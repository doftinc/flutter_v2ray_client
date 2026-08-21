package dev.amirzr.flutter_v2ray_client.v2ray.services;

import android.app.Service;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import dev.amirzr.flutter_v2ray_client.v2ray.core.Tun2socksArgs;
import dev.amirzr.flutter_v2ray_client.v2ray.core.UnderlyingNetworkPolicy;
import dev.amirzr.flutter_v2ray_client.v2ray.core.V2rayCoreManager;
import dev.amirzr.flutter_v2ray_client.v2ray.interfaces.V2rayServicesListener;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.AppConfigs;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.AutoStartStore;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.V2rayConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;

public class V2rayVPNService extends VpnService implements V2rayServicesListener {
    private static final String TAG = "V2rayVPNService";
    // volatile: the tun2socks watcher thread re-enters runTun2socks() and the sendFd
    // thread reads the descriptor, while stopAllProcess() may be clearing both from
    // whichever thread the core called back on.
    private volatile ParcelFileDescriptor mInterface;
    private Process process;
    private V2rayConfig v2rayConfig;
    private volatile boolean isRunning = true;

    /**
     * How many downlink bytes this tunnel has to carry before the restore that built it
     * counts as a success.
     *
     * <p>⚠ THIS NUMBER EXISTS BECAUSE {@code builder.establish() != null} IS NOT A
     * SUCCESS SIGNAL. A black-holed entry IP establishes a tun, runs a core, completes a
     * handshake and moves nothing — the shape measured on 85.189.101.44 on 2026-08-12,
     * where every transport on both engines read 0 KB/s while the node looked healthy
     * from outside, and the shape of the reality-on-.89 result (CDN connects, volume 0).
     * Clearing the failure budget on establish() therefore let an always-on device
     * restore a dead tunnel on every boot forever.
     *
     * <p><b>DOWNLINK, not uplink</b>: uplink rises whether or not there is anything at the
     * other end. Downlink only rises when the far side answered.
     *
     * <p><b>512 KiB is a judgement, not a measurement.</b> It has to sit above whatever a
     * black hole can return by accident — the handshakes it does complete are a few KB
     * each, and a client that retries all night could accumulate a few tens of KB — and
     * below anything a working tunnel on a phone reaches within minutes of a boot (one
     * app-store metadata refresh is larger). If a device is ever seen losing its blob
     * with a healthy tunnel, this is the number that is wrong, not the mechanism.
     */
    private static final long PROOF_DOWNLINK_BYTES = 512L * 1024L;

    /**
     * How often the proof watcher looks. Nothing is latency-sensitive here — the only
     * deadline is "before the next restore" — so this is deliberately slow and cheap.
     * ⚠ Not final: the test harness lowers it by reflection so the watcher can be driven
     * end to end in a bounded run.
     */
    private static volatile long PROOF_POLL_MS = 5000L;

    /**
     * Bumped by every {@link #setup()} that establishes an interface. A watcher whose
     * generation is stale exits: without it, a tun torn down and rebuilt inside one
     * process would leave the older watcher crediting the newer tunnel's bytes.
     */
    private final java.util.concurrent.atomic.AtomicInteger tunGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public void onCreate() {
        super.onCreate();
        V2rayCoreManager.getInstance().setUpListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppConfigs.V2RAY_SERVICE_COMMANDS startCommand = null;
        if (intent != null) {
            try {
                startCommand = (AppConfigs.V2RAY_SERVICE_COMMANDS) intent.getSerializableExtra("COMMAND");
            } catch (Throwable t) {
                // A foreign or unreadable extra is a start we do not understand, not a
                // reason to take the process down.
                Log.w(TAG, "COMMAND extra could not be read", t);
            }
        }

        if (startCommand == null) {
            // TWO SYSTEM-INITIATED STARTS LAND HERE AND NEITHER CAN CARRY OUR EXTRAS.
            //  * The START_STICKY restart after the process was killed: Android
            //    redelivers a NULL intent. This branch used to answer it with
            //    onDestroy() + START_NOT_STICKY, which is why the START_STICKY returned
            //    at the bottom of this method has never once brought a tunnel back.
            //  * Always-on VPN: the manifest declares SUPPORTS_ALWAYS_ON plus the
            //    android.net.VpnService intent-filter, and our kill switch sends the user
            //    to that OS setting. The framework then starts this service with a bare
            //    action intent - no COMMAND, no V2RAY_CONFIG - so the user turned on the
            //    setting we asked for and got an instantly destroyed service.
            // Both mean "the tunnel should be up". Answer them from the last config that
            // actually started, and only from that.
            return restoreLastKnownGood(intent == null
                    ? "sticky restart (null intent)"
                    : "start with no COMMAND extra (always-on VPN)");
        }

        if (startCommand.equals(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE)) {
            v2rayConfig = (V2rayConfig) intent.getSerializableExtra("V2RAY_CONFIG");
            if (v2rayConfig == null) {
                return stopCleanly("V2RAY_CONFIG is null, cannot start service");
            }
            if (V2rayCoreManager.getInstance().isV2rayCoreRunning()) {
                V2rayCoreManager.getInstance().stopCore();
            }
            if (V2rayCoreManager.getInstance().startCore(v2rayConfig)) {
                Log.i(TAG, "onStartCommand success => v2ray core started.");
                // ONLY HERE, AFTER THE CORE IS ACTUALLY UP. This blob is what a
                // system-initiated start replays; a config that never started must never
                // be replayed.
                AutoStartStore.save(this, AutoStartStore.SLOT_VPN, v2rayConfig);
            } else {
                return stopCleanly("failed to start v2ray core");
            }
        } else if (startCommand.equals(AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE)) {
            V2rayCoreManager.getInstance().stopCore();
            AppConfigs.V2RAY_CONFIG = null;
            // The user turned the tunnel off. Nothing may bring it back: not a sticky
            // restart, not always-on.
            AutoStartStore.clear(this, AutoStartStore.SLOT_VPN);
        } else if (startCommand.equals(AppConfigs.V2RAY_SERVICE_COMMANDS.MEASURE_DELAY)) {
            new Thread(() -> {
                try {
                    String packageName = getPackageName();
                    Intent sendB = new Intent(packageName + ".CONNECTED_V2RAY_SERVER_DELAY");
                    sendB.setPackage(packageName);
                    sendB.putExtra("DELAY", String.valueOf(V2rayCoreManager.getInstance().getConnectedV2rayServerDelay()));
                    sendBroadcast(sendB);
                } catch (Exception e) {
                    Log.w("V2rayVPNService", "Failed to send delay broadcast", e);
                }
            }, "MEASURE_CONNECTED_V2RAY_SERVER_DELAY").start();
        } else {
            return stopCleanly("unknown command received");
        }
        return START_STICKY;
    }

    /**
     * Bring the tunnel back from the last config that started the core, for a start the
     * system made (sticky restart or always-on). Any doubt at all and the service stops:
     * a VPN service that is up without a tunnel is worse than one that is down.
     */
    private int restoreLastKnownGood(final String reason) {
        Log.i(TAG, "system-initiated start => " + reason);

        // Idempotent. The framework re-sends the always-on start intent, and tearing a
        // healthy tunnel down to rebuild it would be a self-inflicted outage.
        if (V2rayCoreManager.getInstance().isV2rayCoreRunning()) {
            Log.i(TAG, "core is already running, nothing to restore");
            return START_STICKY;
        }

        final V2rayConfig restored = AutoStartStore.load(this, AutoStartStore.SLOT_VPN);
        if (restored == null) {
            // Either the user stopped the tunnel (we cleared the store) or the blob could
            // not be read back (load() dropped it). Nothing to start: fail closed.
            return stopCleanly("no usable persisted config");
        }

        // ⚠ prepare() NON-NULL MEANS "ASK THE USER", AND THERE IS NOBODY TO ASK. There is
        // no Activity on a system-initiated start, so the consent intent cannot be
        // launched; and starting the core without an established tun interface would put
        // the traffic on the wire outside the tunnel. Stop, cleanly, once.
        try {
            if (VpnService.prepare(this) != null) {
                return stopCleanly("VPN consent not granted and no Activity to ask with");
            }
        } catch (Throwable t) {
            Log.w(TAG, "VpnService.prepare failed", t);
            return stopCleanly("VpnService.prepare failed");
        }

        // A config that fails to start fails the same way every time, and always-on is
        // retried by the framework whatever we return. Spend the budget, then give up.
        if (!AutoStartStore.beginRestoreAttempt(this, AutoStartStore.SLOT_VPN)) {
            return stopCleanly("restore budget spent, persisted config dropped");
        }

        v2rayConfig = restored;
        // ⚠ THE PROOF DEPENDS ON THE COUNTERS RUNNING. V2rayCoreManager only polls
        // queryStats when the config asks for traffic statistics, and the whole bound on
        // this restore chain is "did downlink bytes move" (see PROOF_DOWNLINK_BYTES). A
        // restored session with statistics off would never be able to prove itself, and
        // would burn its failure budget while working perfectly. The cost is one
        // queryStats per outbound tag per second, on a path with no app process watching.
        restored.ENABLE_TRAFFIC_STATICS = true;
        // This process starts fresh on a sticky restart, so the static is back at its
        // default; showNotification() reads it to aim the notification's stop button.
        AppConfigs.V2RAY_CONNECTION_MODE = AppConfigs.V2RAY_CONNECTION_MODES.VPN_TUN;

        // ⚠ startCore() -> showNotification() -> startForeground() is the ONLY
        // startForeground() call on this path, exactly as on the user-initiated one. Do
        // not add a notification-permission check in front of it (6205a88): skipping
        // startForeground() misses the foreground-service deadline and the OS kills the
        // process outright.
        //
        // ⚠ NOT VERIFIED ON A DEVICE, AND KNOWN INCOMPLETE ON API 31+. Always-on gets an
        // FGS-start exemption from the framework (addPowerSaveTempWhitelistApp with
        // REASON_VPN); a plain START_STICKY restart does not obviously get one. If
        // startForeground() is refused there it throws
        // ForegroundServiceStartNotAllowedException INSIDE showNotification(), whose
        // catch(Exception) swallows it - so startCore() still returns true and this
        // method still reports a restored tunnel. The tunnel itself is real (setup() has
        // to establish a tun before anything is cleared), but the service is running
        // without a foreground notification and the OS may kill it shortly after. The
        // only way to tell the two apart is a device test on API 31+: kill
        // :RunSoLibV2RayDaemon while connected and watch for the notification. Fixing it
        // properly means owning the startForeground() call here instead of inside
        // V2rayCoreManager, which is another stream's file this round.
        if (!V2rayCoreManager.getInstance().startCore(restored)) {
            return stopCleanly("restored config did not start the core");
        }
        // ⚠ The failure budget is NOT cleared here. startCore() returning true means the
        // core loop started, not that a tunnel exists - setup() still has to establish
        // the tun, and it can fail. The budget is cleared in setup(), once there is a
        // real interface; otherwise a config that starts a core and then dies would keep
        // resetting its own budget and loop forever.
        Log.i(TAG, "tunnel restored from persisted config => " + restored.REMARK);
        // NOTHING IS SENT TO DART HERE, ON PURPOSE. The app process may not exist - that
        // is the whole point of this path - and the only channel that exists is the
        // per-second V2RAY_CONNECTION_INFO broadcast the core already sends, which is
        // delivered to a RUNTIME-registered receiver (V2rayController.init). With no app
        // process there is nobody registered and the broadcast is a no-op; once the app
        // runs again it registers and picks the state up on the next tick, which is the
        // same reconciliation it already does on resume. Waking the app from here would
        // mean a manifest receiver that launches a process the user did not ask for.
        return START_STICKY;
    }

    /**
     * Stop for real. The old code called onDestroy() by hand, which runs the cleanup but
     * does NOT stop the service: the service stayed alive with no core and, when it had
     * been launched with startForegroundService(), no startForeground() either - which is
     * the shape the OS kills with ForegroundServiceDidNotStartInTimeException.
     */
    private int stopCleanly(final String why) {
        Log.w(TAG, "stopping service => " + why);
        try {
            stopForeground(true);
        } catch (Exception e) {
            Log.w(TAG, "stopForeground failed", e);
        }
        try {
            stopSelf();
        } catch (Exception e) {
            Log.w(TAG, "stopSelf failed", e);
        }
        return START_NOT_STICKY;
    }

    /**
     * The real stop: no core, no tun2socks, no tun, no service. Every failure path in
     * this class ends here, and none of them may end in {@code this.onDestroy()} — that
     * runs the cleanup but leaves the SERVICE alive, which after a START_STICKY return is
     * a process with no tunnel that the framework will keep restarting.
     *
     * <p>Safe to call twice and safe to call before {@link #setup()} ever ran, which it
     * now is: the interface may legitimately be null here.
     */
    private void stopAllProcess() {
        // ⚠ FIRST, AND OUTSIDE EVERY TRY BELOW. A NetworkCallback outlives the service that
        // registered it — the framework holds the reference — so a stop that skipped this
        // would leak one callback per connect and keep calling setUnderlyingNetworks on a
        // tunnel that no longer exists.
        unwatchUnderlyingNetwork();
        isRunning = false;
        try {
            stopForeground(true);
        } catch (Exception e) {
            Log.w(TAG, "stopForeground failed", e);
        }
        if (process != null) {
            try {
                process.destroy();
            } catch (Exception e) {
                Log.w(TAG, "could not destroy tun2socks", e);
            }
            process = null;
        }
        try {
            V2rayCoreManager.getInstance().stopCore();
        } catch (Exception e) {
            Log.w(TAG, "stopCore failed", e);
        }
        try {
            stopSelf();
        } catch (Exception e) {
            Log.e("CANT_STOP", "SELF");
        }
        try {
            if (mInterface != null) {
                mInterface.close();
            }
        } catch (Exception e) {
            // ignored
        }
        mInterface = null;
    }

    private void setup() {
        // ⚠ NOT A BARE RETURN. prepare() non-null means we are not the prepared VPN, so
        // there will be no tun - and the core is ALREADY RUNNING when this listener
        // callback fires. Returning here left a core with no tunnel, which is the shape
        // where traffic leaves in clear.
        Intent prepare_intent = prepare(this);
        if (prepare_intent != null) {
            Log.e(TAG, "not the prepared VPN, refusing to run a core with no tun");
            stopAllProcess();
            return;
        }
        Builder builder = new Builder();
        builder.setSession(v2rayConfig.REMARK);
        builder.setMtu(1500);
        builder.addAddress("26.26.26.1", 30);

        if (v2rayConfig.BYPASS_SUBNETS == null || v2rayConfig.BYPASS_SUBNETS.isEmpty()) {
            builder.addRoute("0.0.0.0", 0);
        } else {
            for (String subnet : v2rayConfig.BYPASS_SUBNETS) {
                String[] parts = subnet.split("/");
                if (parts.length == 2) {
                    String address = parts[0];
                    int prefixLength = Integer.parseInt(parts[1]);
                    builder.addRoute(address, prefixLength);
                }
            }
        }
        if (v2rayConfig.BLOCKED_APPS != null) {
            for (int i = 0; i < v2rayConfig.BLOCKED_APPS.size(); i++) {
                try {
                    builder.addDisallowedApplication(v2rayConfig.BLOCKED_APPS.get(i));
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        try {
            JSONObject json = new JSONObject(v2rayConfig.V2RAY_FULL_JSON_CONFIG);
            if (json.has("dns")) {
                JSONObject dnsObject = json.getJSONObject("dns");
                if (dnsObject.has("servers")) {
                    JSONArray serversArray = dnsObject.getJSONArray("servers");
                    for (int i = 0; i < serversArray.length(); i++) {
                        try {
                            Object entry = serversArray.get(i);
                            if (entry instanceof String) {
                                builder.addDnsServer((String) entry);
                            } else if (entry instanceof JSONObject) {
                                JSONObject obj = (JSONObject) entry;
                                if (obj.has("address")) {
                                    builder.addDnsServer(obj.getString("address"));
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            // If parsing fails, add sane fallback DNS
            try {
                builder.addDnsServer("1.1.1.1");
            } catch (Exception ignored) {
            }
            try {
                builder.addDnsServer("8.8.8.8");
            } catch (Exception ignored) {
            }
        }
        try {
            if (mInterface != null) {
                mInterface.close();
                mInterface = null;
            }
        } catch (Exception e) {
            // ignore
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }

        // ⚠ establish() RETURNS NULL, IT DOES NOT THROW, when we are not (or are no
        // longer) the prepared VPN - another VPN took the slot between prepare() above
        // and here, or the framework revoked us on an app update. The old code assigned
        // that null, declared the service running, cleared the restore budget, and then
        // NPE'd inside sendFileDescriptor() - where the NPE was caught by runTun2socks'
        // own handler. Net effect: core up, no tun, budget back at zero, so
        // MAX_CONSECUTIVE_FAILURES could never bite and a sticky restart looped forever.
        ParcelFileDescriptor tun = null;
        try {
            tun = builder.establish();
        } catch (Exception e) {
            Log.e("VPN_SERVICE", "Failed to establish VPN interface", e);
        }
        if (tun == null) {
            // Do NOT clear the restore budget: this attempt produced no tunnel, and the
            // budget is the only thing that ends the restart loop.
            Log.e("VPN_SERVICE", "builder.establish() produced no tun interface");
            stopAllProcess();
            return;
        }
        mInterface = tun;
        isRunning = true;
        // ⚠ THE DAEMON HAD NO IDEA THE NETWORK EVER CHANGED. This service runs in its own
        // process; the only NetworkCallback in the whole tree lived in the Flutter Activity,
        // which is the wrong process, is torn down when the app is backgrounded, and does not
        // exist at all on an always-on or sticky start. So when the user walked out of the
        // house nothing here noticed, and `setUnderlyingNetworks` was never called even once.
        watchUnderlyingNetwork();
        // ⚠ THE BUDGET IS NOT CLEARED HERE, AND A TUN IS NOT A SUCCESS. This line used to
        // be AutoStartStore.noteRestoreSucceeded(...), on the reasoning that an interface
        // in hand means the tunnel works. It does not: a BLACK-HOLED ENTRY IP gives us a
        // tun, a running core and a completed handshake while moving zero bytes, so that
        // clear let an always-on device restore a dead tunnel on every boot forever with
        // the budget reset each time — and with the kill switch on, the user has no
        // connectivity and no signal at all. The budget is now cleared only by the
        // watcher below, once downlink bytes have actually moved.
        startRestoreProofWatcher();
        runTun2socks();
    }

    /**
     * Watch the tunnel this {@link #setup()} just built until it proves itself by carrying
     * {@link #PROOF_DOWNLINK_BYTES} of DOWNLINK traffic, and only then clear the restore
     * failure budget.
     *
     * <p>⚠ THIS IS THE BOUND ON THE RESTORE CHAIN, AND IT IS NOT A TIMER. Nothing here
     * expires and nothing here counts reboots: a config that keeps working restores for
     * as long as the user wants it to, which is the whole reason the round-2 expiry was
     * removed (always-on is used by people who never open the app; a bound that switches
     * it off after N boots or D days is a date on which their phone loses the network).
     * What is bounded is a config that keeps FAILING — and "failing" now means "carried
     * nothing", not "did not give us a tun", because the failure this exists to stop is
     * the one where the tun is perfect and the far side is a black hole.
     *
     * <p>The thread is a DAEMON: it must never hold the daemon process up, and there is
     * no teardown path that is guaranteed to run on a kill.
     *
     * <p><b>Known residual risk, stated plainly.</b> A device that restores, moves less
     * than {@link #PROOF_DOWNLINK_BYTES} through the tunnel, and is killed again — three
     * times consecutively — loses its blob and has to be re-armed by opening the app.
     * That is a real hole for a phone that is offline or idle across three consecutive
     * boots. It is accepted because from inside {@code :RunSoLibV2RayDaemon} an idle
     * tunnel and a black-holed one are the same observation, and of the two failure modes
     * the black hole is the unrecoverable one: a dropped blob is repaired by launching the
     * app, whereas a restored black hole reports itself connected forever and never is.
     */
    private void startRestoreProofWatcher() {
        final int generation = tunGeneration.incrementAndGet();
        // The counter is not reset between two startCore() calls inside one process, so
        // the proof is measured against where this tunnel started, not against zero.
        final long baseline = downlinkBytes();
        final Thread t = new Thread(() -> {
            try {
                while (isRunning && generation == tunGeneration.get()) {
                    if (restoreProofTick(baseline)) {
                        return;
                    }
                    Thread.sleep(PROOF_POLL_MS);
                }
            } catch (InterruptedException ignored) {
                // teardown
            } catch (Throwable other) {
                Log.w(TAG, "restore proof watcher stopped", other);
            }
        }, "RestoreProof_Thread");
        t.setDaemon(true);
        t.start();
    }

    /**
     * One evaluation of the proof. Package-private and separate from the thread so the
     * test harness can drive it deterministically.
     *
     * @return true once the tunnel has proved itself and the budget has been cleared
     */
    boolean restoreProofTick(final long baseline) {
        final long moved = downlinkBytes() - baseline;
        if (moved < PROOF_DOWNLINK_BYTES) {
            return false;
        }
        Log.i(TAG, "tunnel carried " + moved + " downlink bytes => restore proved");
        AutoStartStore.noteTunnelCarriedTraffic(this, AutoStartStore.SLOT_VPN);
        return true;
    }

    /** Total downlink bytes the core has counted since it was initialised; 0 on error. */
    private long downlinkBytes() {
        try {
            return V2rayCoreManager.getInstance().getTotalDownloadBytes();
        } catch (Throwable t) {
            Log.w(TAG, "could not read the downlink counter", t);
            return 0L;
        }
    }

    /**
     * The exact command line {@link #runTun2socks()} execs.
     *
     * <p>⚠ PUBLIC ONLY SO A TEST CAN READ IT, and that is the point rather than an
     * apology. Building this vector needed a live VpnService, so the single word that
     * decides whether this device can carry a datagram at all was unreachable by every
     * test in this repo — and it was wrong for as long as anyone can measure. A seam
     * that reads the REAL field on the REAL service is what makes "the config asked for
     * udpgw and the process got udpgw" an assertion instead of a hope.
     */
    public ArrayList<String> tun2socksCommand() {
        return Tun2socksArgs.build(
                new File(getApplicationInfo().nativeLibraryDir, "libtun2socks.so").getAbsolutePath(),
                v2rayConfig.LOCAL_SOCKS5_PORT,
                1500,
                v2rayConfig.TUN2SOCKS_UDP_MODE);
    }

    /** Registered while the tunnel is up; see [stopAllProcess] for the unregister. */
    private android.net.ConnectivityManager.NetworkCallback netCb;

    /**
     * Tell the framework which network is carrying the tunnel, and keep telling it.
     *
     * <p>⚠ IT DECLARES, IT DOES NOT DIAL. Reconnecting on the network-change edge was measured
     * on 2026-08-12: Wi-Fi off at +61.8 s, re-dial at +63.3 s while LTE was not usable yet, and
     * the transport chain then burned to EXHAUSTED by +137 s on a device whose cellular was
     * fine. The engines re-bind their own sockets; what they cannot do is tell the SYSTEM which
     * network is underneath, and that is all this does.
     *
     * <p>⚠ AND IT NEVER GUESSES. A network is declared only when the system says it has
     * internet, is not itself a VPN and is VALIDATED (see {@link UnderlyingNetworkPolicy}); a
     * loss clears the declaration back to null, which means "follow the system default" — the
     * behaviour we already had. Declaring a dying Wi-Fi is strictly worse than declaring
     * nothing.
     *
     * <p>Best-effort and never throws: on a device where the request cannot be registered the
     * tunnel comes up exactly as it did before.
     */
    private void watchUnderlyingNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        try {
            final android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            unwatchUnderlyingNetwork();
            android.net.NetworkRequest req = new android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            netCb = new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(android.net.Network n,
                        android.net.NetworkCapabilities caps) {
                    if (!isRunning || caps == null) {
                        return;
                    }
                    boolean validated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            && caps.hasCapability(
                                    android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    boolean ok = UnderlyingNetworkPolicy.shouldDeclare(
                            caps.hasCapability(
                                    android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET),
                            caps.hasCapability(
                                    android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                            validated);
                    if (!ok) {
                        return;
                    }
                    try {
                        setUnderlyingNetworks(new android.net.Network[] {n});
                        Log.i(TAG, "underlying network declared: " + n);
                    } catch (Throwable t) {
                        Log.w(TAG, "could not declare the underlying network", t);
                    }
                }

                @Override
                public void onLost(android.net.Network n) {
                    if (!isRunning || !UnderlyingNetworkPolicy.clearOnLost()) {
                        return;
                    }
                    try {
                        // null = follow the system default. NEVER the next candidate: this
                        // callback does not know what replaced it.
                        setUnderlyingNetworks(null);
                        Log.i(TAG, "underlying network cleared after a loss");
                    } catch (Throwable t) {
                        Log.w(TAG, "could not clear the underlying network", t);
                    }
                }
            };
            cm.registerNetworkCallback(req, netCb);
        } catch (Throwable t) {
            Log.w(TAG, "could not watch the underlying network", t);
            netCb = null;
        }
    }

    /** Idempotent; safe to call when nothing is registered. */
    private void unwatchUnderlyingNetwork() {
        if (netCb == null) {
            return;
        }
        try {
            final android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.unregisterNetworkCallback(netCb);
            }
        } catch (Throwable t) {
            // Already gone, or the framework refused. Either way there is nothing to undo.
        }
        netCb = null;
    }

    private void runTun2socks() {
        // ⚠ THE UDP MODE IS THE WHOLE REASON THIS LINE MOVED OUT OF HERE. It used to
        // read `--enable-udprelay`, which selects badvpn's udpgw framing and needs a
        // udpgw server; `--socks-server-addr` is xray's SOCKS5 inbound, which does not
        // speak udpgw, so every datagram this device produced went into a socket nobody
        // could parse while TCP kept working. Measured, and the reasoning is in
        // Tun2socksArgs — which is a separate class precisely so a test can read this
        // command line without a VpnService.
        ArrayList<String> cmd = tun2socksCommand();
        Log.i(TAG, "tun2socks udp mode: "
                + Tun2socksArgs.normaliseUdpMode(v2rayConfig.TUN2SOCKS_UDP_MODE));
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(cmd);
            processBuilder.redirectErrorStream(true);
            process = processBuilder.directory(getApplicationContext().getFilesDir()).start();
            new Thread(() -> {
                try {
                    process.waitFor();
                    if (isRunning) {
                        runTun2socks();
                    }
                } catch (InterruptedException e) {
                    // ignore
                }
            }, "Tun2socks_Thread").start();
            sendFileDescriptor();
        } catch (Exception e) {
            // ⚠ THIS WAS this.onDestroy(). Calling it by hand runs the cleanup but does
            // NOT stop the service, so a failed tun2socks left a live service with no
            // tunnel - and onStartCommand had already returned START_STICKY, so the
            // framework kept it that way.
            Log.e("VPN_SERVICE", "FAILED=>", e);
            stopAllProcess();
        }
    }

    private void sendFileDescriptor() {
        final ParcelFileDescriptor tun = mInterface;
        if (tun == null) {
            // The watcher thread re-enters runTun2socks() after tun2socks exits; by then
            // stopAllProcess() may already have closed the interface. This used to be an
            // NPE swallowed by runTun2socks' catch, which then called onDestroy() by hand.
            Log.w(TAG, "no tun interface to hand to tun2socks");
            return;
        }
        String localSocksFile = new File(getApplicationContext().getFilesDir(), "sock_path").getAbsolutePath();
        FileDescriptor tunFd = tun.getFileDescriptor();
        new Thread(() -> {
            int tries = 0;
            while (true) {
                try {
                    Thread.sleep(50L * tries);
                    LocalSocket clientLocalSocket = new LocalSocket();
                    clientLocalSocket
                            .connect(new LocalSocketAddress(localSocksFile, LocalSocketAddress.Namespace.FILESYSTEM));
                    if (!clientLocalSocket.isConnected()) {
                        Log.e("SOCK_FILE", "Unable to connect to localSocksFile [" + localSocksFile + "]");
                    } else {
                        Log.e("SOCK_FILE", "connected to sock file [" + localSocksFile + "]");
                    }
                    OutputStream clientOutStream = clientLocalSocket.getOutputStream();
                    clientLocalSocket.setFileDescriptorsForSend(new FileDescriptor[] { tunFd });
                    clientOutStream.write(32);
                    clientLocalSocket.setFileDescriptorsForSend(null);
                    clientLocalSocket.shutdownOutput();
                    clientLocalSocket.close();
                    break;
                } catch (Exception e) {
                    Log.e(V2rayVPNService.class.getSimpleName(), "sendFd failed =>", e);
                    if (tries > 5)
                        break;
                    tries += 1;
                }
            }
        }, "sendFd_Thread").start();
    }

    @Override
    public void onDestroy() {
        Log.i("V2rayVPNService", "onDestroy called - cleaning up resources");
        isRunning = false;
        
        // Stop the V2ray core
        try {
            if (V2rayCoreManager.getInstance().isV2rayCoreRunning()) {
                V2rayCoreManager.getInstance().stopCore();
            }
        } catch (Exception e) {
            Log.e("V2rayVPNService", "Error stopping V2ray core in onDestroy", e);
        }
        
        // Stop foreground service and remove notification
        try {
            stopForeground(true);
        } catch (Exception e) {
            Log.e("V2rayVPNService", "Error stopping foreground in onDestroy", e);
        }
        
        // Destroy tun2socks process
        try {
            if (process != null) {
                process.destroy();
                process = null;
            }
        } catch (Exception e) {
            Log.e("V2rayVPNService", "Error destroying process in onDestroy", e);
        }
        
        // Close VPN interface
        try {
            if (mInterface != null) {
                mInterface.close();
                mInterface = null;
            }
        } catch (Exception e) {
            Log.e("V2rayVPNService", "Error closing VPN interface in onDestroy", e);
        }
        
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        // The user revoked our VPN consent, or another app took the VPN slot. That is the
        // user turning the tunnel off, so it must not come back on a system start.
        AutoStartStore.clear(this, AutoStartStore.SLOT_VPN);
        stopAllProcess();
    }

    @Override
    public boolean onProtect(int socket) {
        return protect(socket);
    }

    @Override
    public Service getService() {
        return this;
    }

    @Override
    public void startService() {
        setup();
    }

    @Override
    public void stopService() {
        stopAllProcess();
    }
}
