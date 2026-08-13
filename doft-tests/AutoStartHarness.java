import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import dev.amirzr.flutter_v2ray_client.v2ray.utils.AutoStartStore;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.V2rayConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs the REAL AutoStartStore — the thing that decides what a system-initiated start
 * (sticky restart / always-on VPN) does.
 *
 * <p>⚠ WHAT IS ACTUALLY BEING TESTED IS THE FAILURE SIDE. A store that round-trips is
 * the easy half; the half that matters is that an unreadable, stale or foreign blob
 * yields NO TUNNEL and NO EXCEPTION, because the caller's only two options are "start the
 * core" and "stop the service", and it runs when there is no app process to repair
 * anything. The corrupt/short/foreign-schema cases below are the ones a Java-serialized
 * V2rayConfig would have failed after the next app update.
 */
public class AutoStartHarness {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-46s %s%s%n", name, ok ? "PASS" : "FAIL",
                (ok || detail == null) ? "" : "  " + detail);
    }

    // ── fakes ────────────────────────────────────────────────────────────────────────

    /** A working in-memory SharedPreferences: the round trip is real, not mocked out. */
    static class FakePrefs implements SharedPreferences {
        final Map<String, Object> map = new HashMap<>();
        int writes = 0;

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
                    for (String k : removed) {
                        map.remove(k);
                    }
                    map.putAll(staged);
                    if (staged.containsKey("vpn_config")) {
                        writes++;
                    }
                }
            };
        }
    }

    /** A resource table with one real drawable and one real string, like an app has. */
    static class FakeResources extends Resources {
        static final int ICON = 0x7f080001;
        static final int A_STRING = 0x7f0f0002;

        public int getIdentifier(String name, String defType, String defPackage) {
            return "com.doft.vpn:mipmap/ic_launcher".equals(name) ? ICON : 0;
        }

        public String getResourceName(int id) {
            if (id == ICON) return "com.doft.vpn:mipmap/ic_launcher";
            if (id == A_STRING) return "com.doft.vpn:string/app_name";
            throw new RuntimeException("no resource " + id);
        }

        public String getResourceTypeName(int id) {
            if (id == ICON) return "mipmap";
            if (id == A_STRING) return "string";
            throw new RuntimeException("no resource " + id);
        }
    }

    static class FakeContext extends Context {
        final FakePrefs prefs = new FakePrefs();
        final Resources res = new FakeResources();

        public Context getApplicationContext() { return this; }
        public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
        public Resources getResources() { return res; }
    }

    static V2rayConfig sample() {
        V2rayConfig c = new V2rayConfig();
        c.CONNECTED_V2RAY_SERVER_ADDRESS = "204.3.207.89";
        c.CONNECTED_V2RAY_SERVER_PORT = "443";
        c.LOCAL_SOCKS5_PORT = 10808;
        c.LOCAL_HTTP_PORT = 10809;
        c.BLOCKED_APPS = new ArrayList<>();
        c.BLOCKED_APPS.add("com.example.blocked");
        c.BYPASS_SUBNETS = null; // null and empty mean different routes; both must survive
        c.V2RAY_FULL_JSON_CONFIG = "{\"outbounds\":[{\"tag\":\"proxy\"}],\"_doft_tuic\":{\"uuid\":\"x\"}}";
        c.ENABLE_TRAFFIC_STATICS = true;
        c.REMARK = "Marseille";
        c.APPLICATION_NAME = "Doft VPN";
        c.NOTIFICATION_DISCONNECT_BUTTON_NAME = "Disconnect";
        c.APPLICATION_ICON = FakeResources.ICON;
        return c;
    }

    static final String SLOT = AutoStartStore.SLOT_VPN;

    public static void main(String[] args) {
        // 1. round trip — every field the tun builder and the notification read
        {
            FakeContext ctx = new FakeContext();
            V2rayConfig in = sample();
            AutoStartStore.save(ctx, SLOT, in);
            V2rayConfig out = AutoStartStore.load(ctx, SLOT);
            check("round trip: config survives", out != null, "load returned null");
            if (out != null) {
                check("round trip: core JSON verbatim",
                        in.V2RAY_FULL_JSON_CONFIG.equals(out.V2RAY_FULL_JSON_CONFIG), out.V2RAY_FULL_JSON_CONFIG);
                check("round trip: socks port", out.LOCAL_SOCKS5_PORT == 10808, "" + out.LOCAL_SOCKS5_PORT);
                check("round trip: http port", out.LOCAL_HTTP_PORT == 10809, "" + out.LOCAL_HTTP_PORT);
                check("round trip: remark", "Marseille".equals(out.REMARK), out.REMARK);
                check("round trip: traffic stats flag", out.ENABLE_TRAFFIC_STATICS, "false");
                check("round trip: blocked apps kept",
                        out.BLOCKED_APPS != null && out.BLOCKED_APPS.size() == 1, "" + out.BLOCKED_APPS);
                // ⚠ NOT the same as an empty list: setup() adds a default route only when
                // BYPASS_SUBNETS is null/empty, and an empty list where null was meant
                // would silently change the routes of a restored tunnel.
                check("round trip: null bypass stays null", out.BYPASS_SUBNETS == null, "" + out.BYPASS_SUBNETS);
                check("round trip: icon resolves", out.APPLICATION_ICON == FakeResources.ICON,
                        "" + out.APPLICATION_ICON);
            }

            // 2. an identical re-save must not rewrite ~100 KB of prefs
            int before = ctx.prefs.writes;
            AutoStartStore.save(ctx, SLOT, sample());
            check("identical re-save does not rewrite the blob", ctx.prefs.writes == before,
                    "writes " + before + " -> " + ctx.prefs.writes);

            // 3. clear = the user turned it off. Nothing may come back.
            AutoStartStore.clear(ctx, SLOT);
            check("clear: nothing to restore", AutoStartStore.load(ctx, SLOT) == null, "still loadable");
            check("clear: credential-bearing blob is gone",
                    ctx.prefs.map.get("vpn_config") == null, "" + ctx.prefs.map.get("vpn_config"));
        }

        // 4. FAIL CLOSED: a corrupt blob returns null and DROPS itself (no crash loop)
        {
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, SLOT, sample());
            ctx.prefs.map.put("vpn_config", "{not json at all");
            check("corrupt blob: no tunnel", AutoStartStore.load(ctx, SLOT) == null, "loaded something");
            check("corrupt blob: dropped, not retried forever",
                    ctx.prefs.map.get("vpn_config") == null, "still stored");
        }

        // 5. FAIL CLOSED: a blob from another schema is dropped unread
        {
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, SLOT, sample());
            ctx.prefs.map.put("vpn_schema", 99);
            check("foreign schema: no tunnel", AutoStartStore.load(ctx, SLOT) == null, "loaded something");
            check("foreign schema: dropped", ctx.prefs.map.get("vpn_config") == null, "still stored");
        }

        // 6. FAIL CLOSED: a blob with no core JSON is not a config
        {
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, SLOT, sample());
            ctx.prefs.map.put("vpn_config", "{\"REMARK\":\"Marseille\"}");
            check("no core JSON: no tunnel", AutoStartStore.load(ctx, SLOT) == null, "loaded something");
        }

        // 7. DEGRADES SAFELY ACROSS AN APP UPDATE. This is the whole reason the store is
        //    named JSON and not a serialized V2rayConfig: a field added by a newer build
        //    and a field dropped by an older one must both still boot the tunnel, where
        //    readObject() would have thrown InvalidClassException on the first start
        //    after the update — with no app process around to fix it.
        {
            FakeContext ctx = new FakeContext();
            ctx.prefs.map.put("vpn_schema", 1);
            ctx.prefs.map.put("vpn_config",
                    "{\"V2RAY_FULL_JSON_CONFIG\":\"{\\\"outbounds\\\":[]}\","
                            + "\"REMARK\":\"Old build\","
                            + "\"A_FIELD_FROM_THE_FUTURE\":{\"nested\":true}}");
            V2rayConfig out = AutoStartStore.load(ctx, SLOT);
            check("older/newer field set still loads", out != null, "load returned null");
            if (out != null) {
                check("unknown key ignored", "Old build".equals(out.REMARK), out.REMARK);
                check("missing key takes its default", out.LOCAL_SOCKS5_PORT == 10808,
                        "" + out.LOCAL_SOCKS5_PORT);
                check("missing lists stay null",
                        out.BLOCKED_APPS == null && out.BYPASS_SUBNETS == null, "not null");
            }
        }

        // 8. A RESOURCE ID IS NOT STABLE ACROSS AN APP UPDATE. A stale number that now
        //    names a string would reach setSmallIcon(), break the notification build, and
        //    take startForeground() down with it — which is the FGS-deadline kill that
        //    6205a88 exists to prevent. Name first, then type-check, then fall back.
        {
            FakeContext ctx = new FakeContext();
            ctx.prefs.map.put("vpn_schema", 1);
            ctx.prefs.map.put("vpn_config",
                    "{\"V2RAY_FULL_JSON_CONFIG\":\"{}\",\"APPLICATION_ICON\":"
                            + FakeResources.A_STRING + "}");
            V2rayConfig out = AutoStartStore.load(ctx, SLOT);
            check("stale icon id that names a string is refused",
                    out != null && out.APPLICATION_ICON != FakeResources.A_STRING,
                    out == null ? "null config" : "" + out.APPLICATION_ICON);
            check("stale icon falls back to something drawable",
                    out != null && out.APPLICATION_ICON == android.R.drawable.sym_def_app_icon,
                    out == null ? "null config" : "" + out.APPLICATION_ICON);

            // The recorded NAME wins over a number that has since moved.
            ctx.prefs.map.put("vpn_config",
                    "{\"V2RAY_FULL_JSON_CONFIG\":\"{}\",\"APPLICATION_ICON\":123456,"
                            + "\"APPLICATION_ICON_NAME\":\"com.doft.vpn:mipmap/ic_launcher\"}");
            V2rayConfig out2 = AutoStartStore.load(ctx, SLOT);
            check("recorded resource name beats a renumbered id",
                    out2 != null && out2.APPLICATION_ICON == FakeResources.ICON,
                    out2 == null ? "null config" : "" + out2.APPLICATION_ICON);
        }

        // 9. The restore budget. Always-on is retried by the framework whatever we
        //    return, so a config that cannot start must eventually be dropped.
        {
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, SLOT, sample());
            boolean a1 = AutoStartStore.beginRestoreAttempt(ctx, SLOT);
            boolean a2 = AutoStartStore.beginRestoreAttempt(ctx, SLOT);
            boolean a3 = AutoStartStore.beginRestoreAttempt(ctx, SLOT);
            boolean a4 = AutoStartStore.beginRestoreAttempt(ctx, SLOT);
            check("budget allows three attempts", a1 && a2 && a3, a1 + "," + a2 + "," + a3);
            check("budget refuses the fourth", !a4, "still allowed");
            check("budget exhausted drops the config",
                    AutoStartStore.load(ctx, SLOT) == null, "still loadable");
        }

        // 10. A tunnel that actually came up clears the budget it spent.
        {
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, SLOT, sample());
            AutoStartStore.beginRestoreAttempt(ctx, SLOT);
            AutoStartStore.beginRestoreAttempt(ctx, SLOT);
            AutoStartStore.noteRestoreSucceeded(ctx, SLOT);
            boolean stillAllowed = AutoStartStore.beginRestoreAttempt(ctx, SLOT)
                    && AutoStartStore.beginRestoreAttempt(ctx, SLOT)
                    && AutoStartStore.beginRestoreAttempt(ctx, SLOT);
            check("a working restore resets the budget", stillAllowed, "budget not reset");
        }

        // 11. The two services must not restore each other's config.
        {
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, AutoStartStore.SLOT_PROXY, sample());
            check("proxy config does not resurrect the VPN slot",
                    AutoStartStore.load(ctx, AutoStartStore.SLOT_VPN) == null, "leaked across slots");
            check("proxy slot itself round-trips",
                    AutoStartStore.load(ctx, AutoStartStore.SLOT_PROXY) != null, "lost");
        }

        // 12. Nothing here may ever throw into onStartCommand.
        {
            check("null context is survivable",
                    AutoStartStore.load(null, SLOT) == null, "returned a config");
            AutoStartStore.save(null, SLOT, sample());
            AutoStartStore.clear(null, SLOT);
            check("null context: no exception escaped", true, null);
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, SLOT, null);
            V2rayConfig noJson = sample();
            noJson.V2RAY_FULL_JSON_CONFIG = null;
            AutoStartStore.save(ctx, SLOT, noJson);
            check("a config with no core JSON is never persisted",
                    AutoStartStore.load(ctx, SLOT) == null, "persisted anyway");
        }

        System.out.println(failures == 0 ? "ALL PASS" : (failures + " FAILURES"));
        if (failures != 0) {
            System.exit(1);
        }
    }
}
