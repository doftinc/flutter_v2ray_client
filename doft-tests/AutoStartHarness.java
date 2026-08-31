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
    /** counted at runtime so the suite total cannot be hand-typed wrong */
    static int checks = 0;

    static void check(String name, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.printf("%-46s %s%s%n", name, ok ? "PASS" : "FAIL",
                (ok || detail == null) ? "" : "  " + detail);
    }

    // ── fakes ────────────────────────────────────────────────────────────────────────

    /**
     * ⚠ THE FAKE LIVES IN LosablePrefs, AND ITS apply() CAN BE LOST. It used to be an
     * in-line map whose apply() landed synchronously, which is a fake that cannot tell
     * apply() from commit() and therefore passes with either — see case 15.
     */

    /** A resource table with one real drawable and one real string, like an app has. */
    static class FakeResources extends Resources {
        static final int ICON = 0x7f080001;
        static final int A_STRING = 0x7f0f0002;
        /**
         * What the launcher icon's id is RIGHT NOW. ⚠ aapt renumbers resource ids on every
         * app update, which is the whole reason save() records the resource NAME as well
         * as the number; a fake that could not move the number could not model the update.
         */
        int icon = ICON;

        void renumber(int newId) { icon = newId; }

        public int getIdentifier(String name, String defType, String defPackage) {
            return "com.doft.vpn:mipmap/ic_launcher".equals(name) ? icon : 0;
        }

        public String getResourceName(int id) {
            if (id == ICON || id == icon) return "com.doft.vpn:mipmap/ic_launcher";
            if (id == A_STRING) return "com.doft.vpn:string/app_name";
            throw new RuntimeException("no resource " + id);
        }

        public String getResourceTypeName(int id) {
            if (id == ICON || id == icon) return "mipmap";
            if (id == A_STRING) return "string";
            throw new RuntimeException("no resource " + id);
        }
    }

    static class FakeContext extends Context {
        /** the rarely-written file: the config blob and its schema */
        final LosablePrefs prefs = new LosablePrefs();
        /** the always-written file: timestamps and counters, a few dozen bytes */
        final LosablePrefs state = new LosablePrefs();
        final FakeResources res = new FakeResources();

        /** the process is killed before any unflushed apply() reached the disk */
        void processDied() {
            prefs.processDied();
            state.processDied();
        }

        /** every write from here on is an apply() that never lands */
        FakeContext losingApplies() {
            prefs.dropUnflushedApplies = true;
            state.dropUnflushedApplies = true;
            return this;
        }

        public Context getApplicationContext() { return this; }

        public SharedPreferences getSharedPreferences(String name, int mode) {
            return "doft_v2ray_autostart_state".equals(name) ? state : prefs;
        }

        public Resources getResources() { return res; }

        /** A blob injected by hand still needs the save time load() ages it against. */
        FakeContext armedAt(long millis) {
            state.map.put("vpn_saved_at", millis);
            return this;
        }
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

            // 2. an identical re-save must not rewrite ~100 KB of prefs. Measured as
            //    apply() calls against the FILE the blob lives in, because that is what a
            //    rewrite is; the counters go to the other file.
            int before = ctx.prefs.applies;
            int stateBefore = ctx.state.applies;
            AutoStartStore.save(ctx, SLOT, sample());
            check("identical re-save does not rewrite the blob file", ctx.prefs.applies == before,
                    "applies " + before + " -> " + ctx.prefs.applies);
            check("the timestamp still lands, in the small file", ctx.state.applies > stateBefore,
                    "state applies " + stateBefore + " -> " + ctx.state.applies);

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
            ctx.prefs.map.put("vpn_schema", 2);
            ctx.armedAt(System.currentTimeMillis());
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
            ctx.prefs.map.put("vpn_schema", 2);
            ctx.armedAt(System.currentTimeMillis());
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
            AutoStartStore.noteTunnelCarriedTraffic(ctx, SLOT);
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

        // 13. ⚠ THE DEFAULT IS NO EXPIRY, AND THAT IS THE FIX, NOT AN OVERSIGHT.
        //     A previous revision defaulted to a 7-day TTL and 8 unattended restores.
        //     Always-on VPN is the feature whose users never open the app - that is what
        //     they turned it on for - so those numbers made the feature switch itself off
        //     after a week or eight reboots, and with the kill switch on that is a phone
        //     with no network at all until somebody launches the app. What is bounded is
        //     FAILURE (case 9); a chain that keeps producing tunnels is not bounded.
        {
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, SLOT, sample());

            // a year of reboots on a device nobody has opened the app on
            ctx.state.map.put("vpn_saved_at", System.currentTimeMillis() - 400L * 24 * 3600 * 1000);
            check("a 400-day-old blob with no ttl policy still restores",
                    AutoStartStore.load(ctx, SLOT) != null, "dropped a working config");
            ctx.state.map.put("vpn_restores", 300);
            check("300 unattended restores do not end an unmetered chain",
                    AutoStartStore.load(ctx, SLOT) != null, "dropped a working config");
            check("and the blob is still there to restore from",
                    ctx.prefs.map.get("vpn_config") != null, "dropped");
        }

        // 13b. AN EXPLICIT ttl_ms IS ENFORCED STRICTLY. This is the instrument for a
        //      session that must be metered by an app process; the Dart side sets it
        //      because it is the side that knows the entitlement.
        {
            FakeContext ctx = new FakeContext();
            V2rayConfig metered = sample();
            metered.V2RAY_FULL_JSON_CONFIG =
                    "{\"outbounds\":[],\"_doft_autostart\":{\"ttl_ms\":3600000}}";
            AutoStartStore.save(ctx, SLOT, metered);
            check("a one-hour ttl loads inside the hour", AutoStartStore.load(ctx, SLOT) != null, "null");

            ctx.state.map.put("vpn_saved_at", System.currentTimeMillis() - 3_601_000L);
            check("past its ttl: no tunnel", AutoStartStore.load(ctx, SLOT) == null, "loaded");
            check("past its ttl: dropped", ctx.prefs.map.get("vpn_config") == null, "kept");

            // A boot before the network fixes the clock reads a few hours early; that is
            // normal, and a metered session must not be killed by it.
            FakeContext ctx2 = new FakeContext();
            AutoStartStore.save(ctx2, SLOT, metered);
            ctx2.state.map.put("vpn_saved_at", System.currentTimeMillis() + 3600_000L);
            check("a small backwards clock jump is tolerated",
                    AutoStartStore.load(ctx2, SLOT) != null, "dropped a usable config");

            ctx2.state.map.put("vpn_saved_at", System.currentTimeMillis() + 90L * 24 * 3600 * 1000);
            check("a blob saved 90 days in the future cannot be aged, so it is dropped",
                    AutoStartStore.load(ctx2, SLOT) == null, "loaded");

            // No save time at all (a state file wiped independently of the blob) is not
            // "age zero", it is "unknown" - and a deadline you cannot measure fails closed.
            FakeContext ctx3 = new FakeContext();
            AutoStartStore.save(ctx3, SLOT, metered);
            ctx3.state.map.remove("vpn_saved_at");
            check("a ttl'd blob with no save time is not restorable",
                    AutoStartStore.load(ctx3, SLOT) == null, "loaded");

            // ...whereas an unmetered blob does not need a save time at all, because
            // nothing is being measured. Losing the small state file must not cost an
            // always-on user their tunnel.
            FakeContext ctx4 = new FakeContext();
            AutoStartStore.save(ctx4, SLOT, sample());
            ctx4.state.map.remove("vpn_saved_at");
            check("an unmetered blob survives a wiped state file",
                    AutoStartStore.load(ctx4, SLOT) != null, "dropped");
        }

        // 13c. An explicit finite max_unattended_restores is still enforced.
        {
            FakeContext ctx = new FakeContext();
            V2rayConfig bounded = sample();
            bounded.V2RAY_FULL_JSON_CONFIG =
                    "{\"outbounds\":[],\"_doft_autostart\":{\"max_unattended_restores\":2}}";
            AutoStartStore.save(ctx, SLOT, bounded);
            ctx.state.map.put("vpn_restores", 2);
            check("an explicit restore bound is honoured",
                    AutoStartStore.load(ctx, SLOT) == null, "loaded past its bound");
        }

        // 13d. A blob written by an OLDER build that declares itself non-restorable is
        //      dropped rather than read: save() refuses to write those now, so its only
        //      source is a build whose policy no longer applies.
        {
            FakeContext ctx = new FakeContext();
            ctx.prefs.map.put("vpn_schema", 2);
            ctx.armedAt(System.currentTimeMillis());
            ctx.prefs.map.put("vpn_config",
                    "{\"V2RAY_FULL_JSON_CONFIG\":\"{}\",\"TTL_MS\":0}");
            check("a blob declaring ttl 0 is refused", AutoStartStore.load(ctx, SLOT) == null, "loaded");
            check("...and dropped", ctx.prefs.map.get("vpn_config") == null, "kept");
        }

        // 14. ttl_ms:0 IN THE CORE JSON MEANS "THE APP MUST BE PRESENT FOR THIS SESSION".
        //     It is the Dart side's kill switch for a metered/capped connect, and it has
        //     to work at SAVE time, because the stop that would otherwise clear the blob
        //     is exactly the one that cannot be delivered from the background.
        {
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, SLOT, sample());
            check("armed", AutoStartStore.load(ctx, SLOT) != null, "null");
            V2rayConfig capped = sample();
            capped.V2RAY_FULL_JSON_CONFIG =
                    "{\"outbounds\":[],\"_doft_autostart\":{\"ttl_ms\":0}}";
            AutoStartStore.save(ctx, SLOT, capped);
            check("ttl_ms:0 is not persisted", AutoStartStore.load(ctx, SLOT) == null, "persisted");
            check("ttl_ms:0 also drops what was there", ctx.prefs.map.get("vpn_config") == null, "kept");

            // A shorter-than-default TTL is honoured too.
            FakeContext ctx2 = new FakeContext();
            V2rayConfig shortLived = sample();
            shortLived.V2RAY_FULL_JSON_CONFIG =
                    "{\"outbounds\":[],\"_doft_autostart\":{\"ttl_ms\":60000}}";
            AutoStartStore.save(ctx2, SLOT, shortLived);
            check("a one-minute TTL still loads inside the minute",
                    AutoStartStore.load(ctx2, SLOT) != null, "null");
            ctx2.state.map.put("vpn_saved_at", System.currentTimeMillis() - 61_000L);
            check("and not outside it", AutoStartStore.load(ctx2, SLOT) == null, "loaded");
        }

        // 15. ⚠ apply() IS ASYNCHRONOUS, AND THE ONE FAILURE THIS BUDGET EXISTS TO BOUND
        //     IS THE PROCESS DYING. A restored config that takes :RunSoLibV2RayDaemon
        //     down inside startCore() never lets apply()'s flush thread run, so a charge
        //     written with apply() was never written at all: count still zero, framework
        //     restarts the service, same config, forever. The charge is commit()ed.
        //
        //     First, prove the FAKE can tell the two apart - otherwise everything below
        //     passes with either and this whole case is decoration.
        {
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, SLOT, sample());
            AutoStartStore.beginRestoreAttempt(ctx, SLOT);
            AutoStartStore.beginRestoreAttempt(ctx, SLOT);
            ctx.losingApplies();
            AutoStartStore.noteTunnelCarriedTraffic(ctx, SLOT); // an apply() write, by design
            check("harness self-test: an unflushed apply() is visible in-process",
                    ctx.state.getInt("vpn_failures", -1) == 0, "" + ctx.state.getInt("vpn_failures", -1));
            ctx.processDied();
            check("harness self-test: and is gone once the process dies",
                    ctx.state.getInt("vpn_failures", -1) == 2, "" + ctx.state.getInt("vpn_failures", -1));
        }

        // The charge itself must survive the death it is bounding.
        {
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, SLOT, sample());
            ctx.losingApplies();
            check("the attempt is allowed", AutoStartStore.beginRestoreAttempt(ctx, SLOT), "refused");
            ctx.processDied(); // the restored config killed the process inside startCore()
            check("the failure charge survived the process death",
                    ctx.state.getInt("vpn_failures", 0) == 1, "" + ctx.state.getInt("vpn_failures", 0));
            check("the unattended-restore count survived it too",
                    ctx.state.getInt("vpn_restores", 0) == 1, "" + ctx.state.getInt("vpn_restores", 0));
        }

        // ...so a config that kills the process on every start still runs out of budget.
        {
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, SLOT, sample());
            ctx.losingApplies();
            for (int i = 0; i < 3; i++) {
                AutoStartStore.beginRestoreAttempt(ctx, SLOT);
                ctx.processDied();
            }
            check("three process-killing restores exhaust the budget",
                    !AutoStartStore.beginRestoreAttempt(ctx, SLOT), "a fourth was allowed");
            ctx.processDied();
            check("and the config is dropped even though every process died",
                    ctx.prefs.map.get("vpn_config") == null, "still stored");
        }

        // 16. clear() IS THE FAIL-CLOSED, so it cannot be a write that might not land: a
        //     lost flush leaves a credential blob restorable after the user said stop, or
        //     after another VPN took the slot.
        {
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, SLOT, sample());
            ctx.losingApplies();
            AutoStartStore.clear(ctx, SLOT);
            ctx.processDied(); // the stop raced the framework killing the service
            check("clear() survives the process death: the credential blob is gone",
                    ctx.prefs.map.get("vpn_config") == null, "still on disk");
            check("clear() survives it: the schema key is gone too",
                    ctx.prefs.map.get("vpn_schema") == null, "still on disk");
            check("nothing restores after a clear that raced a kill",
                    AutoStartStore.load(ctx, SLOT) == null, "loaded");
        }

        // 17. clear()'s OTHER FILE. The state prefs hold the failure count, the
        //     unattended-restore count and the save time; reverting THAT commit() to
        //     apply() left the suite green because case 16 only ever looked at the blob
        //     file. A stale failure count that survives a clear is a config the next
        //     app-initiated connect starts three-quarters of the way to being dropped,
        //     and a stale save time can age a fresh blob out on its first restore.
        {
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, SLOT, sample());
            AutoStartStore.beginRestoreAttempt(ctx, SLOT);
            AutoStartStore.beginRestoreAttempt(ctx, SLOT);
            ctx.losingApplies();
            AutoStartStore.clear(ctx, SLOT);
            ctx.processDied();
            check("clear() survives a kill: the failure count is gone from disk",
                    ctx.state.map.get("vpn_failures") == null,
                    "still " + ctx.state.map.get("vpn_failures"));
            check("clear() survives a kill: the unattended-restore count is gone too",
                    ctx.state.map.get("vpn_restores") == null,
                    "still " + ctx.state.map.get("vpn_restores"));
            check("clear() survives a kill: the save time is gone too",
                    ctx.state.map.get("vpn_saved_at") == null,
                    "still " + ctx.state.map.get("vpn_saved_at"));
        }

        // 18. ⚠ A RESOURCE ID IS NOT STABLE ACROSS AN APP UPDATE - aapt renumbers them,
        //     and a stale id reaching setSmallIcon() makes the notification fail to build,
        //     which misses the only startForeground() on the path and gets the process
        //     killed for the FGS deadline (6205a88). save() records the resource NAME for
        //     exactly that reason; deleting that write left the suite green because
        //     nothing ever renumbered anything.
        {
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, SLOT, sample());
            // the app updated: the old numeric id now belongs to nothing
            ctx.res.renumber(0x7f080099);
            V2rayConfig out = AutoStartStore.load(ctx, SLOT);
            check("the icon survives an app update that renumbered it",
                    out != null && out.APPLICATION_ICON == 0x7f080099,
                    "" + (out == null ? "null config" : out.APPLICATION_ICON));
        }

        // 19. BYPASS_SUBNETS ROUND-TRIPS WHEN IT IS NOT NULL. The existing round trip only
        //     ever asserted the null case, so deleting the write entirely stayed green -
        //     and a lost bypass list means traffic that must not enter the tunnel enters
        //     it (LAN, captive portals, the operator's own management ranges).
        {
            FakeContext ctx = new FakeContext();
            V2rayConfig in = sample();
            in.BYPASS_SUBNETS = new ArrayList<>();
            in.BYPASS_SUBNETS.add("192.168.0.0/16");
            in.BYPASS_SUBNETS.add("10.0.0.0/8");
            AutoStartStore.save(ctx, SLOT, in);
            V2rayConfig out = AutoStartStore.load(ctx, SLOT);
            check("bypass subnets survive the round trip",
                    out != null && out.BYPASS_SUBNETS != null
                            && out.BYPASS_SUBNETS.size() == 2
                            && "192.168.0.0/16".equals(out.BYPASS_SUBNETS.get(0))
                            && "10.0.0.0/8".equals(out.BYPASS_SUBNETS.get(1)),
                    out == null ? "null" : String.valueOf(out.BYPASS_SUBNETS));
        }

        // 20. ⚠ A SAVE THAT CANNOT BE RESTORED FROM MUST NOT DESTROY THE ONE THAT CAN.
        //     save() refuses a config with no core JSON. Reverting that guard to a
        //     constant false left the suite green, and the cost of losing it is not "a
        //     bad blob is stored" - it is that the empty write REPLACES the previous
        //     known-good blob, so an always-on device has nothing to restore at all.
        {
            FakeContext ctx = new FakeContext();
            AutoStartStore.save(ctx, SLOT, sample());
            check("armed with a good config", ctx.prefs.map.get("vpn_config") != null, "nothing stored");
            V2rayConfig empty = sample();
            empty.V2RAY_FULL_JSON_CONFIG = "";
            AutoStartStore.save(ctx, SLOT, empty);
            V2rayConfig out = AutoStartStore.load(ctx, SLOT);
            check("a config with an EMPTY core JSON does not overwrite the good one",
                    out != null && out.V2RAY_FULL_JSON_CONFIG != null
                            && !out.V2RAY_FULL_JSON_CONFIG.isEmpty(),
                    "the known-good blob was destroyed");
        }

        System.out.println(failures == 0 ? "ALL PASS" : (failures + " FAILURES"));
        System.out.println("RESULT autostart checks=" + checks + " failures=" + failures);
        if (failures != 0) {
            System.exit(1);
        }
    }
}
