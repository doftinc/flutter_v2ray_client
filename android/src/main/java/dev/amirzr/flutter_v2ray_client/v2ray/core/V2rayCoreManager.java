package dev.amirzr.flutter_v2ray_client.v2ray.core;

import static dev.amirzr.flutter_v2ray_client.v2ray.utils.Utilities.getUserAssetsPath;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.CountDownTimer;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import dev.amirzr.flutter_v2ray_client.v2ray.interfaces.V2rayServicesListener;
import dev.amirzr.flutter_v2ray_client.v2ray.services.V2rayProxyOnlyService;
import dev.amirzr.flutter_v2ray_client.v2ray.services.V2rayVPNService;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.AppConfigs;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.Utilities;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.V2rayConfig;

import org.json.JSONObject;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;
import libv2ray.V2RayProtector;

public final class V2rayCoreManager {
    private static final int NOTIFICATION_ID = 1;
    private volatile static V2rayCoreManager INSTANCE;
    public V2rayServicesListener v2rayServicesListener = null;
    private CoreController coreController;
    public AppConfigs.V2RAY_STATES V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED;
    private boolean isLibV2rayCoreInitialized = false;
    private CountDownTimer countDownTimer;
    private int seconds, minutes, hours;
    private long totalDownload, totalUpload, uploadSpeed, downloadSpeed;
    /**
     * Outbound tags the traffic counters are summed over.
     *
     * <p>xray keys its stats counters by OUTBOUND TAG ({@code outbound&gt;&gt;&gt;<tag>&gt;&gt;&gt;traffic&gt;&gt;&gt;…})
     * and {@code queryStats} matches that name exactly — there is no prefix or wildcard
     * form. Reading only {@code "proxy"} was therefore correct exactly as long as the
     * config had a single proxy outbound. It does not any more: the Doft client emits a
     * {@code burstObservatory} + routing balancer over {@code proxy}, {@code proxy-r1},
     * {@code proxy-cdn}, {@code proxy-ss}, {@code proxy-hy2} …, so every byte the
     * balancer routes through a member other than {@code proxy} was invisible — and the
     * app meters its free tier, confirms the device identity and vetoes false failovers
     * from these numbers.
     *
     * <p>Populated from the running config in {@link #startCore}; the defaults are the
     * historical behaviour, so a config we cannot parse degrades to what it did before.
     */
    private volatile String[] statsTags = new String[] { "block", "proxy" };
    private String SERVICE_DURATION = "00:00:00";

    public static V2rayCoreManager getInstance() {
        if (INSTANCE == null) {
            synchronized (V2rayCoreManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new V2rayCoreManager();
                }
            }
        }
        return INSTANCE;
    }

    private void makeDurationTimer(final Context context, final boolean enable_traffic_statics) {
        countDownTimer = new CountDownTimer(7200, 1000) {
            @RequiresApi(api = Build.VERSION_CODES.M)
            public void onTick(long millisUntilFinished) {

                seconds++;
                if (seconds == 59) {
                    minutes++;
                    seconds = 0;
                }
                if (minutes == 59) {
                    minutes = 0;
                    hours++;
                }
                if (hours == 23) {
                    hours = 0;
                }
                if (enable_traffic_statics) {
                    // Sum over EVERY outbound tag in the running config, not just "proxy".
                    // queryStats also RESETS the counter it reads, so a tag left unread is
                    // not merely unreported — those bytes are gone. A tag that does not
                    // exist returns 0, so an over-broad list is free.
                    long dn = 0, up = 0;
                    final String[] tags = statsTags;
                    if (coreController != null) {
                        for (int t = 0; t < tags.length; t++) {
                            dn += coreController.queryStats(tags[t], "downlink");
                            up += coreController.queryStats(tags[t], "uplink");
                        }
                    }
                    downloadSpeed = dn;
                    uploadSpeed = up;
                    totalDownload = totalDownload + downloadSpeed;
                    totalUpload = totalUpload + uploadSpeed;
                }
                SERVICE_DURATION = Utilities.convertIntToTwoDigit(hours) + ":" + Utilities.convertIntToTwoDigit(minutes)
                        + ":" + Utilities.convertIntToTwoDigit(seconds);
                String packageName = context.getPackageName();
                Intent connection_info_intent = new Intent(packageName + ".V2RAY_CONNECTION_INFO");
                connection_info_intent.setPackage(packageName);
                connection_info_intent.putExtra("STATE", V2rayCoreManager.getInstance().V2RAY_STATE);
                connection_info_intent.putExtra("DURATION", SERVICE_DURATION);
                connection_info_intent.putExtra("UPLOAD_SPEED", uploadSpeed);
                connection_info_intent.putExtra("DOWNLOAD_SPEED", downloadSpeed);
                connection_info_intent.putExtra("UPLOAD_TRAFFIC", totalUpload);
                connection_info_intent.putExtra("DOWNLOAD_TRAFFIC", totalDownload);
                try {
                    context.sendBroadcast(connection_info_intent);
                } catch (Exception e) {
                    Log.w("V2rayCoreManager", "Failed to send connection info broadcast", e);
                }
            }

            public void onFinish() {
                countDownTimer.cancel();
                if (V2rayCoreManager.getInstance().isV2rayCoreRunning())
                    makeDurationTimer(context, enable_traffic_statics);
            }
        }.start();
    }

    public void setUpListener(Service targetService) {
        try {
            v2rayServicesListener = (V2rayServicesListener) targetService;
            Libv2ray.initCoreEnv(getUserAssetsPath(targetService.getApplicationContext()), "");

            // Register Android VPN socket protector with libv2ray (Go)
            Libv2ray.useProtector(new V2RayProtector() {
                @Override
                public boolean protect(long fd) {
                    if (v2rayServicesListener != null) {
                        return v2rayServicesListener.onProtect((int) fd);
                    }
                    return true;
                }
            });
            // Initialize controller with callback handler
            coreController = Libv2ray.newCoreController(new CoreCallbackHandler() {
                @Override
                public long onEmitStatus(long p0, String p1) {
                    // Currently unused; log for debugging
                    Log.d(V2rayCoreManager.class.getSimpleName(), "onEmitStatus => " + p0 + ": " + p1);
                    return 0;
                }

                @Override
                public long shutdown() {
                    if (v2rayServicesListener == null) {
                        Log.e(V2rayCoreManager.class.getSimpleName(), "shutdown failed => can`t find initial service.");
                        return -1;
                    }
                    try {
                        v2rayServicesListener.stopService();
                        v2rayServicesListener = null;
                        return 0;
                    } catch (Exception e) {
                        Log.e(V2rayCoreManager.class.getSimpleName(), "shutdown failed =>", e);
                        return -1;
                    }
                }

                @Override
                public long startup() {
                    if (v2rayServicesListener != null) {
                        try {
                            v2rayServicesListener.startService();
                        } catch (Exception e) {
                            Log.e(V2rayCoreManager.class.getSimpleName(), "startup failed => ", e);
                            return -1;
                        }
                    }
                    return 0;
                }
            });
            isLibV2rayCoreInitialized = true;
            SERVICE_DURATION = "00:00:00";
            seconds = 0;
            minutes = 0;
            hours = 0;
            uploadSpeed = 0;
            downloadSpeed = 0;
            totalDownload = 0;
            totalUpload = 0;
            Log.e(V2rayCoreManager.class.getSimpleName(), "setUpListener => new initialize from "
                    + v2rayServicesListener.getService().getClass().getSimpleName());
        } catch (Exception e) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "setUpListener failed => ", e);
            isLibV2rayCoreInitialized = false;
        }
    }

    public boolean startCore(final V2rayConfig v2rayConfig) {
        statsTags = readOutboundTags(v2rayConfig.V2RAY_FULL_JSON_CONFIG);
        makeDurationTimer(v2rayServicesListener.getService().getApplicationContext(),
                v2rayConfig.ENABLE_TRAFFIC_STATICS);
        V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTING;
        if (!isLibV2rayCoreInitialized) {
            Log.e(V2rayCoreManager.class.getSimpleName(),
                    "startCore failed => LibV2rayCore should be initialize before start.");
            return false;
        }
        if (isV2rayCoreRunning()) {
            stopCore();
        }
        try {
            if (coreController == null) {
                Log.e(V2rayCoreManager.class.getSimpleName(), "startCore failed => coreController is null.");
                return false;
            }
            // Configure protector target server and IP family preference before starting
            // core
            try {
                String server = v2rayConfig.CONNECTED_V2RAY_SERVER_ADDRESS + ":"
                        + v2rayConfig.CONNECTED_V2RAY_SERVER_PORT;
                Libv2ray.setProtectorServer(server, false);
            } catch (Exception ignored) {
            }
            // ⚠ TUIC STARTS BEFORE THE CORE, AND THE CORE NEVER SEES ITS BLOCK.
            // xray-core has no TUIC at all, so the transport is a client compiled into
            // our fork of libv2ray that speaks SOCKS5 on 127.0.0.1. The Dart side emits
            // the outbound with a PLACEHOLDER port (only this side knows what the
            // listener bound) plus a `_doft_tuic` block carrying the credentials. We
            // start the listener, write the real port into every socks outbound tagged
            // proxy-tuic*, and delete the block — it is not xray config, and it holds
            // the device's credential.
            String coreJson = applyTuic(v2rayConfig.V2RAY_FULL_JSON_CONFIG);
            coreController.startLoop(coreJson, 0);
            V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTED;
            if (isV2rayCoreRunning()) {
                showNotification(v2rayConfig);
            }
        } catch (Exception e) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "startCore failed =>", e);
            return false;
        }
        return true;
    }

    /**
     * The PROXY outbound tags in the config — {@code proxy} and anything the balancer
     * added beside it ({@code proxy-r1}, {@code proxy-cdn}, {@code proxy-ss},
     * {@code proxy-hy2} …) — plus {@code block}, exactly as before.
     *
     * <p>⚠ {@code direct} and {@code dns-out} are DELIBERATELY EXCLUDED. They carry the
     * split-tunnel and bypassed-domain traffic, which leaves the machine outside the
     * tunnel; the app meters its free data allowance from these counters, so counting
     * bypassed bytes would charge users for traffic the VPN never carried. The previous
     * behaviour was {@code block + proxy}, and this is the same set plus the members
     * that only exist because one outbound became several.
     *
     * <p>Falls back to {@code {"block", "proxy"}} on any parse failure, i.e. to exactly
     * what this code did before.
     */
    private static String[] readOutboundTags(final String fullJsonConfig) {
        try {
            final org.json.JSONArray outbounds =
                    new JSONObject(fullJsonConfig).getJSONArray("outbounds");
            final java.util.ArrayList<String> tags = new java.util.ArrayList<>();
            tags.add("block");
            for (int i = 0; i < outbounds.length(); i++) {
                final String tag = outbounds.getJSONObject(i).optString("tag", "");
                if (tag.startsWith("proxy") && !tags.contains(tag)) {
                    tags.add(tag);
                }
            }
            if (tags.size() > 1) {
                return tags.toArray(new String[0]);
            }
        } catch (Exception e) {
            Log.w(V2rayCoreManager.class.getSimpleName(),
                    "readOutboundTags failed, falling back to block+proxy", e);
        }
        return new String[] { "block", "proxy" };
    }

    public void stopCore() {
        try {
            // Safely cancel notification - handle cases where service might be null
            if (v2rayServicesListener != null && v2rayServicesListener.getService() != null) {
                NotificationManager notificationManager = (NotificationManager) v2rayServicesListener.getService()
                        .getSystemService(Context.NOTIFICATION_SERVICE);
                if (notificationManager != null) {
                    notificationManager.cancel(NOTIFICATION_ID);
                }
            }
        } catch (Exception e) {
            Log.w("V2rayCoreManager", "Failed to cancel notification", e);
        }

        try {
            if (isV2rayCoreRunning()) {
                if (coreController != null) {
                    coreController.stopLoop();
                }
                // After the core, not before: the core may still be draining streams
                // through the loopback listener while it shuts down.
                try {
                    Libv2ray.stopTuic();
                } catch (Throwable ignored) {
                }
                v2rayServicesListener.stopService();
                Log.e(V2rayCoreManager.class.getSimpleName(), "stopCore success => v2ray core stopped.");
            } else {
                Log.e(V2rayCoreManager.class.getSimpleName(), "stopCore failed => v2ray core not running.");
            }
            sendDisconnectedBroadCast();
        } catch (Exception e) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "stopCore failed =>", e);
        }
    }

    private void sendDisconnectedBroadCast() {
        V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED;
        SERVICE_DURATION = "00:00:00";
        seconds = 0;
        minutes = 0;
        hours = 0;
        uploadSpeed = 0;
        downloadSpeed = 0;
        if (v2rayServicesListener != null) {
            Context context = v2rayServicesListener.getService().getApplicationContext();
            String packageName = context.getPackageName();
            Intent connection_info_intent = new Intent(packageName + ".V2RAY_CONNECTION_INFO");
            connection_info_intent.setPackage(packageName);
            connection_info_intent.putExtra("STATE", V2rayCoreManager.getInstance().V2RAY_STATE);
            connection_info_intent.putExtra("DURATION", SERVICE_DURATION);
            connection_info_intent.putExtra("UPLOAD_SPEED", uploadSpeed);
            connection_info_intent.putExtra("DOWNLOAD_SPEED", downloadSpeed);
            connection_info_intent.putExtra("UPLOAD_TRAFFIC", totalUpload);
            connection_info_intent.putExtra("DOWNLOAD_TRAFFIC", totalDownload);
            try {
                context.sendBroadcast(connection_info_intent);
            } catch (Exception e) {
                Log.w("V2rayCoreManager", "Failed to send disconnected broadcast", e);
            }
        }
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }

    private String createNotificationChannelID(String appName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = "A_FLUTTER_V2RAY_SERVICE_CH_ID"; // default and constant ID
            try {
                if (v2rayServicesListener == null || v2rayServicesListener.getService() == null) {
                    return channelId;
                }

                NotificationManager notificationManager = (NotificationManager) v2rayServicesListener.getService()
                        .getSystemService(Context.NOTIFICATION_SERVICE);

                String channelName = appName + " Background Service";
                NotificationChannel channel = new NotificationChannel(channelId, channelName,
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription(channelName);
                channel.setLightColor(Color.DKGRAY);
                channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

                if (notificationManager != null) {
                    notificationManager.createNotificationChannel(channel);
                }
            } catch (Exception e) {
                Log.w("V2rayCoreManager", "Failed to create notification channel", e);
            }

            return channelId;
        }
        return "";
    }

    private void showNotification(final V2rayConfig v2rayConfig) {
        Service context = v2rayServicesListener.getService();
        if (context == null) {
            return;
        }

        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launchIntent != null) {
            launchIntent.setAction("FROM_DISCONNECT_BTN");
            launchIntent.setFlags(
                    Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        final int flags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        } else {
            flags = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        PendingIntent notificationContentPendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent, flags);

        String notificationChannelID = createNotificationChannelID(v2rayConfig.APPLICATION_NAME);

        Intent stopIntent;
        if (AppConfigs.V2RAY_CONNECTION_MODE == AppConfigs.V2RAY_CONNECTION_MODES.PROXY_ONLY) {
            stopIntent = new Intent(context, V2rayProxyOnlyService.class);
        } else if (AppConfigs.V2RAY_CONNECTION_MODE == AppConfigs.V2RAY_CONNECTION_MODES.VPN_TUN) {
            stopIntent = new Intent(context, V2rayVPNService.class);
        } else {
            return;
        }
        stopIntent.putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE);

        PendingIntent pendingIntent = PendingIntent.getService(
                context, 0, stopIntent, flags);

        try {
            // Build the notification
            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(context,
                    notificationChannelID)
                    .setSmallIcon(v2rayConfig.APPLICATION_ICON)
                    .setContentTitle(v2rayConfig.REMARK)
                    .addAction(0, v2rayConfig.NOTIFICATION_DISCONNECT_BUTTON_NAME, notificationContentPendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(notificationContentPendingIntent)
                    .setSilent(true)
                    .setOngoing(true);

            context.startForeground(NOTIFICATION_ID, notificationBuilder.build());
        } catch (Exception e) {
            Log.w("V2rayCoreManager", "Failed to show notification, continuing without notification", e);
            // VPN/Proxy continues to work even if notification fails
        }
    }

    public boolean isV2rayCoreRunning() {
        if (coreController != null) {
            return coreController.getIsRunning();
        }
        return false;
    }

    public Long getConnectedV2rayServerDelay() {
        try {
            if (coreController == null)
                return -1L;
            return coreController.measureDelay(AppConfigs.DELAY_URL);
        } catch (Exception e) {
            return -1L;
        }
    }

    public Long getV2rayServerDelay(final String config, final String url) {
        try {
            try {
                JSONObject config_json = new JSONObject(config);
                JSONObject new_routing_json = config_json.getJSONObject("routing");
                new_routing_json.remove("rules");
                config_json.remove("routing");
                config_json.put("routing", new_routing_json);
                return Libv2ray.measureOutboundDelay(config_json.toString(), url);
            } catch (Exception json_error) {
                Log.e("getV2rayServerDelay", json_error.toString());
                return Libv2ray.measureOutboundDelay(config, url);
            }
        } catch (Exception e) {
            Log.e("getV2rayServerDelayCore", e.toString());
            return -1L;
        }
    }


    /**
     * Start the in-process TUIC listener when the config asks for one, point the socks
     * outbounds at it, and strip the request block.
     *
     * ⚠ FAILING OPEN IS DELIBERATE. If the listener cannot start, the `proxy-tuic`
     * members are REMOVED rather than left pointing at a dead port: a member that fails
     * every probe costs the balancer a health check every interval over a thin mobile
     * line, and on the engine where that budget is already scaled by member count. The
     * tunnel comes up on the other transports exactly as it would have without TUIC.
     *
     * ⚠ AND IT MUST NEVER THROW. This runs between "user tapped connect" and
     * startLoop(); an exception here would take down a tunnel that has nothing to do
     * with TUIC, so anything unexpected returns the ORIGINAL config minus the block.
     */
    private String applyTuic(String configJson) {
        if (configJson == null || !configJson.contains("_doft_tuic")) {
            return configJson;
        }
        org.json.JSONObject root;
        org.json.JSONObject req;
        try {
            root = new org.json.JSONObject(configJson);
            req = root.optJSONObject("_doft_tuic");
            // ⚠ STRIPPED BEFORE ANYTHING ELSE CAN FAIL. It is not xray config — an
            // unknown top-level key is at best ignored and at worst a parse error — and
            // it carries the device's credential. Every return path below is already
            // clean because the removal happened here, not in a finally block.
            root.remove("_doft_tuic");
        } catch (Throwable e) {
            // The config did not parse at all, so there is nothing safe to hand back but
            // what we were given; the core will reject it and say why.
            Log.e(V2rayCoreManager.class.getSimpleName(), "tuic: config did not parse", e);
            return configJson;
        }

        long port = -1;
        try {
            if (req != null) {
                port = Libv2ray.startTuic(req.toString());
            }
        } catch (Throwable e) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "tuic: listener failed to start", e);
        }

        try {
            org.json.JSONArray outs = root.optJSONArray("outbounds");
            if (outs == null) {
                return root.toString();
            }
            org.json.JSONArray kept = new org.json.JSONArray();
            for (int i = 0; i < outs.length(); i++) {
                org.json.JSONObject o = outs.optJSONObject(i);
                String tag = (o == null) ? "" : o.optString("tag", "");
                if (!tag.startsWith("proxy-tuic")) {
                    kept.put(outs.get(i));
                    continue;
                }
                org.json.JSONObject settings = o.optJSONObject("settings");
                org.json.JSONArray servers =
                        (settings == null) ? null : settings.optJSONArray("servers");
                if (port <= 0 || servers == null) {
                    // ⚠ FAIL OPEN: DROP THE MEMBER, do not leave it pointing at a dead
                    // port. A member that fails every probe still costs the balancer a
                    // health check every interval — on the engine whose probe budget is
                    // already scaled by member count, over a thin mobile line. The tunnel
                    // comes up on the other transports exactly as it would have without
                    // TUIC configured at all.
                    Log.w(V2rayCoreManager.class.getSimpleName(),
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
                Log.i(V2rayCoreManager.class.getSimpleName(),
                        "tuic: listening on 127.0.0.1:" + port);
            }
        } catch (Throwable e) {
            // Anything unexpected while rewriting: hand back the stripped config as it
            // stood. Worst case a proxy-tuic member survives with port 0 and fails its
            // probes — bounded, and never a failed connect for an unrelated transport.
            Log.e(V2rayCoreManager.class.getSimpleName(), "tuic: rewrite failed", e);
        }
        return root.toString();
    }
}
