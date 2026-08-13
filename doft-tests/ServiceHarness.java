import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import dev.amirzr.flutter_v2ray_client.v2ray.core.V2rayCoreManager;
import dev.amirzr.flutter_v2ray_client.v2ray.services.V2rayProxyOnlyService;
import dev.amirzr.flutter_v2ray_client.v2ray.services.V2rayVPNService;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.AppConfigs;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.AutoStartStore;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.V2rayConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs the REAL V2rayVPNService and V2rayProxyOnlyService — the two classes that decide
 * what happens when Android, not the user, starts us.
 *
 * <p>⚠ WHY THIS EXISTS. The previous round shipped the restore path with no test over it
 * at all: reverting both service files to 84424a2 left every other assertion in this
 * suite green, because they only ever exercised the key-value store underneath. The cases
 * below are chosen so that the revert goes RED — each one names a state the shipped code
 * could reach and must not:
 *
 * <ul>
 *   <li>a null intent (START_STICKY redelivery) answered with suicide, so the
 *       START_STICKY at the bottom of onStartCommand never brought a tunnel back;</li>
 *   <li>a bare action intent (always-on VPN) answered the same way;</li>
 *   <li>{@code builder.establish()} RETURNING NULL — it does not throw — being treated as
 *       a working tunnel: budget cleared, core left running, no tun;</li>
 *   <li>{@code this.onDestroy()} called by hand as a "stop", which runs the cleanup and
 *       leaves the service alive.</li>
 * </ul>
 *
 * <p>The service classes are subclassed here ONLY to redirect Context plumbing
 * (SharedPreferences, Resources) at in-memory fakes. No method under test is overridden.
 */
public class ServiceHarness {

    static int failures = 0;

    /**
     * Run one case. ⚠ A CASE THAT THROWS IS A FAILED CASE, NOT A FAILED RUN: the pre-fix
     * V2rayVPNService NPEs inside setup() when establish() hands back null, and letting
     * that abort the JVM would hide every case after it.
     */
    static void run(Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            failures++;
            System.out.printf("%-58s %s  %s%n", "(case threw)", "FAIL", t);
        }
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-58s %s%s%n", name, ok ? "PASS" : "FAIL",
                (ok || detail == null) ? "" : "  " + detail);
    }

    // ── fakes ────────────────────────────────────────────────────────────────────────

    static class Prefs implements SharedPreferences {
        final Map<String, Object> map = new HashMap<>();
        /** every apply() rewrites the whole XML on a device; this counts them. */
        int applies = 0;

        public String getString(String k, String def) {
            Object v = map.get(k);
            return v instanceof String ? (String) v : def;
        }

        public int getInt(String k, int def) {
            Object v = map.get(k);
            return v instanceof Integer ? (Integer) v : def;
        }

        public long getLong(String k, long def) {
            Object v = map.get(k);
            return v instanceof Long ? (Long) v : def;
        }

        public Editor edit() {
            final Map<String, Object> staged = new HashMap<>();
            final ArrayList<String> removed = new ArrayList<>();
            return new Editor() {
                public Editor putString(String k, String v) { staged.put(k, v); return this; }
                public Editor putInt(String k, int v) { staged.put(k, v); return this; }
                public Editor putLong(String k, long v) { staged.put(k, v); return this; }
                public Editor remove(String k) { removed.add(k); return this; }
                public void apply() {
                    applies++;
                    for (String k : removed) {
                        map.remove(k);
                    }
                    map.putAll(staged);
                }
            };
        }
    }

    /** The on-disk state a killed process leaves behind: survives the service instance. */
    static class Disk {
        final Map<String, Prefs> files = new HashMap<>();

        Prefs file(String name) {
            Prefs p = files.get(name);
            if (p == null) {
                p = new Prefs();
                files.put(name, p);
            }
            return p;
        }

        Prefs config() { return file("doft_v2ray_autostart"); }

        Prefs state() { return file("doft_v2ray_autostart_state"); }

        boolean hasVpnBlob() { return config().map.get("vpn_config") != null; }

        boolean hasProxyBlob() { return config().map.get("proxy_config") != null; }

        int vpnFailures() { return state().getInt("vpn_failures", 0); }

        int vpnRestores() { return state().getInt("vpn_restores", 0); }
    }

    static class FakeResources extends Resources {
        static final int ICON = 0x7f080001;

        public int getIdentifier(String name, String defType, String defPackage) {
            return "com.doft.vpn:mipmap/ic_launcher".equals(name) ? ICON : 0;
        }

        public String getResourceName(int id) {
            if (id == ICON) return "com.doft.vpn:mipmap/ic_launcher";
            throw new RuntimeException("no resource " + id);
        }

        public String getResourceTypeName(int id) {
            if (id == ICON) return "mipmap";
            throw new RuntimeException("no resource " + id);
        }
    }

    static class TestVpn extends V2rayVPNService {
        final Disk disk;
        final Resources res = new FakeResources();

        TestVpn(Disk d) { disk = d; }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return disk.file(name); }
        @Override public Resources getResources() { return res; }
        @Override public Context getApplicationContext() { return this; }
    }

    static class TestProxy extends V2rayProxyOnlyService {
        final Disk disk;
        final Resources res = new FakeResources();

        TestProxy(Disk d) { disk = d; }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return disk.file(name); }
        @Override public Resources getResources() { return res; }
        @Override public Context getApplicationContext() { return this; }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────

    static V2rayConfig config(String policy) {
        V2rayConfig c = new V2rayConfig();
        c.CONNECTED_V2RAY_SERVER_ADDRESS = "204.3.207.89";
        c.CONNECTED_V2RAY_SERVER_PORT = "443";
        c.LOCAL_SOCKS5_PORT = 10808;
        c.LOCAL_HTTP_PORT = 10809;
        c.V2RAY_FULL_JSON_CONFIG = "{\"outbounds\":[{\"tag\":\"proxy\"}]"
                + (policy == null ? "" : ",\"_doft_autostart\":" + policy) + "}";
        c.ENABLE_TRAFFIC_STATICS = true;
        c.REMARK = "Marseille";
        c.APPLICATION_NAME = "Doft VPN";
        c.NOTIFICATION_DISCONNECT_BUTTON_NAME = "Disconnect";
        c.APPLICATION_ICON = FakeResources.ICON;
        return c;
    }

    static Intent command(AppConfigs.V2RAY_SERVICE_COMMANDS cmd, V2rayConfig cfg) {
        Intent i = new Intent();
        i.putExtra("COMMAND", cmd);
        if (cfg != null) {
            i.putExtra("V2RAY_CONFIG", cfg);
        }
        return i;
    }

    /** A start the USER made: the only thing that arms the store. */
    static void userStart(TestVpn s, V2rayConfig cfg) {
        V2rayCoreManager.coreRunning = false;
        s.onCreate();
        s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, cfg), 0, 1);
    }

    /** A start ANDROID made after killing the process: new instance, same disk, null intent. */
    static int stickyRestart(TestVpn s) {
        V2rayCoreManager.coreRunning = false;
        s.onCreate();
        return s.onStartCommand(null, 0, 1);
    }

    static void resetWorld() {
        V2rayCoreManager.reset();
        VpnService.reset();
        AppConfigs.V2RAY_CONNECTION_MODE = AppConfigs.V2RAY_CONNECTION_MODES.VPN_TUN;
    }

    public static void main(String[] args) {

        // 1. THE DEFECT ITSELF. Android redelivers a NULL intent when it restarts a
        //    service that returned START_STICKY. Answering it with onDestroy() +
        //    START_NOT_STICKY is why no tunnel has ever come back from a kill.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            check("armed: a user start persists the config", disk.hasVpnBlob(), "nothing stored");

            TestVpn restarted = new TestVpn(disk);
            int r = stickyRestart(restarted);
            check("sticky restart (null intent) returns START_STICKY",
                    r == android.app.Service.START_STICKY, "returned " + r);
            check("sticky restart starts the core again",
                    V2rayCoreManager.startCoreCalls == 2, "startCore calls " + V2rayCoreManager.startCoreCalls);
            check("sticky restart does not stop the service",
                    !restarted.stopSelfCalled, "stopSelf was called");
        });

        // 2. ALWAYS-ON. The framework starts a VpnService with a bare action intent: not
        //    null, but with no COMMAND extra. Same answer required.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));

            TestVpn alwaysOn = new TestVpn(disk);
            V2rayCoreManager.coreRunning = false;
            alwaysOn.onCreate();
            int r = alwaysOn.onStartCommand(new Intent("android.net.VpnService"), 0, 1);
            check("always-on (bare action intent) returns START_STICKY",
                    r == android.app.Service.START_STICKY, "returned " + r);
            check("always-on starts the core", V2rayCoreManager.startCoreCalls == 2,
                    "startCore calls " + V2rayCoreManager.startCoreCalls);
        });

        // 3. Nothing armed => nothing to restore. Fail closed, and stop for real.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            int r = stickyRestart(s);
            check("no persisted config: START_NOT_STICKY", r == android.app.Service.START_NOT_STICKY,
                    "returned " + r);
            check("no persisted config: service actually stops", s.stopSelfCalled, "still alive");
            check("no persisted config: core never started", V2rayCoreManager.startCoreCalls == 0,
                    "" + V2rayCoreManager.startCoreCalls);
        });

        // 4. NO CONSENT, NO BUDGET SPENT. prepare() non-null means "ask the user", and a
        //    system-initiated start has no Activity to ask with. That is not the config's
        //    fault, so it must not be charged against the failure budget - otherwise a
        //    user who grants a different VPN for a day comes back to a dropped config.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            VpnService.prepareResult = new Intent("consent");

            TestVpn s = new TestVpn(disk);
            int r = stickyRestart(s);
            check("no VPN consent: START_NOT_STICKY", r == android.app.Service.START_NOT_STICKY,
                    "returned " + r);
            check("no VPN consent: does not charge the budget", disk.vpnFailures() == 0,
                    "failures " + disk.vpnFailures());
            check("no VPN consent: config is kept for later", disk.hasVpnBlob(), "dropped");
            check("no VPN consent: core is not started", V2rayCoreManager.startCoreCalls == 1,
                    "" + V2rayCoreManager.startCoreCalls);
        });

        // 5. AN EXPLICIT STOP IS FINAL. Nothing may bring the tunnel back afterwards.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            userStart(s, config(null));
            s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE, null), 0, 2);
            check("STOP_SERVICE clears the slot", !disk.hasVpnBlob(), "blob survived the stop");
            check("STOP_SERVICE clears the credential-bearing blob",
                    disk.config().map.get("vpn_config") == null, "still stored");

            int r = stickyRestart(new TestVpn(disk));
            check("after STOP_SERVICE nothing restores", r == android.app.Service.START_NOT_STICKY,
                    "returned " + r);
        });

        // 6. ⚠ THE ONE THE WHOLE DESIGN RESTED ON. builder.establish() RETURNS NULL - it
        //    does not throw - when we are not the prepared VPN any more (another VPN took
        //    the slot between prepare() and here, or an app update revoked us). The old
        //    code assigned that null, called noteRestoreSucceeded, and NPE'd later inside
        //    sendFileDescriptor(), where runTun2socks' own catch swallowed it into a hand
        //    -called onDestroy(): core up, no tun, budget back at zero, restart forever.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));

            TestVpn s = new TestVpn(disk);
            stickyRestart(s);
            check("null establish: budget was charged for the attempt", disk.vpnFailures() == 1,
                    "failures " + disk.vpnFailures());
            VpnService.establishResult = null;
            s.startService(); // what xray's startup() callback does: runs setup()
            check("null establish: budget stays charged (no false success)",
                    disk.vpnFailures() == 1, "failures " + disk.vpnFailures());
            check("null establish: the core is stopped, not left running",
                    !V2rayCoreManager.coreRunning, "core still running");
            check("null establish: the service is stopped for real", s.stopSelfCalled,
                    "service left alive");
        });

        // 7. A REAL TUN CLEARS THE BUDGET - and the tun2socks binary that cannot start
        //    (there is none in a test JVM) must end in a real stop, not in the
        //    hand-called onDestroy() that left the service alive.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));

            TestVpn s = new TestVpn(disk);
            stickyRestart(s);
            check("restore charges the budget up front", disk.vpnFailures() == 1,
                    "failures " + disk.vpnFailures());
            VpnService.establishResult = new ParcelFileDescriptor();
            s.startService();
            check("a real tun clears the failure budget", disk.vpnFailures() == 0,
                    "failures " + disk.vpnFailures());
            check("tun2socks that cannot start stops the SERVICE, not just its resources",
                    s.stopSelfCalled, "onDestroy-style cleanup left the service alive");
            check("tun2socks failure stops the core too", !V2rayCoreManager.coreRunning,
                    "core still running");
        });

        // 8. IDEMPOTENT. The framework re-sends the always-on start intent; tearing a
        //    healthy tunnel down to rebuild it would be a self-inflicted outage.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            V2rayCoreManager.coreRunning = true;
            TestVpn s = new TestVpn(disk);
            s.onCreate();
            int r = s.onStartCommand(null, 0, 1);
            check("restore is idempotent while the core runs",
                    r == android.app.Service.START_STICKY && V2rayCoreManager.startCoreCalls == 1,
                    "returned " + r + ", startCore " + V2rayCoreManager.startCoreCalls);
        });

        // 9. THE BUDGET ENDS THE LOOP. Always-on is retried by the framework whatever we
        //    return, so a config that never produces a tun must be dropped eventually.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            VpnService.establishResult = null;
            int last = 0;
            for (int i = 0; i < 3; i++) {
                TestVpn s = new TestVpn(disk);
                last = stickyRestart(s);
                s.startService();
            }
            check("three tunnel-less restores are allowed", last == android.app.Service.START_STICKY,
                    "returned " + last);
            TestVpn fourth = new TestVpn(disk);
            int r = stickyRestart(fourth);
            check("the fourth is refused", r == android.app.Service.START_NOT_STICKY, "returned " + r);
            check("and the config is dropped", !disk.hasVpnBlob(), "still stored");
        });

        // 10. ⚠ THE SECOND CONNECT INTENT MUST NOT OUTLIVE THE GRANT. A restore chain
        //     that WORKS is exactly the one nothing is metering: there is no app process
        //     behind it. noteRestoreSucceeded may reset the failure budget; it must not
        //     reset the unattended-restore budget, or the chain refills itself forever.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config("{\"max_unattended_restores\":2}"));
            for (int i = 0; i < 2; i++) {
                TestVpn s = new TestVpn(disk);
                stickyRestart(s);
                VpnService.establishResult = new ParcelFileDescriptor();
                s.startService();
            }
            check("successful restores still count against the unattended budget",
                    disk.vpnRestores() == 2, "restores " + disk.vpnRestores());
            TestVpn third = new TestVpn(disk);
            int r = stickyRestart(third);
            check("an unattended chain ends at its bound", r == android.app.Service.START_NOT_STICKY,
                    "returned " + r);
            check("the config is dropped, so the app must re-arm it", !disk.hasVpnBlob(), "still stored");

            // And an app-initiated connect refills it - that is the whole point.
            TestVpn again = new TestVpn(disk);
            userStart(again, config("{\"max_unattended_restores\":2}"));
            check("an app-initiated connect re-arms the store",
                    disk.hasVpnBlob() && disk.vpnRestores() == 0,
                    "blob " + disk.hasVpnBlob() + ", restores " + disk.vpnRestores());
        });

        // 11. AN EXPIRY THE BLOB CARRIES. A session killed before a data cap was hit must
        //     not be restorable indefinitely with no app process to meter it.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            // eight days later, on a device that has not run the app since
            disk.state().map.put("vpn_saved_at",
                    System.currentTimeMillis() - 8L * 24L * 60L * 60L * 1000L);
            int r = stickyRestart(new TestVpn(disk));
            check("a blob past its TTL does not restore", r == android.app.Service.START_NOT_STICKY,
                    "returned " + r);
            check("a blob past its TTL is dropped", !disk.hasVpnBlob(), "still stored");
        });

        // 12. ttl_ms:0 IS A KILL SWITCH THE DART SIDE CAN SET AT CONNECT TIME, for a
        //     session it must be present to meter. It is refused at save() time, so it
        //     works even when the later stop cannot be delivered.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            check("armed before the non-restorable connect", disk.hasVpnBlob(), "nothing stored");
            userStart(new TestVpn(disk), config("{\"ttl_ms\":0}"));
            check("ttl_ms:0 is not persisted", !disk.hasVpnBlob(), "persisted anyway");
            int r = stickyRestart(new TestVpn(disk));
            check("ttl_ms:0 cannot be resurrected", r == android.app.Service.START_NOT_STICKY,
                    "returned " + r);
        });

        // 13. THE CONFIG FILE IS NOT REWRITTEN BY A RECONNECT TO THE SAME NODE. apply()
        //     rewrites the whole XML it is staged against, so the timestamps and counters
        //     live in a different preferences file from the ~100 KB blob.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            int blobWrites = disk.config().applies;
            userStart(new TestVpn(disk), config(null));
            check("same config again does not rewrite the blob file",
                    disk.config().applies == blobWrites,
                    "applies " + blobWrites + " -> " + disk.config().applies);
            check("but the state file is updated", disk.state().applies > 1,
                    "applies " + disk.state().applies);
        });

        // ── proxy-only service ───────────────────────────────────────────────────────

        // 14. Same null-intent defect, same answer.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestProxy p = new TestProxy(disk);
            p.onCreate();
            p.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, config(null)), 0, 1);
            check("proxy: a user start persists the config", disk.hasProxyBlob(), "nothing stored");

            V2rayCoreManager.coreRunning = false;
            TestProxy restarted = new TestProxy(disk);
            restarted.onCreate();
            int r = restarted.onStartCommand(null, 0, 1);
            check("proxy: sticky restart returns START_STICKY",
                    r == android.app.Service.START_STICKY, "returned " + r);
            check("proxy: sticky restart starts the core",
                    V2rayCoreManager.startCoreCalls == 2, "" + V2rayCoreManager.startCoreCalls);
        });

        // 15. ⚠ THE PROXY HAS NO TUN TO POINT AT, so it has NO evidence that a restore
        //     worked - startCore() returning true only means startLoop() did not throw.
        //     Clearing the budget there is the pattern V2rayVPNService's own comment
        //     forbids: the chain would refill its own budget and never end.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestProxy p = new TestProxy(disk);
            p.onCreate();
            p.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, config(null)), 0, 1);
            int last = 0;
            for (int i = 0; i < 3; i++) {
                V2rayCoreManager.coreRunning = false;
                TestProxy s = new TestProxy(disk);
                s.onCreate();
                last = s.onStartCommand(null, 0, 1);
            }
            check("proxy: three restores are allowed", last == android.app.Service.START_STICKY,
                    "returned " + last);
            V2rayCoreManager.coreRunning = false;
            TestProxy fourth = new TestProxy(disk);
            fourth.onCreate();
            int r = fourth.onStartCommand(null, 0, 1);
            check("proxy: a restore does not refill its own budget",
                    r == android.app.Service.START_NOT_STICKY, "returned " + r);
            check("proxy: the config is dropped", !disk.hasProxyBlob(), "still stored");
        });

        System.out.println(failures == 0 ? "ALL PASS" : (failures + " FAILURES"));
        if (failures != 0) {
            System.exit(1);
        }
    }
}
