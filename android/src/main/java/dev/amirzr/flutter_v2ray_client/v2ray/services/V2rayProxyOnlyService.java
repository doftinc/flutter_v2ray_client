package dev.amirzr.flutter_v2ray_client.v2ray.services;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import dev.amirzr.flutter_v2ray_client.v2ray.core.V2rayCoreManager;
import dev.amirzr.flutter_v2ray_client.v2ray.interfaces.V2rayServicesListener;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.AppConfigs;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.AutoStartStore;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.V2rayConfig;

public class V2rayProxyOnlyService extends Service implements V2rayServicesListener {

    private static final String TAG = "V2rayProxyOnlyService";

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
                Log.w(TAG, "COMMAND extra could not be read", t);
            }
        }

        if (startCommand == null) {
            // Same defect as V2rayVPNService: Android redelivers a NULL intent when it
            // restarts a service killed while START_STICKY, and this branch used to
            // destroy the service on sight - so the START_STICKY below never did
            // anything. (Always-on does not apply here: this service is not a VpnService
            // and has no intent-filter.)
            return restoreLastKnownGood(intent == null
                    ? "sticky restart (null intent)"
                    : "start with no COMMAND extra");
        }

        if (startCommand.equals(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE)) {
            V2rayConfig v2rayConfig = (V2rayConfig) intent.getSerializableExtra("V2RAY_CONFIG");
            if (v2rayConfig == null) {
                return stopCleanly("V2RAY_CONFIG is null, cannot start service");
            }
            if (V2rayCoreManager.getInstance().isV2rayCoreRunning()) {
                V2rayCoreManager.getInstance().stopCore();
            }
            if (V2rayCoreManager.getInstance().startCore(v2rayConfig)) {
                Log.i(TAG, "onStartCommand success => v2ray core started.");
                // Only after the core is actually up.
                AutoStartStore.save(this, AutoStartStore.SLOT_PROXY, v2rayConfig);
            } else {
                return stopCleanly("failed to start v2ray core");
            }
        } else if (startCommand.equals(AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE)) {
            V2rayCoreManager.getInstance().stopCore();
            AppConfigs.V2RAY_CONFIG = null;
            // The user turned it off; nothing may resurrect it.
            AutoStartStore.clear(this, AutoStartStore.SLOT_PROXY);
        } else if (startCommand.equals(AppConfigs.V2RAY_SERVICE_COMMANDS.MEASURE_DELAY)) {
            new Thread(() -> {
                try {
                    String packageName = getPackageName();
                    Intent sendB = new Intent(packageName + ".CONNECTED_V2RAY_SERVER_DELAY");
                    sendB.setPackage(packageName);
                    sendB.putExtra("DELAY", String.valueOf(V2rayCoreManager.getInstance().getConnectedV2rayServerDelay()));
                    sendBroadcast(sendB);
                } catch (Exception e) {
                    Log.w("V2rayProxyOnlyService", "Failed to send delay broadcast", e);
                }
            }, "MEASURE_CONNECTED_V2RAY_SERVER_DELAY").start();
        } else {
            return stopCleanly("unknown command received");
        }
        return START_STICKY;
    }

    /** @see dev.amirzr.flutter_v2ray_client.v2ray.services.V2rayVPNService */
    private int restoreLastKnownGood(final String reason) {
        Log.i(TAG, "system-initiated start => " + reason);

        if (V2rayCoreManager.getInstance().isV2rayCoreRunning()) {
            Log.i(TAG, "core is already running, nothing to restore");
            return START_STICKY;
        }

        final V2rayConfig restored = AutoStartStore.load(this, AutoStartStore.SLOT_PROXY);
        if (restored == null) {
            return stopCleanly("no usable persisted config");
        }
        if (!AutoStartStore.beginRestoreAttempt(this, AutoStartStore.SLOT_PROXY)) {
            return stopCleanly("restore budget spent, persisted config dropped");
        }

        // This process starts fresh on a sticky restart, so the static is back at its
        // VPN_TUN default; showNotification() reads it to aim the notification's stop
        // button, and aiming it at V2rayVPNService here would leave the user a button
        // that stops nothing.
        AppConfigs.V2RAY_CONNECTION_MODE = AppConfigs.V2RAY_CONNECTION_MODES.PROXY_ONLY;

        if (!V2rayCoreManager.getInstance().startCore(restored)) {
            return stopCleanly("restored config did not start the core");
        }
        AutoStartStore.noteRestoreSucceeded(this, AutoStartStore.SLOT_PROXY);
        Log.i(TAG, "proxy restored from persisted config => " + restored.REMARK);
        return START_STICKY;
    }

    /**
     * Stop for real. onDestroy() here only calls super, so the old
     * "this.onDestroy(); return START_NOT_STICKY" left the service running forever with
     * no core in it.
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

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public boolean onProtect(int socket) {
        return true;
    }

    @Override
    public Service getService() {
        return this;
    }

    @Override
    public void startService() {
        // ignore
    }

    @Override
    public void stopService() {
        try {
            stopSelf();
        } catch (Exception e) {
            // ignore
        }
    }
}
