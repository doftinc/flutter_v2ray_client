package dev.amirzr.flutter_v2ray_client.v2ray.core;

import android.util.Log;

/**
 * The JSON half of TUIC config assembly, with NO dependency on libv2ray or on any
 * Android class beyond Log — so it can be compiled and run on a laptop.
 *
 * <p>⚠ WHY IT IS ITS OWN CLASS. This logic decides, when the TUIC listener did not come
 * up, whether an outbound is dropped or kept. Get that wrong for a PRIMARY and xray falls
 * through to the next outbound, which is `freedom` — every byte the user believes is
 * tunnelled leaves the device in clear. It lived inside V2rayCoreManager, which pulls in
 * the AAR and a VpnService, so nothing could exercise it; doft-tests/run.sh now does.
 */
final class TuicConfigRewriter {
    private static final String TAG = "TuicConfigRewriter";

    private TuicConfigRewriter() {}

    /**
     * The JSON half of {@link #applyTuic}: point every TUIC outbound at the port the
     * listener actually bound, and decide what to do when there is none.
     *
     * <p>Split out of applyTuic so it can be TESTED. The rest of that method calls into
     * libv2ray, which needs an Android device and the AAR — so the one piece whose
     * mistakes cost a tunnel (or, in the primary case, leak traffic outside one) was the
     * one piece nothing could exercise. This takes the port as an argument, so a test can
     * pass 0 and assert the fail-closed behaviour directly.
     *
     * <p>Package-visible rather than private for the same reason.
     */
    public static String rewrite(org.json.JSONObject root, long port) {
        try {
            org.json.JSONArray outs = root.optJSONArray("outbounds");
            if (outs == null) {
                return root.toString();
            }
            org.json.JSONArray kept = new org.json.JSONArray();
            for (int i = 0; i < outs.length(); i++) {
                org.json.JSONObject o = outs.optJSONObject(i);
                String tag = (o == null) ? "" : o.optString("tag", "");
                // ⚠ THE PRIMARY IS FOUND BY MARKER, NOT BY TAG. It must keep the tag
                // `proxy`: the Dart balancer looks the base outbound up by exactly that
                // string and builds no group at all without it, so matching the primary
                // by a `proxy-tuic` tag would have silently disabled Android's only
                // background failover on every device that chose TUIC. The marker is
                // stripped here, before the core parses anything — same contract as the
                // `_doft_tuic` credential block.
                boolean isPrimary = (o != null) && o.optBoolean("_doft_tuic_primary", false);
                if (o != null) {
                    o.remove("_doft_tuic_primary");
                }
                if (!isPrimary && !tag.startsWith("proxy-tuic")) {
                    kept.put(outs.get(i));
                    continue;
                }
                org.json.JSONObject settings = o.optJSONObject("settings");
                org.json.JSONArray servers =
                        (settings == null) ? null : settings.optJSONArray("servers");
                if (port <= 0 || servers == null) {
                    // ⚠ A MEMBER IS DROPPED. THE PRIMARY IS NOT — IT FAILS CLOSED.
                    // Dropping a member is right: it would fail every probe and the
                    // balancer's interval is scaled by member count, so a dead member is
                    // paid for by every live one, and the tunnel comes up on the others
                    // exactly as if TUIC were not configured.
                    //
                    // Dropping the PRIMARY is a different thing entirely. When the
                    // transport is pinned there is no balancer and no catch-all rule, so
                    // xray falls back to outbounds[0] — and the next outbound in that
                    // config is `freedom`. Removing the primary would therefore promote
                    // DIRECT: every byte the user believes is tunnelled would leave the
                    // device in clear. Keeping the dead outbound fails every dial
                    // instead, which the app's payload probe detects in seconds and
                    // fails over from. A tunnel that does not come up is recoverable; a
                    // tunnel that silently is not a tunnel is not.
                    if (isPrimary) {
                        Log.e(TAG,
                                "tuic: listener unavailable (port=" + port + ") and this is"
                                        + " the PRIMARY — failing closed rather than"
                                        + " promoting the direct outbound");
                        kept.put(outs.get(i));
                        continue;
                    }
                    Log.w(TAG,
                            "tuic: dropping " + tag + " (port=" + port + ")");
                    continue;
                }
                for (int k = 0; k < servers.length(); k++) {
                    org.json.JSONObject s = servers.optJSONObject(k);
                    if (s != null) {
                        s.put("port", port);
                    }
                }
                kept.put(o);
            }
            root.put("outbounds", kept);
            if (port > 0) {
                Log.i(TAG,
                        "tuic: listening on 127.0.0.1:" + port);
            }
        } catch (Throwable e) {
            // Anything unexpected while rewriting: hand back the stripped config as it
            // stood. Worst case a proxy-tuic member survives with port 0 and fails its
            // probes — bounded, and never a failed connect for an unrelated transport.
            Log.e(TAG, "tuic: rewrite failed", e);
        }
        return root.toString();
    }
}
