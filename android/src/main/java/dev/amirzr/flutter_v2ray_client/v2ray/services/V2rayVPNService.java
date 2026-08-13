package dev.amirzr.flutter_v2ray_client.v2ray.services;

import android.app.Service;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

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
    private ParcelFileDescriptor mInterface;
    private Process process;
    private V2rayConfig v2rayConfig;
    private boolean isRunning = true;

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
        // This process starts fresh on a sticky restart, so the static is back at its
        // default; showNotification() reads it to aim the notification's stop button.
        AppConfigs.V2RAY_CONNECTION_MODE = AppConfigs.V2RAY_CONNECTION_MODES.VPN_TUN;

        // ⚠ startCore() -> showNotification() -> startForeground() is the ONLY
        // startForeground() call on this path, exactly as on the user-initiated one. Do
        // not add a notification-permission check in front of it (6205a88): skipping
        // startForeground() misses the foreground-service deadline and the OS kills the
        // process outright.
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

    private void stopAllProcess() {
        stopForeground(true);
        isRunning = false;
        if (process != null) {
            process.destroy();
        }
        V2rayCoreManager.getInstance().stopCore();
        try {
            stopSelf();
        } catch (Exception e) {
            // ignore
            Log.e("CANT_STOP", "SELF");
        }
        try {
            mInterface.close();
        } catch (Exception e) {
            // ignored
        }

    }

    private void setup() {
        Intent prepare_intent = prepare(this);
        if (prepare_intent != null) {
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
            mInterface.close();
        } catch (Exception e) {
            // ignore
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }

        try {
            mInterface = builder.establish();
            isRunning = true;
            // A tun interface exists, so whatever config produced it is known-good: this
            // is the only place the restore budget may be cleared (see
            // restoreLastKnownGood). Harmless on the user-initiated path, where save()
            // has already cleared it.
            AutoStartStore.noteRestoreSucceeded(this, AutoStartStore.SLOT_VPN);
            runTun2socks();
        } catch (Exception e) {
            Log.e("VPN_SERVICE", "Failed to establish VPN interface", e);
            stopAllProcess();
        }

    }

    private void runTun2socks() {
        ArrayList<String> cmd = new ArrayList<>(
                Arrays.asList(new File(getApplicationInfo().nativeLibraryDir, "libtun2socks.so").getAbsolutePath(),
                        "--netif-ipaddr", "26.26.26.2",
                        "--netif-netmask", "255.255.255.252",
                        "--socks-server-addr", "127.0.0.1:" + v2rayConfig.LOCAL_SOCKS5_PORT,
                        "--tunmtu", "1500",
                        "--sock-path", "sock_path",
                        "--enable-udprelay",
                        "--loglevel", "error"));
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
            Log.e("VPN_SERVICE", "FAILED=>", e);
            this.onDestroy();
        }
    }

    private void sendFileDescriptor() {
        String localSocksFile = new File(getApplicationContext().getFilesDir(), "sock_path").getAbsolutePath();
        FileDescriptor tunFd = mInterface.getFileDescriptor();
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
