package dev.amirzr.flutter_v2ray_client.v2ray.core;
// Same package, so the package-private rewriter is visible without opening it up.

import org.json.JSONArray;
import org.json.JSONObject;

/** Runs the REAL rewriteTuicOutbounds against the shapes the Dart side emits. */
public class TuicRewriteHarness {
    static int failures = 0;
    /** counted at runtime so the suite total cannot be hand-typed wrong */
    static int checks = 0;

    static void check(String name, boolean ok, String detail) {
        checks++;
        if (!ok) failures++;
        System.out.printf("%-52s %s  %s%n", name, ok ? "PASS" : "FAIL", detail);
    }

    static JSONObject cfg(JSONArray outs) {
        return new JSONObject().put("outbounds", outs);
    }

    static JSONObject tuicOut(String tag, boolean primary) {
        JSONObject o = new JSONObject()
                .put("tag", tag)
                .put("protocol", "socks")
                .put("settings", new JSONObject().put("servers",
                        new JSONArray().put(new JSONObject()
                                .put("address", "127.0.0.1").put("port", 0))));
        if (primary) o.put("_doft_tuic_primary", true);
        return o;
    }

    static JSONObject plain(String tag, String proto) {
        return new JSONObject().put("tag", tag).put("protocol", proto);
    }

    static JSONArray outbounds(JSONObject res) {
        return new JSONObject(res.toString()).getJSONArray("outbounds");
    }

    public static void main(String[] a) {
        // 1. PRIMARY, listener up: port substituted, marker gone, tag still `proxy`.
        JSONObject r = new JSONObject(TuicConfigRewriter.rewrite(
                cfg(new JSONArray().put(tuicOut("proxy", true)).put(plain("direct", "freedom"))), 41234));
        JSONArray o = r.getJSONArray("outbounds");
        JSONObject p = o.getJSONObject(0);
        check("primary up: port substituted",
                p.getJSONObject("settings").getJSONArray("servers")
                        .getJSONObject(0).getInt("port") == 41234, "port=41234");
        check("primary up: marker stripped before the core sees it",
                !p.has("_doft_tuic_primary"), "no _doft_tuic_primary");
        check("primary up: tag stays `proxy` (the balancer looks it up)",
                "proxy".equals(p.getString("tag")), "tag=" + p.getString("tag"));

        // 2. PRIMARY, listener DOWN: must FAIL CLOSED, never promote `direct`.
        r = new JSONObject(TuicConfigRewriter.rewrite(
                cfg(new JSONArray().put(tuicOut("proxy", true)).put(plain("direct", "freedom"))), 0));
        o = r.getJSONArray("outbounds");
        check("primary down: outbound[0] is NOT the direct outbound",
                !"freedom".equals(o.getJSONObject(0).optString("protocol")),
                "outbounds[0].protocol=" + o.getJSONObject(0).optString("protocol"));
        check("primary down: primary kept (fails closed)",
                "proxy".equals(o.getJSONObject(0).optString("tag")) && o.length() == 2,
                "len=" + o.length() + " tag0=" + o.getJSONObject(0).optString("tag"));
        check("primary down: marker still stripped",
                !o.getJSONObject(0).has("_doft_tuic_primary"), "no marker");

        // 3. MEMBER, listener down: dropped, as before.
        r = new JSONObject(TuicConfigRewriter.rewrite(
                cfg(new JSONArray().put(plain("proxy", "vless"))
                        .put(tuicOut("proxy-tuic", false))), 0));
        o = r.getJSONArray("outbounds");
        check("member down: dropped",
                o.length() == 1 && "proxy".equals(o.getJSONObject(0).getString("tag")),
                "len=" + o.length());

        // 4. MEMBER, listener up: substituted and kept.
        r = new JSONObject(TuicConfigRewriter.rewrite(
                cfg(new JSONArray().put(plain("proxy", "vless"))
                        .put(tuicOut("proxy-tuic", false))), 5555));
        o = r.getJSONArray("outbounds");
        check("member up: substituted and kept",
                o.length() == 2 && o.getJSONObject(1).getJSONObject("settings")
                        .getJSONArray("servers").getJSONObject(0).getInt("port") == 5555,
                "port=5555");

        // 5. Non-TUIC outbounds are never touched.
        r = new JSONObject(TuicConfigRewriter.rewrite(
                cfg(new JSONArray().put(plain("proxy", "vless"))
                        .put(plain("proxy-hy2", "hysteria")).put(plain("direct", "freedom"))), 7777));
        o = r.getJSONArray("outbounds");
        check("unrelated outbounds untouched", o.length() == 3, "len=" + o.length());

        // ── the keys that are ours and not xray's ────────────────────────────────────
        // ⚠ THE DELAY PATH NEVER WENT THROUGH THE START PATH. getV2rayServerDelay hands a
        // config straight to the core, so `_doft_tuic` — which carries the device's
        // credential — was reaching measureOutboundDelay untouched, i.e. exactly what
        // applyTuic's own comment says must never happen. Adding a second private key
        // made that this change's problem rather than an inherited one.
        String withBoth = new JSONObject()
                .put("_doft_tuic", new JSONObject().put("uuid", "SECRET"))
                .put("_doft_android", new JSONObject().put("udp_mode", "socks5"))
                .put("outbounds", new JSONArray().put(plain("proxy", "vless")))
                .toString();
        String stripped = TuicConfigRewriter.stripPrivateKeys(withBoth);
        check("strip: the credential block is gone",
                !stripped.contains("_doft_tuic") && !stripped.contains("SECRET"), stripped);
        check("strip: the android marker is gone",
                !stripped.contains("_doft_android"), stripped);
        check("strip: the outbounds survive",
                new JSONObject(stripped).getJSONArray("outbounds").length() == 1, stripped);
        String cleanCfg = new JSONObject()
                .put("outbounds", new JSONArray().put(plain("proxy", "vless"))).toString();
        check("strip: a config with neither key is returned untouched",
                cleanCfg.equals(TuicConfigRewriter.stripPrivateKeys(cleanCfg)), cleanCfg);
        check("strip: unparseable input is returned as-is, not dropped",
                "not json".equals(TuicConfigRewriter.stripPrivateKeys("not json")), "not json");
        check("strip: null in, null out",
                TuicConfigRewriter.stripPrivateKeys(null) == null, "null");

        System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILURE(S)");
        System.out.println("RESULT tuic checks=" + checks + " failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
