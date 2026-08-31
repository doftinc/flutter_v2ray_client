package dev.amirzr.flutter_v2ray_client.v2ray.utils;

import android.content.Context;
import android.util.Log;

import dev.amirzr.flutter_v2ray_client.v2ray.core.V2rayCoreManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class Utilities {

    public static void CopyFiles(InputStream src, File dst) throws IOException {
        try (OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = src.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    public static String getUserAssetsPath(Context context) {
        File extDir = context.getExternalFilesDir("assets");
        if (extDir == null) {
            return "";
        }
        if (!extDir.exists()) {
            return context.getDir("assets", 0).getAbsolutePath();
        } else {
            return extDir.getAbsolutePath();
        }
    }

    public static void copyAssets(final Context context) {
        String extFolder = getUserAssetsPath(context);
        try {
            String geo = "geosite.dat,geoip.dat";
            for (String assets_obj : context.getAssets().list("")) {
                if (geo.contains(assets_obj)) {
                    CopyFiles(context.getAssets().open(assets_obj), new File(extFolder, assets_obj));
                }
            }
        } catch (Exception e) {
            Log.e("Utilities", "copyAssets failed=>", e);
        }
    }


    public static String convertIntToTwoDigit(int value) {
        if (value < 10) return "0" + value;
        else return value + "";
    }


    public static V2rayConfig parseV2rayJsonFile(final String remark, String config, final ArrayList<String> blockedApplication, final ArrayList<String> bypass_subnets) {
        final V2rayConfig v2rayConfig = new V2rayConfig();
        v2rayConfig.REMARK = remark;
        v2rayConfig.BLOCKED_APPS = blockedApplication;
        v2rayConfig.BYPASS_SUBNETS = bypass_subnets;
        v2rayConfig.APPLICATION_ICON = AppConfigs.APPLICATION_ICON;
        v2rayConfig.APPLICATION_NAME = AppConfigs.APPLICATION_NAME;
        v2rayConfig.NOTIFICATION_DISCONNECT_BUTTON_NAME = AppConfigs.NOTIFICATION_DISCONNECT_BUTTON_NAME;
        try {
            JSONObject config_json = new JSONObject(config);
            // ⚠ READ AND REMOVED IN THE SAME BREATH, BEFORE ANYTHING ELSE CAN FAIL.
            // `_doft_android` is OUR key, not xray's: it carries how tun2socks should
            // relay UDP (see Tun2socksArgs). xray must never see it — an unknown
            // top-level key is at best ignored and at worst a parse error, which would
            // take the tunnel down for every Android user at once.
            //
            // ⚠ AND THE RE-SERIALISE IS THE WHOLE POINT OF DOING IT HERE. `config` is
            // the string that becomes V2RAY_FULL_JSON_CONFIG and is handed to the core;
            // the only other place this method writes it back is inside
            // `if (AppConfigs.ENABLE_TRAFFIC_AND_SPEED_STATICS)`. Removing a key from
            // `config_json` alone therefore does nothing at all whenever statics are
            // off — which is exactly how the pre-existing `policy`/`stats` removals
            // below have always behaved. Test: "the marker is gone with statics off".
            org.json.JSONObject doftAndroid =
                    config_json.optJSONObject(dev.amirzr.flutter_v2ray_client.v2ray.core
                            .Tun2socksArgs.CONFIG_KEY);
            v2rayConfig.TUN2SOCKS_UDP_MODE = dev.amirzr.flutter_v2ray_client.v2ray.core
                    .Tun2socksArgs.normaliseUdpMode(
                            doftAndroid == null ? null : doftAndroid.optString("udp_mode", ""));
            if (config_json.has(dev.amirzr.flutter_v2ray_client.v2ray.core
                    .Tun2socksArgs.CONFIG_KEY)) {
                config_json.remove(dev.amirzr.flutter_v2ray_client.v2ray.core
                        .Tun2socksArgs.CONFIG_KEY);
                config = config_json.toString();
            }
            try {
                JSONArray inbounds = config_json.getJSONArray("inbounds");
                for (int i = 0; i < inbounds.length(); i++) {
                    try {
                        if (inbounds.getJSONObject(i).getString("protocol").equals("socks")) {
                            v2rayConfig.LOCAL_SOCKS5_PORT = inbounds.getJSONObject(i).getInt("port");
                        }
                    } catch (Exception e) {
                        //ignore
                    }
                    try {
                        if (inbounds.getJSONObject(i).getString("protocol").equals("http")) {
                            v2rayConfig.LOCAL_HTTP_PORT = inbounds.getJSONObject(i).getInt("port");
                        }
                    } catch (Exception e) {
                        //ignore
                    }
                }
            } catch (Exception e) {
                Log.w(V2rayCoreManager.class.getSimpleName(), "startCore warn => can`t find inbound port of socks5 or http.");
                return null;
            }
            // ⚠ THIS BLOCK DECIDED WHICH PROTOCOLS ANDROID COULD LEAD WITH, and nobody
            // meant it to. It read the endpoint from `settings.vnext[0]` (VLESS/VMess)
            // and fell back to `settings.servers[0]` (Shadowsocks/SOCKS/Trojan). A
            // HYSTERIA outbound carries neither — its endpoint is `settings.address` /
            // `settings.port` directly — so both lookups threw, the whole parse returned
            // null, and `V2rayController.StartV2ray` did a BARE `return`: no exception,
            // no callback, no log. Dart's future completed and the app reported itself
            // connected with no core running. That is the 2026-08-09 outage, and it is
            // why Hysteria2 has been documented ever since as "can never be the primary
            // outbound on Android" — a limit of this method, not of xray, which dials
            // that outbound perfectly well (measured 765 KB/s from Krasnodar as a
            // balancer member, on the same core, in the same process).
            //
            // Read every shape, in the order that disambiguates them, and treat "no
            // endpoint at all" as the hard error it is rather than as a silent null.
            JSONObject firstOut = config_json.getJSONArray("outbounds").getJSONObject(0);
            JSONObject outSettings = firstOut.optJSONObject("settings");
            String addr = "", port = "";
            if (outSettings != null) {
                JSONArray vnext = outSettings.optJSONArray("vnext");
                JSONArray servers = outSettings.optJSONArray("servers");
                if (vnext != null && vnext.length() > 0) {
                    addr = vnext.getJSONObject(0).optString("address", "");
                    port = vnext.getJSONObject(0).optString("port", "");
                } else if (servers != null && servers.length() > 0) {
                    addr = servers.getJSONObject(0).optString("address", "");
                    port = servers.getJSONObject(0).optString("port", "");
                } else {
                    // hysteria (and anything else that names its endpoint inline)
                    addr = outSettings.optString("address", "");
                    port = outSettings.optString("port", "");
                }
            }
            if (addr.isEmpty()) {
                // ⚠ LOUD. The address is handed to `Libv2ray.setProtectorServer`, and a
                // start with no endpoint is a bug in the config we were given — the one
                // thing that must never again look like a successful connect.
                Log.e(Utilities.class.getName(),
                        "parseV2rayJsonFile: outbounds[0] names no endpoint (protocol="
                                + firstOut.optString("protocol", "?")
                                + ") — refusing to start with an unknown server");
                return null;
            }
            v2rayConfig.CONNECTED_V2RAY_SERVER_ADDRESS = addr;
            v2rayConfig.CONNECTED_V2RAY_SERVER_PORT = port;
            try {
                if (config_json.has("policy")) {
                    config_json.remove("policy");
                }
                if (config_json.has("stats")) {
                    config_json.remove("stats");
                }
            } catch (Exception ignore_error) {
                //ignore
            }
            if (AppConfigs.ENABLE_TRAFFIC_AND_SPEED_STATICS) {
                try {
                    JSONObject policy = new JSONObject();
                    JSONObject levels = new JSONObject();
                    levels.put("8", new JSONObject()
                            .put("connIdle", 300)
                            .put("downlinkOnly", 1)
                            .put("handshake", 4)
                            .put("uplinkOnly", 1));
                    JSONObject system = new JSONObject()
                            .put("statsOutboundUplink", true)
                            .put("statsOutboundDownlink", true);
                    policy.put("levels", levels);
                    policy.put("system", system);
                    config_json.put("policy", policy);
                    config_json.put("stats", new JSONObject());
                    config = config_json.toString();
                    v2rayConfig.ENABLE_TRAFFIC_STATICS = true;
                } catch (Exception e) {
                    //ignore
                }
            }
        } catch (Exception e) {
            Log.e(Utilities.class.getName(), "parseV2rayJsonFile failed => ", e);
            //ignore
            return null;
        }
        v2rayConfig.V2RAY_FULL_JSON_CONFIG = config;
        return v2rayConfig;
    }


}
