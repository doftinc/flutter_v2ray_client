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
 * <h3>⚠ THIS IS A SECOND, JAVA-SIDE CONNECT INTENT — AND WHAT BOUNDS IT IS FAILURE, NOT TIME</h3>
 * The Dart layer keeps its own "should be connected" intent and clears it on events the
 * Java side cannot see — a data-cap cut being the one that matters, because restoring a
 * capped session after an app kill is a free-tier bypass with extra steps. A blob here
 * outlives the app process by design, so something has to bound it.
 *
 * <p><b>An earlier revision of this file bounded it with two numbers that were wrong:</b>
 * eight unattended restores and a seven-day expiry, both counted from the last
 * app-initiated connect. Consider who actually uses this. Always-on VPN is turned on once,
 * usually together with the kill switch, by a user whose whole reason for turning it on is
 * that they never have to think about the app again. Eight reboots — or one week of not
 * launching an app there is no reason to launch — and the blob was dropped, always-on had
 * nothing to start, and with the kill switch on that is a phone with NO connectivity at
 * all until a human opens the app. A bound whose failure mode is "the feature silently
 * switches itself off for every user who uses it as intended" is worse than the thing it
 * was bounding.
 *
 * <p>The question is not how old the config is. It is which of these two is happening:
 *
 * <ul>
 *   <li><b>"This config keeps FAILING."</b> Bounded hard, and this is the only bound that
 *       is on by default: {@link #MAX_CONSECUTIVE_FAILURES} restores that do not CARRY
 *       TRAFFIC and the blob is dropped. The counter is charged BEFORE the attempt and
 *       committed synchronously (see {@link #beginRestoreAttempt}), and it is cleared ONLY
 *       by {@link #noteTunnelCarriedTraffic}, which the VPN service calls only once
 *       DOWNLINK BYTES HAVE ACTUALLY MOVED through the interface — never on
 *       {@code startCore()} returning true, and never on {@code builder.establish()}
 *       handing back an interface. <b>A BLACK-HOLED ENTRY IP SATISFIES BOTH OF THOSE</b>:
 *       the tun establishes, the core runs, the handshake completes, and nothing comes
 *       back. So a config that black-holes, or that starts a core and never gets an
 *       interface, or that takes the process down with it, ends the loop after three
 *       tries whatever the framework does.</li>
 *   <li><b>"This config keeps WORKING and nobody has opened the app."</b> NOT bounded.
 *       There is no number of successful reboots after which a working tunnel should turn
 *       itself off, and every candidate number is a date on which somebody's device
 *       silently loses the network.</li>
 * </ul>
 *
 * <p>The metering worry that produced the time bound is real, but a blind timer in this
 * file is not the instrument for it. The side that knows the entitlement is the Dart side,
 * and it already has an exact one: put a {@code _doft_autostart} object in the core JSON —
 * {@code {"_doft_autostart":{"ttl_ms":3600000,"max_unattended_restores":2}}}. Both keys
 * default to <b>unbounded</b> here and are enforced strictly when present, so a metered or
 * free-tier connect is expected to carry a finite {@code ttl_ms}, and
 * <b>{@code "ttl_ms":0} means "never restorable"</b> — a config carrying it is not
 * persisted at all and any previously stored one is dropped, which makes it a kill switch
 * even for a cap cut that cannot reach the service.
 *
 * <p>⚠ NOTHING SETS {@code _doft_autostart} YET: the Dart connect path does not emit the
 * key, so as shipped an entitled and an unentitled session are bounded identically, by
 * failure alone. That is the deliberate trade — an unmetered restore of a lapsed session
 * costs one failed connect, because the node authenticates every reconnect and a lapsed
 * grant is refused there, whereas the timer's failure mode was a dead device. The
 * remaining work is in the Dart connect path, not in this file.
 *
 * <p>An explicit stop stays absolute and immediate: STOP_SERVICE and {@code onRevoke()}
 * both {@link #clear} the slot, synchronously, and a cleared slot cannot be restored by
 * anything.
 * <h3>What is in it</h3>
 * The full core JSON, which on this fork carries the device's TUIC credential in its
 * {@code _doft_tuic} block. It therefore lives in app-private storage and nowhere else,
 * an explicit stop {@link #clear clears} it rather than merely marking it unusable, and
 * the app manifest sets {@code android:allowBackup="false"} so it is not swept into cloud
 * backup. (Device-to-device transfer on Android 12+ additionally needs a
 * {@code <data-extraction-rules>} resource; see the note in AndroidManifest.xml.)
 *
 * <h3>Two preference files, on purpose</h3>
 * A {@link SharedPreferences} write rewrites the WHOLE backing XML, so staging a timestamp
 * next to the config blob would rewrite the blob — every reconnect, tens of kilobytes. The
 * blob lives in {@link #PREFS_CONFIG}, which is written only when the config actually
 * changes; the counters and timestamps that change on every start live in
 * {@link #PREFS_STATE}, which is a few dozen bytes. That split is also what makes the
 * synchronous {@code commit()} in {@link #beginRestoreAttempt} affordable: the write that
 * has to land before the attempt runs is against the small file.
 *
 * <h3>apply() vs commit(), which is not a style question here</h3>
 * apply() is asynchronous and its flush is guaranteed only on a NORMAL process exit. Every
 * write in this class that GRANTS something — a stored config, a cleared failure count —
 * uses apply(), because losing it fails safe. The two writes that TAKE something away —
 * charging the restore budget in {@link #beginRestoreAttempt}, and dropping the blob in
 * {@link #clear} — use commit(), because the exits they have to survive are the abnormal
 * ones: a restored config that kills the process, and a stop that races the framework
 * killing the service.
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

    /** {@code max_unattended_restores} sentinel: the chain is not counted at all. */
    private static final int RESTORES_UNBOUNDED = Integer.MAX_VALUE;

    /** {@code ttl_ms} sentinel: the blob is not aged at all. */
    private static final long TTL_NO_EXPIRY = Long.MAX_VALUE;

    /**
     * How many system-initiated restores may run between two app-initiated connects.
     *
     * <p>⚠ UNBOUNDED BY DEFAULT, ON PURPOSE. This counter rises on every restore,
     * successful or not, and is cleared only by an app-initiated {@link #save}. A finite
     * default therefore counts REBOOTS OF A WORKING TUNNEL and switches always-on off
     * after N of them — for the users least likely to ever open the app. A session that
     * must be metered by an app process says so with
     * {@code _doft_autostart.max_unattended_restores}; the failure budget above is what
     * stops a chain that is not working.
     */
    private static final int DEFAULT_MAX_UNATTENDED_RESTORES = RESTORES_UNBOUNDED;

    /**
     * How long after the app-initiated connect the blob stays restorable.
     *
     * <p>⚠ NO EXPIRY BY DEFAULT, ON PURPOSE — see the class comment. An expiry here is a
     * date on which an always-on device silently loses the network, and the grant it was
     * meant to track is enforced at the node on every reconnect anyway. A session with a
     * real deadline carries {@code _doft_autostart.ttl_ms}, which IS enforced.
     */
    private static final long DEFAULT_TTL_MS = TTL_NO_EXPIRY;

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
            // rewrite it: a write rewrites the whole XML it is staged against.
            //
            // ⚠ apply() HERE IS DELIBERATE, unlike in beginRestoreAttempt()/clear(). This
            // is the hot connect path (a ~100 KB blob, on the main thread, while the user
            // is watching the connect), and every way this write can be lost fails SAFE:
            // no blob means no restore, and a lost counter reset means the budget stays
            // charged, i.e. errs towards stopping. Nothing here is a permission to run.
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

            // ── the bounds ────────────────────────────────────────────────────────────
            // Both are OFF unless the connect that wrote this blob asked for them. The
            // bound that is always on is the failure budget, and it lives in
            // beginRestoreAttempt() where the attempt is charged.
            final SharedPreferences st = statePrefs(context);
            final long ttlMs = o.optLong("TTL_MS", DEFAULT_TTL_MS);
            if (ttlMs <= 0L) {
                // save() refuses to persist these, so a blob saying it is not restorable
                // can only be one written by an older build. Drop it.
                Log.w(TAG, "dropping slot " + slot + ": blob declares itself non-restorable");
                clear(context, slot);
                return null;
            }
            long age = -1L;
            if (ttlMs != TTL_NO_EXPIRY) {
                // The connect asked for a deadline, so it gets a strict one: a blob that
                // cannot be aged is not "young", it is unusable.
                final long savedAt = st.getLong(keySavedAt(slot), 0L);
                if (savedAt <= 0L) {
                    Log.w(TAG, "dropping slot " + slot + ": blob has a ttl but no save time");
                    clear(context, slot);
                    return null;
                }
                age = System.currentTimeMillis() - savedAt;
                if (age > ttlMs) {
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
            }
            final int restores = st.getInt(keyRestores(slot), 0);
            final int maxRestores = o.optInt("MAX_UNATTENDED_RESTORES", DEFAULT_MAX_UNATTENDED_RESTORES);
            if (maxRestores != RESTORES_UNBOUNDED && restores >= maxRestores) {
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
                    + " (age " + (age < 0L ? "not tracked" : (age / 1000) + "s")
                    + ", unattended restores " + restores + "/"
                    + (maxRestores == RESTORES_UNBOUNDED ? "unbounded" : String.valueOf(maxRestores)) + ")");
            return config;
        } catch (Throwable t) {
            // Unreadable today, unreadable forever. Drop it rather than retry it.
            Log.w(TAG, "dropping slot " + slot + ": stored config did not parse", t);
            clear(context, slot);
            return null;
        }
    }

    /**
     * Forget the tunnel. Called on an explicit stop, on {@code onRevoke()}, and when a
     * restore keeps failing.
     *
     * <p>⚠ {@code commit()}, NOT {@code apply()}. apply() only stages the change in
     * memory and flushes on a background thread; the flush is guaranteed to complete
     * before the process exits NORMALLY, and the two callers that matter here are exactly
     * the abnormal ones — a stop that races the framework killing the service, and the
     * drop of a config whose own start took the process down. A lost flush leaves a
     * credential blob restorable after the user said stop, which is the one outcome this
     * method exists to prevent. The cost is a synchronous write of a few dozen bytes to
     * two app-private XML files on a path that is already tearing a VPN down.
     */
    public static void clear(final Context context, final String slot) {
        if (context == null) {
            return;
        }
        try {
            configPrefs(context).edit()
                    .remove(keyConfig(slot))
                    .remove(keySchema(slot))
                    .commit();
        } catch (Throwable t) {
            Log.w(TAG, "could not clear the config for slot " + slot, t);
        }
        try {
            statePrefs(context).edit()
                    .remove(keySavedAt(slot))
                    .remove(keyFailures(slot))
                    .remove(keyRestores(slot))
                    .commit();
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
            // ⚠ WRITTEN BEFORE THE ATTEMPT, AND WITH commit(), NOT apply().
            //
            // apply() is asynchronous: it mutates the in-memory map and hands the disk
            // write to a background thread, and that write is only guaranteed to have
            // landed by the time the process exits normally. The single failure this
            // budget exists to bound is the abnormal exit — a restored config that takes
            // :RunSoLibV2RayDaemon down inside startCore(). With apply() the flush never
            // happens, the count never rose, the framework restarts the service, and the
            // restart loop the budget exists to end runs forever. commit() writes it
            // synchronously, so the count is on disk before the config gets a chance to
            // kill anything.
            //
            // This is a main-thread disk write (onStartCommand), which StrictMode will
            // flag: it is a few dozen bytes into the SMALL preferences file — the blob
            // lives in the other one precisely so this write stays cheap — and it is the
            // only thing standing between a bad config and an unbounded restart loop.
            p.edit()
                    .putInt(keyFailures(slot), failures + 1)
                    .putInt(keyRestores(slot), p.getInt(keyRestores(slot), 0) + 1)
                    .commit();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not read the restore budget for slot " + slot, t);
            return false;
        }
    }

    /**
     * The tunnel this restore built has CARRIED DOWNLINK TRAFFIC; forget the failures
     * that preceded it.
     *
     * <p>⚠ THE NAME IS THE POINT, AND IT USED TO BE {@code noteRestoreSucceeded}. The old
     * name invited the old call site: {@code V2rayVPNService.setup()} called it the
     * moment {@code builder.establish()} handed back a non-null interface, i.e. it
     * treated "the OS gave us a tun" as "the tunnel works". <b>A BLACK-HOLED ENTRY IP
     * SATISFIES THAT.</b> The tun establishes, the core runs, the handshake completes,
     * and zero bytes come back — the shape measured on 85.189.101.44 on 2026-08-12, where
     * every transport and both engines read 0 KB/s while the node looked healthy from
     * outside. Clearing the budget there meant an always-on device restored a
     * zero-throughput tunnel on every boot, forever, with the failure budget reset each
     * time; with the kill switch on that is a phone with no connectivity and no signal.
     *
     * <p>So the only caller is the watcher in {@code V2rayVPNService} that has seen
     * downlink bytes accumulate through the interface. Downlink is the discriminator on
     * purpose: uplink rises whether or not anything is at the other end.
     *
     * <p>It deliberately does NOT touch the unattended-restore counter: a restore chain
     * that works is exactly the one that must still end, because nothing is metering it.
     */
    public static void noteTunnelCarriedTraffic(final Context context, final String slot) {
        if (context == null) {
            return;
        }
        try {
            // apply() is right here: losing this write leaves the attempt charged, which
            // errs towards dropping the config. The writes that must not be lost are the
            // ones that GRANT nothing — the charge and the clear — and those commit().
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
