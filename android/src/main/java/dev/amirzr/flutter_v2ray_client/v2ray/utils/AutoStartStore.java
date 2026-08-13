package dev.amirzr.flutter_v2ray_client.v2ray.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * The last {@link V2rayConfig} that ACTUALLY STARTED THE CORE, kept so that a start we
 * did not initiate can be answered with something instead of with suicide.
 *
 * <p>Two starts arrive without our extras and both used to destroy the service on sight:
 * the START_STICKY restart after the process is killed (Android redelivers a <b>null</b>
 * intent) and the always-on VPN start (the manifest declares
 * {@code SUPPORTS_ALWAYS_ON} and the {@code android.net.VpnService} intent-filter, so the
 * framework starts the service with a bare action intent). Neither can carry a config —
 * only this store can.
 *
 * <h3>Why this is not a serialized V2rayConfig</h3>
 * {@link V2rayConfig} implements {@link java.io.Serializable}, and writing the object
 * graph would be three lines. It is deliberately NOT done: a Java-serialized blob is
 * keyed to the class it came from. Add, remove or retype one field — or let R8 renumber
 * it — and readObject() throws {@code InvalidClassException} on the FIRST boot after an
 * app update, i.e. exactly on the boot where always-on has to work and the app is not
 * running to repair anything. This writes named JSON instead: an unknown key is ignored,
 * a missing key takes its default, and the schema number below is the escape hatch if the
 * meaning of a key ever changes.
 *
 * <h3>Fail closed</h3>
 * Every read path that cannot produce a usable config returns {@code null} AND drops the
 * stored blob. A blob that does not parse today will not parse tomorrow; keeping it would
 * buy nothing but a restart loop. No tunnel is always the safe answer here — the caller
 * stops the service.
 *
 * <h3>What is in it</h3>
 * The full core JSON, which on this fork carries the device's TUIC credential in its
 * {@code _doft_tuic} block. It therefore lives in app-private storage and nowhere else,
 * and an explicit stop {@link #clear clears} it rather than merely marking it unusable.
 *
 * <p>Written and read only from the {@code :RunSoLibV2RayDaemon} process (the services
 * live there), so a plain single-process {@link SharedPreferences} is the whole story; no
 * cross-process mode is used, and none of the deprecated multi-process ones would be
 * honoured anyway.
 */
public final class AutoStartStore {

    private static final String TAG = "V2rayAutoStart";
    private static final String PREFS = "doft_v2ray_autostart";

    /** Bump only if the MEANING of a key changes; an old blob is then dropped, not read. */
    private static final int SCHEMA = 1;

    /**
     * How many consecutive restores may fail before the config is dropped.
     *
     * <p>A config that fails to start fails the same way every time. Always-on is retried
     * by the framework no matter what we return from onStartCommand, so without a budget
     * a bad blob is an unbounded restart loop; with it, the user gets a stopped VPN and
     * the next app launch writes a fresh config.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    public static final String SLOT_VPN = "vpn";
    public static final String SLOT_PROXY = "proxy";

    private AutoStartStore() {
    }

    private static SharedPreferences prefs(final Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String keyConfig(final String slot) {
        return slot + "_config";
    }

    private static String keySchema(final String slot) {
        return slot + "_schema";
    }

    private static String keyFailures(final String slot) {
        return slot + "_failures";
    }

    private static String keySavedAt(final String slot) {
        return slot + "_saved_at";
    }

    /**
     * Record a config as restorable. Call this ONLY after
     * {@code V2rayCoreManager.startCore()} has returned true — a config that never
     * started must never be replayed by the system.
     */
    public static void save(final Context context, final String slot, final V2rayConfig config) {
        if (context == null || config == null) {
            return;
        }
        if (config.V2RAY_FULL_JSON_CONFIG == null || config.V2RAY_FULL_JSON_CONFIG.isEmpty()) {
            Log.w(TAG, "not persisting a config with no core JSON");
            return;
        }
        try {
            final JSONObject o = new JSONObject();
            o.put("CONNECTED_V2RAY_SERVER_ADDRESS", nullToEmpty(config.CONNECTED_V2RAY_SERVER_ADDRESS));
            o.put("CONNECTED_V2RAY_SERVER_PORT", nullToEmpty(config.CONNECTED_V2RAY_SERVER_PORT));
            o.put("LOCAL_SOCKS5_PORT", config.LOCAL_SOCKS5_PORT);
            o.put("LOCAL_HTTP_PORT", config.LOCAL_HTTP_PORT);
            // A null list and an empty list mean different things to the tun builder
            // (no bypass subnets => default route), so an absent key is kept absent.
            if (config.BLOCKED_APPS != null) {
                o.put("BLOCKED_APPS", toJsonArray(config.BLOCKED_APPS));
            }
            if (config.BYPASS_SUBNETS != null) {
                o.put("BYPASS_SUBNETS", toJsonArray(config.BYPASS_SUBNETS));
            }
            o.put("V2RAY_FULL_JSON_CONFIG", config.V2RAY_FULL_JSON_CONFIG);
            o.put("ENABLE_TRAFFIC_STATICS", config.ENABLE_TRAFFIC_STATICS);
            o.put("REMARK", nullToEmpty(config.REMARK));
            o.put("APPLICATION_NAME", nullToEmpty(config.APPLICATION_NAME));
            o.put("NOTIFICATION_DISCONNECT_BUTTON_NAME", nullToEmpty(config.NOTIFICATION_DISCONNECT_BUTTON_NAME));
            // ⚠ A RESOURCE ID IS NOT STABLE ACROSS AN APP UPDATE — aapt renumbers them.
            // A stale id reaches setSmallIcon(), the notification fails to build, and the
            // only startForeground() call on the path is the one inside that try block
            // (see 6205a88): miss it and the OS kills the process for missing the FGS
            // deadline. So the resource NAME is the primary record and the number is only
            // a fallback; load() re-resolves and type-checks whatever comes back.
            o.put("APPLICATION_ICON", config.APPLICATION_ICON);
            try {
                o.put("APPLICATION_ICON_NAME",
                        context.getResources().getResourceName(config.APPLICATION_ICON));
            } catch (Throwable ignored) {
                // no name to record; load() will fall back to the app's own icon
            }
            // The timestamp is kept OUT of the blob so the blob is byte-comparable
            // below; a reconnect to the same node must not rewrite ~100 KB of prefs.
            final String blob = o.toString();
            final SharedPreferences p = prefs(context);
            final SharedPreferences.Editor edit = p.edit();
            if (!blob.equals(p.getString(keyConfig(slot), null)) || p.getInt(keySchema(slot), -1) != SCHEMA) {
                edit.putInt(keySchema(slot), SCHEMA).putString(keyConfig(slot), blob);
                Log.i(TAG, "persisted last-known-good config for slot " + slot + " => " + config.REMARK);
            }
            edit.putLong(keySavedAt(slot), System.currentTimeMillis())
                    // A start that worked clears the failure budget.
                    .putInt(keyFailures(slot), 0)
                    .apply();
        } catch (Throwable t) {
            // Keep whatever was there: an older config that once worked is still a better
            // answer to always-on than nothing. Never let this take down a live connect.
            Log.w(TAG, "could not persist config for slot " + slot, t);
        }
    }

    /**
     * The stored config, or {@code null} if there is nothing usable — in which case the
     * stored blob has also been dropped. The caller must stop the service on null.
     */
    public static V2rayConfig load(final Context context, final String slot) {
        if (context == null) {
            return null;
        }
        final String raw;
        final int schema;
        try {
            final SharedPreferences p = prefs(context);
            raw = p.getString(keyConfig(slot), null);
            schema = p.getInt(keySchema(slot), -1);
        } catch (Throwable t) {
            Log.w(TAG, "could not read the store for slot " + slot, t);
            return null;
        }
        if (raw == null) {
            // Normal and expected: the user stopped the tunnel, or never started one.
            return null;
        }
        if (schema != SCHEMA) {
            Log.w(TAG, "dropping slot " + slot + ": schema " + schema + " != " + SCHEMA);
            clear(context, slot);
            return null;
        }
        try {
            final JSONObject o = new JSONObject(raw);
            final String fullJson = o.optString("V2RAY_FULL_JSON_CONFIG", "");
            if (fullJson.isEmpty()) {
                Log.w(TAG, "dropping slot " + slot + ": stored config has no core JSON");
                clear(context, slot);
                return null;
            }
            final V2rayConfig config = new V2rayConfig();
            config.CONNECTED_V2RAY_SERVER_ADDRESS = o.optString("CONNECTED_V2RAY_SERVER_ADDRESS", "");
            config.CONNECTED_V2RAY_SERVER_PORT = o.optString("CONNECTED_V2RAY_SERVER_PORT", "");
            config.LOCAL_SOCKS5_PORT = o.optInt("LOCAL_SOCKS5_PORT", 10808);
            config.LOCAL_HTTP_PORT = o.optInt("LOCAL_HTTP_PORT", 10809);
            config.BLOCKED_APPS = toStringList(o, "BLOCKED_APPS");
            config.BYPASS_SUBNETS = toStringList(o, "BYPASS_SUBNETS");
            config.V2RAY_FULL_JSON_CONFIG = fullJson;
            config.ENABLE_TRAFFIC_STATICS = o.optBoolean("ENABLE_TRAFFIC_STATICS", false);
            config.REMARK = o.optString("REMARK", "");
            config.APPLICATION_NAME = o.optString("APPLICATION_NAME", "");
            config.NOTIFICATION_DISCONNECT_BUTTON_NAME =
                    o.optString("NOTIFICATION_DISCONNECT_BUTTON_NAME", "");
            config.APPLICATION_ICON = resolveIcon(context,
                    o.optString("APPLICATION_ICON_NAME", ""),
                    o.optInt("APPLICATION_ICON", 0));
            final long savedAt = prefs(context).getLong(keySavedAt(slot), 0L);
            Log.i(TAG, "restored config for slot " + slot + " => " + config.REMARK
                    + " (age " + (savedAt > 0 ? (System.currentTimeMillis() - savedAt) / 1000 : -1) + "s)");
            return config;
        } catch (Throwable t) {
            // Unreadable today, unreadable forever. Drop it rather than retry it.
            Log.w(TAG, "dropping slot " + slot + ": stored config did not parse", t);
            clear(context, slot);
            return null;
        }
    }

    /** Forget the tunnel. Called on an explicit stop and when a restore keeps failing. */
    public static void clear(final Context context, final String slot) {
        if (context == null) {
            return;
        }
        try {
            prefs(context).edit()
                    .remove(keyConfig(slot))
                    .remove(keySchema(slot))
                    .remove(keySavedAt(slot))
                    .remove(keyFailures(slot))
                    .apply();
            Log.i(TAG, "cleared persisted config for slot " + slot);
        } catch (Throwable t) {
            Log.w(TAG, "could not clear slot " + slot, t);
        }
    }

    /**
     * Charge one attempt against the failure budget.
     *
     * @return true if the restore may proceed; false once the budget is spent, in which
     *         case the stored config has been dropped and the caller must stop.
     */
    public static boolean beginRestoreAttempt(final Context context, final String slot) {
        if (context == null) {
            return false;
        }
        try {
            final SharedPreferences p = prefs(context);
            final int failures = p.getInt(keyFailures(slot), 0);
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                Log.w(TAG, "slot " + slot + ": " + failures
                        + " consecutive failed restores, dropping the config");
                clear(context, slot);
                return false;
            }
            // Written BEFORE the attempt: if the attempt takes the process down, the
            // count still went up.
            p.edit().putInt(keyFailures(slot), failures + 1).apply();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not read the restore budget for slot " + slot, t);
            return false;
        }
    }

    /** The core came up from a restored config; forget the failures that preceded it. */
    public static void noteRestoreSucceeded(final Context context, final String slot) {
        if (context == null) {
            return;
        }
        try {
            prefs(context).edit().putInt(keyFailures(slot), 0).apply();
        } catch (Throwable t) {
            Log.w(TAG, "could not reset the restore budget for slot " + slot, t);
        }
    }

    /**
     * A drawable id that is valid IN THIS INSTALL, preferring the recorded resource name
     * over the recorded number, and falling back to the app's own icon. Never returns an
     * id that does not resolve to a drawable/mipmap.
     */
    private static int resolveIcon(final Context context, final String name, final int storedId) {
        final Resources res = context.getResources();
        int icon = 0;
        if (name != null && !name.isEmpty()) {
            try {
                icon = res.getIdentifier(name, null, null);
            } catch (Throwable ignored) {
            }
        }
        if (icon == 0) {
            icon = storedId;
        }
        if (icon != 0 && !isDrawable(res, icon)) {
            // The number survived the update but now points at something else entirely.
            icon = 0;
        }
        if (icon == 0) {
            try {
                icon = context.getApplicationInfo().icon;
            } catch (Throwable ignored) {
            }
        }
        if (icon == 0 || !isDrawable(res, icon)) {
            icon = android.R.drawable.sym_def_app_icon;
        }
        return icon;
    }

    private static boolean isDrawable(final Resources res, final int id) {
        try {
            final String type = res.getResourceTypeName(id);
            return "drawable".equals(type) || "mipmap".equals(type);
        } catch (Throwable t) {
            return false;
        }
    }

    private static JSONArray toJsonArray(final ArrayList<String> values) {
        final JSONArray a = new JSONArray();
        for (int i = 0; i < values.size(); i++) {
            final String v = values.get(i);
            if (v != null) {
                a.put(v);
            }
        }
        return a;
    }

    private static ArrayList<String> toStringList(final JSONObject o, final String key) {
        if (!o.has(key) || o.isNull(key)) {
            return null;
        }
        final JSONArray a = o.optJSONArray(key);
        if (a == null) {
            return null;
        }
        final ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            final String v = a.optString(i, null);
            if (v != null && !v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private static String nullToEmpty(final String s) {
        return s == null ? "" : s;
    }
}
