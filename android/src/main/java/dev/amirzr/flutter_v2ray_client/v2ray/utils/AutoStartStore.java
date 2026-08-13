package dev.amirzr.flutter_v2ray_client.v2ray.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * The last {@link V2rayConfig} that started the core, kept so that a start we did not
 * initiate can be answered with something instead of with suicide.
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
 * <h3>⚠ THIS IS A SECOND, JAVA-SIDE CONNECT INTENT, AND IT IS BOUNDED FOR THAT REASON</h3>
 * The Dart layer keeps its own "should be connected" intent and clears it on events the
 * Java side cannot see — a data-cap cut being the one that matters, because restoring a
 * capped session after an app kill is a free-tier bypass with extra steps. A blob here
 * outlives the app process by design, so it must not be able to outlive the grant that
 * justified it. Two hard bounds, both enforced in {@link #load}:
 *
 * <ul>
 *   <li><b>An expiry the blob carries.</b> The blob is restorable only for
 *       {@code TTL_MS} after the app-initiated connect that wrote it. The clock is NOT
 *       refreshed by a restore, so the window is absolute: however many times the system
 *       resurrects the session, it stops being resurrectable {@code TTL_MS} after the last
 *       time a human asked for it.</li>
 *   <li><b>A bound on unattended resurrections.</b> {@code MAX_UNATTENDED_RESTORES}
 *       system-initiated restores may run before the config is dropped. This counter is
 *       cleared ONLY by {@link #save} — i.e. by an app-initiated connect — and
 *       deliberately NOT by {@link #noteRestoreSucceeded}, so a healthy restore chain
 *       cannot refill its own budget and run forever with no app process to meter it.</li>
 * </ul>
 *
 * <p>Both numbers default conservatively here and can be overridden per connect by the
 * Dart layer, which is the side that knows the entitlement: put a {@code _doft_autostart}
 * object in the core JSON —
 * {@code {"_doft_autostart":{"ttl_ms":3600000,"max_unattended_restores":2}}} — and
 * <b>{@code "ttl_ms":0} means "never restorable"</b>, which is the correct value for a
 * session that is metered against a cap. A config carrying it is not persisted at all and
 * any previously stored one is dropped, so it works as a kill switch even from a cap cut
 * that cannot reach the service.
 *
 * <h3>What is in it</h3>
 * The full core JSON, which on this fork carries the device's TUIC credential in its
 * {@code _doft_tuic} block. It therefore lives in app-private storage and nowhere else,
 * an explicit stop {@link #clear clears} it rather than merely marking it unusable, and
 * the app manifest sets {@code android:allowBackup="false"} so it is not swept into cloud
 * backup. (Device-to-device transfer on Android 12+ additionally needs a
 * {@code <data-extraction-rules>} resource; see the note in AndroidManifest.xml.)
 *
 * <h3>Two preference files, on purpose</h3>
 * {@link SharedPreferences#edit()}.apply() rewrites the WHOLE backing XML, so staging a
 * timestamp next to the config blob would rewrite the blob — every reconnect, tens of
 * kilobytes. The blob lives in {@link #PREFS_CONFIG}, which is written only when the
 * config actually changes; the counters and timestamps that change on every start live in
 * {@link #PREFS_STATE}, which is a few dozen bytes.
 *
 * <p>Written and read only from the {@code :RunSoLibV2RayDaemon} process (the services
 * live there), so plain single-process {@link SharedPreferences} is the whole story; no
 * cross-process mode is used, and none of the deprecated multi-process ones would be
 * honoured anyway.
 */
public final class AutoStartStore {

    private static final String TAG = "V2rayAutoStart";

    /** Rarely written: the config blob and its schema. */
    private static final String PREFS_CONFIG = "doft_v2ray_autostart";
    /** Written on every start: timestamps and counters, and nothing large. */
    private static final String PREFS_STATE = "doft_v2ray_autostart_state";

    /** Bump only if the MEANING of a key changes; an old blob is then dropped, not read. */
    private static final int SCHEMA = 2;

    /**
     * How many consecutive restores may fail before the config is dropped.
     *
     * <p>A config that fails to start fails the same way every time. Always-on is retried
     * by the framework no matter what we return from onStartCommand, so without a budget
     * a bad blob is an unbounded restart loop; with it, the user gets a stopped VPN and
     * the next app launch writes a fresh config.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /**
     * How many system-initiated restores may run between two app-initiated connects,
     * successful or not. Bounds a session that the system keeps resurrecting with no app
     * process behind it — which is a session nothing is metering.
     */
    private static final int DEFAULT_MAX_UNATTENDED_RESTORES = 8;

    /** How long after the app-initiated connect the blob stays restorable. */
    private static final long DEFAULT_TTL_MS = 7L * 24L * 60L * 60L * 1000L;

    /**
     * A wall clock that reads EARLIER than the moment we saved is a clock that moved, not
     * an age. Tolerate a day of it (a boot before the network fixes the clock is normal
     * and always-on must still work); beyond that the blob cannot be aged at all and is
     * dropped rather than trusted.
     */
    private static final long MAX_CLOCK_SKEW_MS = 24L * 60L * 60L * 1000L;

    /** Optional per-connect policy block inside the core JSON. */
    private static final String POLICY_OBJECT = "_doft_autostart";
    private static final String POLICY_TTL = "ttl_ms";
    private static final String POLICY_MAX_RESTORES = "max_unattended_restores";

    public static final String SLOT_VPN = "vpn";
    public static final String SLOT_PROXY = "proxy";

    private AutoStartStore() {
    }

    private static SharedPreferences configPrefs(final Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_CONFIG, Context.MODE_PRIVATE);
    }

    private static SharedPreferences statePrefs(final Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_STATE, Context.MODE_PRIVATE);
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

    private static String keyRestores(final String slot) {
        return slot + "_restores";
    }

    private static String keySavedAt(final String slot) {
        return slot + "_saved_at";
    }

    /**
     * Record a config as restorable. Call this ONLY after
     * {@code V2rayCoreManager.startCore()} has returned true — a config that never
     * started must never be replayed by the system — and only from an APP-INITIATED
     * start: this is the call that says "a human asked for this", and it is the only one
     * that refills the unattended-restore budget.
     */
    public static void save(final Context context, final String slot, final V2rayConfig config) {
        if (context == null || config == null) {
            return;
        }
        if (config.V2RAY_FULL_JSON_CONFIG == null || config.V2RAY_FULL_JSON_CONFIG.isEmpty()) {
            Log.w(TAG, "not persisting a config with no core JSON");
            return;
        }
        final long ttlMs = policyTtlMs(config.V2RAY_FULL_JSON_CONFIG);
        if (ttlMs <= 0L) {
            // The caller declared this session non-restorable (a metered/capped session,
            // a trial, anything the app must be present for). Do not keep it, and do not
            // keep whatever was there before it either.
            Log.i(TAG, "slot " + slot + ": config declares ttl_ms<=0, not restorable");
            clear(context, slot);
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
            // The bounds are copied out of the core JSON and into the blob so that load()
            // — which runs when there may be no app process — never has to parse the
            // whole core config to find out how long it may trust itself.
            o.put("TTL_MS", ttlMs);
            o.put("MAX_UNATTENDED_RESTORES", policyMaxRestores(config.V2RAY_FULL_JSON_CONFIG));
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
            // The timestamp and the counters are kept OUT of the blob, and out of the
            // blob's preference FILE, so that a reconnect to the same node does not
            // rewrite it: apply() rewrites the whole XML it is staged against.
            final String blob = o.toString();
            final SharedPreferences cfg = configPrefs(context);
            if (!blob.equals(cfg.getString(keyConfig(slot), null)) || cfg.getInt(keySchema(slot), -1) != SCHEMA) {
                cfg.edit().putInt(keySchema(slot), SCHEMA).putString(keyConfig(slot), blob).apply();
                Log.i(TAG, "persisted last-known-good config for slot " + slot + " => " + config.REMARK);
            }
            statePrefs(context).edit()
                    .putLong(keySavedAt(slot), System.currentTimeMillis())
                    // A start the app asked for clears both budgets: the failure budget
                    // because this config demonstrably starts, and the unattended-restore
                    // budget because there is, right now, an app process behind it.
                    .putInt(keyFailures(slot), 0)
                    .putInt(keyRestores(slot), 0)
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
            final SharedPreferences p = configPrefs(context);
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

            // ── the two bounds ────────────────────────────────────────────────────────
            final SharedPreferences st = statePrefs(context);
            final long savedAt = st.getLong(keySavedAt(slot), 0L);
            final long ttlMs = o.optLong("TTL_MS", DEFAULT_TTL_MS);
            final long age = System.currentTimeMillis() - savedAt;
            if (savedAt <= 0L) {
                Log.w(TAG, "dropping slot " + slot + ": blob has no save time, cannot be aged");
                clear(context, slot);
                return null;
            }
            if (ttlMs <= 0L || age > ttlMs) {
                Log.w(TAG, "dropping slot " + slot + ": age " + (age / 1000) + "s exceeds ttl "
                        + (ttlMs / 1000) + "s");
                clear(context, slot);
                return null;
            }
            if (age < -MAX_CLOCK_SKEW_MS) {
                Log.w(TAG, "dropping slot " + slot + ": saved " + (-age / 1000)
                        + "s in the future, the clock moved and the age is meaningless");
                clear(context, slot);
                return null;
            }
            final int restores = st.getInt(keyRestores(slot), 0);
            final int maxRestores = o.optInt("MAX_UNATTENDED_RESTORES", DEFAULT_MAX_UNATTENDED_RESTORES);
            if (restores >= maxRestores) {
                Log.w(TAG, "dropping slot " + slot + ": " + restores
                        + " system restores since the app last asked for this session");
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
            Log.i(TAG, "restored config for slot " + slot + " => " + config.REMARK
                    + " (age " + (age / 1000) + "s, unattended restores " + restores + "/" + maxRestores + ")");
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
            configPrefs(context).edit()
                    .remove(keyConfig(slot))
                    .remove(keySchema(slot))
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "could not clear the config for slot " + slot, t);
        }
        try {
            statePrefs(context).edit()
                    .remove(keySavedAt(slot))
                    .remove(keyFailures(slot))
                    .remove(keyRestores(slot))
                    .apply();
            Log.i(TAG, "cleared persisted config for slot " + slot);
        } catch (Throwable t) {
            Log.w(TAG, "could not clear the state for slot " + slot, t);
        }
    }

    /**
     * Charge one attempt against BOTH budgets: the failure budget (cleared by a restore
     * that produced a real tunnel) and the unattended-restore budget (cleared only by an
     * app-initiated {@link #save}).
     *
     * @return true if the restore may proceed; false once either budget is spent, in
     *         which case the stored config has been dropped and the caller must stop.
     */
    public static boolean beginRestoreAttempt(final Context context, final String slot) {
        if (context == null) {
            return false;
        }
        try {
            final SharedPreferences p = statePrefs(context);
            final int failures = p.getInt(keyFailures(slot), 0);
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                Log.w(TAG, "slot " + slot + ": " + failures
                        + " consecutive failed restores, dropping the config");
                clear(context, slot);
                return false;
            }
            // Written BEFORE the attempt: if the attempt takes the process down, the
            // count still went up.
            p.edit()
                    .putInt(keyFailures(slot), failures + 1)
                    .putInt(keyRestores(slot), p.getInt(keyRestores(slot), 0) + 1)
                    .apply();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not read the restore budget for slot " + slot, t);
            return false;
        }
    }

    /**
     * A restore produced a real tunnel; forget the failures that preceded it.
     *
     * <p>⚠ CALL THIS ONLY WITH A TUN INTERFACE IN HAND. {@code startCore()} returning
     * true means the core loop started, not that any traffic can leave;
     * {@code builder.establish()} RETURNS NULL rather than throwing when we are not the
     * prepared VPN, and a budget cleared on that path never bites.
     *
     * <p>It deliberately does NOT touch the unattended-restore counter: a restore chain
     * that works is exactly the one that must still end, because nothing is metering it.
     */
    public static void noteRestoreSucceeded(final Context context, final String slot) {
        if (context == null) {
            return;
        }
        try {
            statePrefs(context).edit().putInt(keyFailures(slot), 0).apply();
        } catch (Throwable t) {
            Log.w(TAG, "could not reset the restore budget for slot " + slot, t);
        }
    }

    /** {@code _doft_autostart.ttl_ms}, or the default. Never throws. */
    private static long policyTtlMs(final String fullJson) {
        final JSONObject policy = policy(fullJson);
        if (policy == null || !policy.has(POLICY_TTL)) {
            return DEFAULT_TTL_MS;
        }
        return policy.optLong(POLICY_TTL, DEFAULT_TTL_MS);
    }

    /** {@code _doft_autostart.max_unattended_restores}, or the default. Never throws. */
    private static int policyMaxRestores(final String fullJson) {
        final JSONObject policy = policy(fullJson);
        if (policy == null || !policy.has(POLICY_MAX_RESTORES)) {
            return DEFAULT_MAX_UNATTENDED_RESTORES;
        }
        final int v = policy.optInt(POLICY_MAX_RESTORES, DEFAULT_MAX_UNATTENDED_RESTORES);
        return v < 0 ? 0 : v;
    }

    private static JSONObject policy(final String fullJson) {
        if (fullJson == null || fullJson.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(fullJson).optJSONObject(POLICY_OBJECT);
        } catch (Throwable t) {
            // An unparseable core config is the core's problem, not ours; take defaults.
            return null;
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
