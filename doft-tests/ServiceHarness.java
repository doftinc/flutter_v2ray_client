import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import dev.amirzr.flutter_v2ray_client.v2ray.core.V2rayCoreManager;
import dev.amirzr.flutter_v2ray_client.v2ray.services.V2rayProxyOnlyService;
import dev.amirzr.flutter_v2ray_client.v2ray.services.V2rayVPNService;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.AppConfigs;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.AutoStartStore;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.V2rayConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs the REAL V2rayVPNService and V2rayProxyOnlyService — the two classes that decide
 * what happens when Android, not the user, starts us.
 *
 * <h2>⚠ READ THIS BEFORE YOU TRUST A GREEN RUN</h2>
 *
 * This suite has been through four rounds and four adversaries. Each adversary reverted
 * shipped lines one at a time and counted how many the suite failed to notice; each found
 * more than the last. What follows is the honest inventory as of round 4, so that "208
 * assertions, 0 failed" is read as the bounded claim it is and not as "Android is safe".
 *
 * <h3>WHAT A GREEN RUN HERE DOES MEAN</h3>
 *
 * Every item below was proved by DELETING the production line and watching this suite go
 * red. That is the only evidence a test is worth anything, and it is the standard used
 * throughout.
 *
 * <ul>
 *   <li><b>A system start brings the tunnel back.</b> A null intent (START_STICKY
 *       redelivery) and a bare action intent (always-on VPN) both restore from the last
 *       config that actually started the core, and the user-initiated START returns
 *       START_STICKY — without which no restart ever happens and nothing else here
 *       matters.</li>
 *   <li><b>Every give-up path ends the SERVICE, not just its resources.</b> The pre-fix
 *       pair {@code this.onDestroy(); return START_NOT_STICKY;} is caught at every
 *       reachable call site in both classes, and by shape in the one branch no test can
 *       reach (case 31a).</li>
 *   <li><b>A tun is not a success.</b> {@code builder.establish()} returning non-null does
 *       NOT clear the failure budget. Only downlink bytes actually moving does. This is
 *       the round-4 headline: a BLACK-HOLED ENTRY IP satisfies establish(), the core, and
 *       the handshake while carrying nothing (85.189.101.44 on this fleet, 0 KB/s on every
 *       transport and both engines while the node looked healthy from outside), so the old
 *       signal let an always-on device restore a dead tunnel on every boot forever.</li>
 *   <li><b>And the reverse is still true.</b> A config that KEEPS WORKING restores
 *       indefinitely with the app never opened (case 19: 30 reboots over a year), because
 *       the tunnel clears its own budget from inside the daemon process. Failure is
 *       bounded; success is not. Reintroducing a time or reboot bound is the round-2
 *       defect and case 19 is what catches it.</li>
 *   <li><b>The credential-bearing blob dies when the user says stop</b>, on BOTH slots,
 *       and on onRevoke, durably enough to survive a process kill mid-write.</li>
 *   <li><b>Teardown really tears down</b>: {@code isRunning=false} ends the real
 *       tun2socks respawn loop and {@code mInterface=null} makes the descriptor
 *       unreachable, both observed against a real exec'd process.</li>
 * </ul>
 *
 * <h3>⚠ WHAT A GREEN RUN HERE DOES <i>NOT</i> MEAN</h3>
 *
 * <ol>
 *   <li><b>NOTHING HERE IS BUILT FOR ANDROID, LET ALONE RUN ON IT.</b> This is a plain
 *       JVM with hand-written stubs for {@code android.*}. No Gradle, no dex, no device,
 *       no emulator. A change that compiles and passes here can still fail to build the
 *       AAR or crash on a phone.</li>
 *   <li><b>THE CORE IS A STUB.</b> {@code V2rayCoreManager} is faked; libv2ray, xray,
 *       tun2socks (a shell script that appends a line), the VpnService.Builder (every
 *       method returns {@code this}) and the binder are all fiction. Nothing here says a
 *       packet ever moves. In particular the routes, MTU, DNS servers and per-app rules
 *       the Builder is fed are never asserted at all.</li>
 *   <li><b>THE 512 KiB PROOF THRESHOLD IS A JUDGEMENT, NOT A MEASUREMENT.</b> The suite
 *       proves the MECHANISM (bytes gate the budget); it cannot tell you the NUMBER is
 *       right. Too low and a black hole that returns a few handshakes could pass; too
 *       high and a slow link loses its blob. Nobody has measured what a real black hole
 *       returns.</li>
 *   <li><b>THE KNOWN RESIDUAL HOLE IS NOT COVERED BECAUSE IT IS ACCEPTED, NOT FIXED.</b> A
 *       device that restores, carries less than the threshold, and is killed again —
 *       three times running — loses its blob and must be re-armed by opening the app.
 *       An idle or offline phone across three consecutive boots looks exactly like a
 *       black hole from inside {@code :RunSoLibV2RayDaemon}. That trade is deliberate
 *       (see {@code startRestoreProofWatcher}), and it is a real hole.</li>
 *   <li><b>THE NOTIFICATION IS ASSERTED BY SOURCE TEXT, NOT BY BUILDING ONE.</b> Cases 30
 *       and 31a read the .java files and grep them. {@code showNotification()} never runs
 *       here — it needs a real NotificationManager and a real PendingIntent — so
 *       "the Disconnect button works" is NOT proved; only "the wiring was not silently
 *       undone" is. The same caveat applies to the unknown-command branch.</li>
 *   <li><b>startForeground() AND THE FGS DEADLINE ARE UNTESTABLE HERE.</b> The 6205a88
 *       crash class (notification fails to build ⇒ startForeground missed ⇒ OS kills the
 *       process) can only be seen on a device.</li>
 *   <li><b>CONCURRENCY IS BARELY EXERCISED.</b> Two cases run the real watcher thread;
 *       everything else drives {@code restoreProofTick} on the caller's thread so results
 *       are deterministic. Races between a teardown, a restore and the watcher are NOT
 *       covered.</li>
 *   <li><b>THE PREFERENCES FAKE MODELS ONE FAILURE, NOT ALL OF THEM.</b> LosablePrefs
 *       models "an apply() that never flushed before the kill". It does not model partial
 *       XML writes, a corrupt prefs file, multi-process prefs, or a full disk.</li>
 *   <li><b>ANDROID VERSION AND OEM BEHAVIOUR ARE ABSENT.</b> Doze, restricted app
 *       standby buckets, OEM task killers, per-version always-on semantics, and whether
 *       the framework actually redelivers a null intent on THIS device are all assumed,
 *       not tested.</li>
 *   <li><b>EVERYTHING OUTSIDE THESE FOUR FILES IS UNCOVERED.</b> The Dart layer, the
 *       method channel, {@code V2rayController}, and the rest of V2rayCoreManager
 *       (queryStats, the duration timer, the tag list) have no cases here.</li>
 *   <li><b>ADVERSARY FINDINGS LEFT OPEN, BY NAME.</b> The round-3 adversary ran sixty
 *       single-line reverts and reported "one real bug and twenty-plus green holes". Only
 *       about a dozen were named to this round and all of those are now closed; a round-4
 *       run of 34 further reverts of my own found three more (cases 31a–31c) and closed
 *       them too. <b>The unnamed remainder of that twenty-plus is still open and unknown
 *       to this file.</b> Assume a fifth adversary will find more, because the tail of
 *       untested lines in a 900-line service class is not bounded by any of this.</li>
 * </ol>
 *
 * <p>The service classes are subclassed here ONLY to redirect Context plumbing
 * (SharedPreferences, Resources, nativeLibraryDir) at in-memory fakes. No method under
 * test is overridden.
 */
public class ServiceHarness {

    static int failures = 0;
    /** counted at runtime so the suite total cannot be hand-typed wrong */
    static int checks = 0;

    /**
     * Run one case. ⚠ A CASE THAT THROWS IS A FAILED CASE, NOT A FAILED RUN: the pre-fix
     * V2rayVPNService NPEs inside setup() when establish() hands back null, and letting
     * that abort the JVM would hide every case after it.
     */
    static void run(Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            checks++;
            failures++;
            System.out.printf("%-58s %s  %s%n", "(case threw)", "FAIL", t);
            t.printStackTrace();
        }
    }

    static void check(String name, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.printf("%-58s %s%s%n", name, ok ? "PASS" : "FAIL",
                (ok || detail == null) ? "" : "  " + detail);
    }

    // ── fakes ────────────────────────────────────────────────────────────────────────

    /**
     * ⚠ THE FAKE IS LosablePrefs, WHOSE apply() CAN BE LOST. A fake whose apply() lands
     * synchronously cannot distinguish apply() from commit(), so it green-lights a budget
     * that is never written before the crash it is bounding.
     */

    /** The on-disk state a killed process leaves behind: survives the service instance. */
    static class Disk {
        final Map<String, LosablePrefs> files = new HashMap<>();

        LosablePrefs file(String name) {
            LosablePrefs p = files.get(name);
            if (p == null) {
                p = new LosablePrefs();
                files.put(name, p);
            }
            return p;
        }

        LosablePrefs config() { return file("doft_v2ray_autostart"); }

        LosablePrefs state() { return file("doft_v2ray_autostart_state"); }

        /** from here on, an apply() stages in memory and never reaches the disk */
        Disk losingApplies() {
            for (LosablePrefs p : files.values()) {
                p.dropUnflushedApplies = true;
            }
            return this;
        }

        /** Android killed :RunSoLibV2RayDaemon; unflushed apply()s are gone with it. */
        void processDied() {
            for (LosablePrefs p : files.values()) {
                p.processDied();
            }
        }

        boolean hasVpnBlob() { return config().map.get("vpn_config") != null; }

        boolean hasProxyBlob() { return config().map.get("proxy_config") != null; }

        int vpnFailures() { return state().getInt("vpn_failures", 0); }

        int vpnRestores() { return state().getInt("vpn_restores", 0); }
    }

    static class FakeResources extends Resources {
        static final int ICON = 0x7f080001;

        public int getIdentifier(String name, String defType, String defPackage) {
            return "com.doft.vpn:mipmap/ic_launcher".equals(name) ? ICON : 0;
        }

        public String getResourceName(int id) {
            if (id == ICON) return "com.doft.vpn:mipmap/ic_launcher";
            throw new RuntimeException("no resource " + id);
        }

        public String getResourceTypeName(int id) {
            if (id == ICON) return "mipmap";
            throw new RuntimeException("no resource " + id);
        }
    }

    static class TestVpn extends V2rayVPNService {
        final Disk disk;
        final Resources res = new FakeResources();
        /** where runTun2socks() looks for libtun2socks.so; nowhere, unless a case redirects it */
        String nativeLibs = "/nonexistent-doft-test-libs";

        TestVpn(Disk d) { disk = d; }

        TestVpn withNativeLibs(String dir) { nativeLibs = dir; return this; }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return disk.file(name); }
        @Override public Resources getResources() { return res; }
        @Override public Context getApplicationContext() { return this; }
        @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
            android.content.pm.ApplicationInfo ai = new android.content.pm.ApplicationInfo();
            ai.nativeLibraryDir = nativeLibs;
            return ai;
        }
    }

    static class TestProxy extends V2rayProxyOnlyService {
        final Disk disk;
        final Resources res = new FakeResources();

        TestProxy(Disk d) { disk = d; }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return disk.file(name); }
        @Override public Resources getResources() { return res; }
        @Override public Context getApplicationContext() { return this; }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────

    static V2rayConfig config(String policy) {
        V2rayConfig c = new V2rayConfig();
        c.CONNECTED_V2RAY_SERVER_ADDRESS = "204.3.207.89";
        c.CONNECTED_V2RAY_SERVER_PORT = "443";
        c.LOCAL_SOCKS5_PORT = 10808;
        c.LOCAL_HTTP_PORT = 10809;
        c.V2RAY_FULL_JSON_CONFIG = "{\"outbounds\":[{\"tag\":\"proxy\"}]"
                + (policy == null ? "" : ",\"_doft_autostart\":" + policy) + "}";
        c.ENABLE_TRAFFIC_STATICS = true;
        c.REMARK = "Marseille";
        c.APPLICATION_NAME = "Doft VPN";
        c.NOTIFICATION_DISCONNECT_BUTTON_NAME = "Disconnect";
        c.APPLICATION_ICON = FakeResources.ICON;
        return c;
    }

    static Intent command(AppConfigs.V2RAY_SERVICE_COMMANDS cmd, V2rayConfig cfg) {
        Intent i = new Intent();
        i.putExtra("COMMAND", cmd);
        if (cfg != null) {
            i.putExtra("V2RAY_CONFIG", cfg);
        }
        return i;
    }

    /** A start the USER made: the only thing that arms the store. */
    static void userStart(TestVpn s, V2rayConfig cfg) {
        V2rayCoreManager.coreRunning = false;
        s.onCreate();
        s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, cfg), 0, 1);
    }

    /** A start ANDROID made after killing the process: new instance, same disk, null intent. */
    static int stickyRestart(TestVpn s) {
        V2rayCoreManager.coreRunning = false;
        s.onCreate();
        return s.onStartCommand(null, 0, 1);
    }

    // ── reflection helpers ───────────────────────────────────────────────────────────
    // ⚠ USED ONLY TO OBSERVE AND TO DRIVE, NEVER TO REPLACE. Nothing below overrides a
    // method under test; the alternative to reflection here is widening production
    // visibility for a test, which is a worse trade.

    static Object priv(Object target, Class<?> owner, String field) {
        try {
            java.lang.reflect.Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void privSet(Object target, Class<?> owner, String field, Object value) {
        try {
            java.lang.reflect.Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Object call(Object target, String method, Class<?>[] sig, Object... args) {
        try {
            java.lang.reflect.Method m = V2rayVPNService.class.getDeclaredMethod(method, sig);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** How many downlink bytes the service demands before it believes a restore worked. */
    static final long PROOF_BYTES = (Long) priv(null, V2rayVPNService.class, "PROOF_DOWNLINK_BYTES");

    /** One evaluation of the proof, run on the caller's thread so cases stay deterministic. */
    static boolean proofTick(V2rayVPNService s) {
        return (Boolean) call(s, "restoreProofTick", new Class<?>[] { long.class }, 0L);
    }

    /**
     * Drive a restore all the way to PROVED: a tun, then enough downlink bytes to satisfy
     * PROOF_DOWNLINK_BYTES, then one tick of the proof. ⚠ THE TWO HALVES ARE SEPARATE ON
     * PURPOSE - a black hole gives you the first and never the second.
     */
    /**
     * Wait until the service's teardown lane is idle. Generously bounded: this is the
     * suite making itself deterministic, not the property under test.
     *
     * <p>⚠ EVERY STOP IN THIS FILE NEEDS IT NOW. The teardown moved off the caller's
     * thread, so a check written straight after {@code onStartCommand(STOP_SERVICE)} is
     * racing it. It passed anyway on the machine this was written on, which is exactly
     * the kind of green that means nothing.
     */
    static boolean joinLane(V2rayVPNService s) {
        return (Boolean) call(s, "joinTeardown", new Class<?>[] { });
    }

    /**
     * Wedge the lane shut until the returned latch is opened.
     *
     * <p>⚠ THE ONLY DETERMINISTIC WAY TO ASK "DID THIS RUN SYNCHRONOUSLY". Gating
     * {@code stopCore} parks the lane INSIDE the teardown, so anything a regression moved
     * onto the lane AHEAD of stopCore has already run by the time the check looks — a
     * mutation that deferred onRevoke's AutoStartStore.clear() survived a suite that
     * could only gate stopCore. A barrier queued FIRST means nothing the callback posts
     * can have happened at all.
     */
    static java.util.concurrent.CountDownLatch wedgeLane(V2rayVPNService s) {
        final java.util.concurrent.CountDownLatch open = new java.util.concurrent.CountDownLatch(1);
        call(s, "offTheMainThread", new Class<?>[] { String.class, Runnable.class },
                "harness barrier", (Runnable) () -> {
                    try {
                        open.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        return open;
    }

    /**
     * A source file with its line comments removed.
     *
     * ⚠ THE COMMENTS ARE PART OF THE PROBLEM, NOT BACKGROUND. A structural check that
     * searches raw text finds the explanation before the code: the note above the
     * MEASURE_DELAY branch names `getConnectedV2rayServerDelay` two lines before the call
     * that runs it, so "the probe comes after the lane" read FALSE on correct code. In a
     * file where the comments outweigh the statements, any ordering check has to look at
     * the statements.
     */
    static String readCode(String relative) {
        StringBuilder out = new StringBuilder();
        for (String ln : readSource(relative).split("\n", -1)) {
            int i = ln.indexOf("//");
            out.append(i >= 0 ? ln.substring(0, i) : ln).append("\n");
        }
        return out.toString();
    }

    /** A production source file, read from disk. See case 47's note. */
    static String readSource(String relative) {
        try {
            return new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(relative)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Spin until [cond] holds, or fail the case rather than hang the suite. */
    static void waitFor(java.util.function.BooleanSupplier cond, String detail) {
        long deadline = System.currentTimeMillis() + 10000L;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(2L); } catch (InterruptedException ignored) { }
        }
        check("harness: " + detail, cond.getAsBoolean(), detail);
    }

    static void restoreAndProve(TestVpn s) {
        VpnService.establishResult = new ParcelFileDescriptor();
        s.startService();
        V2rayCoreManager.totalDownloadBytes += PROOF_BYTES;
        proofTick(s);
    }

    /**
     * Every LIVE thread with this name. ⚠ There can be more than one: earlier cases in
     * this run arm watchers too, and a daemon that has not noticed its generation is
     * stale yet is still alive. A case that wants "the watcher I just armed" must diff
     * this set across the call, not take the first match.
     */
    static java.util.List<Thread> liveThreads(String name) {
        Thread[] all = new Thread[Thread.activeCount() * 2 + 64];
        int n = Thread.enumerate(all);
        java.util.List<Thread> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (all[i] != null && name.equals(all[i].getName()) && all[i].isAlive()) {
                out.add(all[i]);
            }
        }
        return out;
    }

    /** Spin until the condition holds or the budget runs out. Returns whether it held. */
    static boolean waitFor(long millis, java.util.function.BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                return cond.getAsBoolean();
            }
        }
        return cond.getAsBoolean();
    }

    /**
     * A stand-in for libtun2socks.so: a shell script that records one line per exec and
     * exits. ⚠ IT HAS TO BE A REAL PROCESS. The only way to observe the Tun2socks_Thread
     * respawn loop - and therefore the only way to observe the isRunning flag that ends
     * it - is to let runTun2socks() actually exec something that exits.
     */
    static final class Tun2socks {
        static java.io.File dir;
        static java.io.File log;

        static synchronized java.io.File fakeLibDir() throws Exception {
            if (dir != null) {
                return dir;
            }
            // ⚠ A DIRECTORY THIS JVM OWNS, NOT A FIXED NAME UNDER java.io.tmpdir. On Linux
            // that property is `/tmp` — shared by every user and every CI job on the host,
            // and kept between jobs on a self-hosted runner — so the Files.write below
            // landed on a copy an earlier run had left, threw AccessDeniedException, and
            // killed this case before its first assertion. The suite came back 315/1 on the
            // runner while eighteen consecutive local runs were 316/0, because on macOS
            // java.io.tmpdir is a PER-USER directory under /var/folders and the collision
            // cannot happen there. The gate that runs this suite at the pinned ref is what
            // surfaced it; nothing else was ever going to.
            java.io.File d = java.nio.file.Files.createTempDirectory("doft-fake-tun2socks").toFile();
            d.deleteOnExit();
            log = new java.io.File(d, "execs.log");
            log.delete();
            java.io.File bin = new java.io.File(d, "libtun2socks.so");
            java.nio.file.Files.write(bin.toPath(),
                    ("#!/bin/sh\nprintf 'x\\n' >> '" + log.getAbsolutePath() + "'\nsleep 0.05\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bin.setExecutable(true, false);
            // runTun2socks() runs the binary with getFilesDir() as its working directory —
            // which is now the stub Context's own per-JVM directory, for the same reason.
            android.content.Context.ASSETS.mkdirs();
            dir = d;
            return d;
        }

        static int execCount() {
            try {
                return (int) java.nio.file.Files.readAllLines(log.toPath()).size();
            } catch (Exception e) {
                return 0;
            }
        }
    }

    static void resetWorld() {
        V2rayCoreManager.reset();
        VpnService.reset();
        AppConfigs.V2RAY_CONNECTION_MODE = AppConfigs.V2RAY_CONNECTION_MODES.VPN_TUN;
    }

    public static void main(String[] args) {

        // 1. THE DEFECT ITSELF. Android redelivers a NULL intent when it restarts a
        //    service that returned START_STICKY. Answering it with onDestroy() +
        //    START_NOT_STICKY is why no tunnel has ever come back from a kill.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            check("armed: a user start persists the config", disk.hasVpnBlob(), "nothing stored");

            // A fresh process starts with the static back at its default; set it to the
            // WRONG value so that "the restore re-asserts it" is an assertion and not a
            // coincidence. showNotification() reads it to aim the stop button.
            AppConfigs.V2RAY_CONNECTION_MODE = AppConfigs.V2RAY_CONNECTION_MODES.PROXY_ONLY;
            TestVpn restarted = new TestVpn(disk);
            int r = stickyRestart(restarted);
            check("sticky restart aims the notification at the VPN service",
                    AppConfigs.V2RAY_CONNECTION_MODE == AppConfigs.V2RAY_CONNECTION_MODES.VPN_TUN,
                    "" + AppConfigs.V2RAY_CONNECTION_MODE);
            check("sticky restart (null intent) returns START_STICKY",
                    r == android.app.Service.START_STICKY, "returned " + r);
            check("sticky restart starts the core again",
                    V2rayCoreManager.startCoreCalls == 2, "startCore calls " + V2rayCoreManager.startCoreCalls);
            check("sticky restart does not stop the service",
                    !restarted.stopSelfCalled, "stopSelf was called");
        });

        // 2. ALWAYS-ON. The framework starts a VpnService with a bare action intent: not
        //    null, but with no COMMAND extra. Same answer required.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));

            TestVpn alwaysOn = new TestVpn(disk);
            V2rayCoreManager.coreRunning = false;
            alwaysOn.onCreate();
            int r = alwaysOn.onStartCommand(new Intent("android.net.VpnService"), 0, 1);
            check("always-on (bare action intent) returns START_STICKY",
                    r == android.app.Service.START_STICKY, "returned " + r);
            check("always-on starts the core", V2rayCoreManager.startCoreCalls == 2,
                    "startCore calls " + V2rayCoreManager.startCoreCalls);
        });

        // 3. Nothing armed => nothing to restore. Fail closed, and stop for real.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            int r = stickyRestart(s);
            check("no persisted config: START_NOT_STICKY", r == android.app.Service.START_NOT_STICKY,
                    "returned " + r);
            check("no persisted config: service actually stops", s.stopSelfCalled, "still alive");
            check("no persisted config: core never started", V2rayCoreManager.startCoreCalls == 0,
                    "" + V2rayCoreManager.startCoreCalls);
        });

        // 4. NO CONSENT, NO BUDGET SPENT. prepare() non-null means "ask the user", and a
        //    system-initiated start has no Activity to ask with. That is not the config's
        //    fault, so it must not be charged against the failure budget - otherwise a
        //    user who grants a different VPN for a day comes back to a dropped config.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            VpnService.prepareResult = new Intent("consent");

            TestVpn s = new TestVpn(disk);
            int r = stickyRestart(s);
            check("no VPN consent: START_NOT_STICKY", r == android.app.Service.START_NOT_STICKY,
                    "returned " + r);
            check("no VPN consent: does not charge the budget", disk.vpnFailures() == 0,
                    "failures " + disk.vpnFailures());
            check("no VPN consent: config is kept for later", disk.hasVpnBlob(), "dropped");
            check("no VPN consent: core is not started", V2rayCoreManager.startCoreCalls == 1,
                    "" + V2rayCoreManager.startCoreCalls);
        });

        // 5. AN EXPLICIT STOP IS FINAL. Nothing may bring the tunnel back afterwards.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            userStart(s, config(null));
            s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE, null), 0, 2);
            joinLane(s);
            check("STOP_SERVICE clears the slot", !disk.hasVpnBlob(), "blob survived the stop");
            check("STOP_SERVICE clears the credential-bearing blob",
                    disk.config().map.get("vpn_config") == null, "still stored");

            int r = stickyRestart(new TestVpn(disk));
            check("after STOP_SERVICE nothing restores", r == android.app.Service.START_NOT_STICKY,
                    "returned " + r);
        });

        // 6. ⚠ THE ONE THE WHOLE DESIGN RESTED ON. builder.establish() RETURNS NULL - it
        //    does not throw - when we are not the prepared VPN any more (another VPN took
        //    the slot between prepare() and here, or an app update revoked us). The old
        //    code assigned that null, called noteTunnelCarriedTraffic, and NPE'd later inside
        //    sendFileDescriptor(), where runTun2socks' own catch swallowed it into a hand
        //    -called onDestroy(): core up, no tun, budget back at zero, restart forever.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));

            TestVpn s = new TestVpn(disk);
            stickyRestart(s);
            check("null establish: budget was charged for the attempt", disk.vpnFailures() == 1,
                    "failures " + disk.vpnFailures());
            VpnService.establishResult = null;
            s.startService(); // what xray's startup() callback does: runs setup()
            check("null establish: budget stays charged (no false success)",
                    disk.vpnFailures() == 1, "failures " + disk.vpnFailures());
            check("null establish: the core is stopped, not left running",
                    !V2rayCoreManager.coreRunning, "core still running");
            check("null establish: the service is stopped for real", s.stopSelfCalled,
                    "service left alive");
        });

        // 7. ⚠ A REAL TUN DOES NOT CLEAR THE BUDGET; CARRIED TRAFFIC DOES. This case
        //    asserted the opposite until round 4, which is precisely the defect: a
        //    black-holed entry IP hands back a perfect tun and moves nothing (case 22).
        //    The tun2socks binary that cannot start (there is none in a test JVM) must
        //    still end in a real stop, not in the hand-called onDestroy() that left the
        //    service alive.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));

            TestVpn s = new TestVpn(disk);
            stickyRestart(s);
            check("restore charges the budget up front", disk.vpnFailures() == 1,
                    "failures " + disk.vpnFailures());
            VpnService.establishResult = new ParcelFileDescriptor();
            V2rayCoreManager.totalDownloadBytes = 0L;
            s.startService();
            check("a tun ALONE does not clear the failure budget", disk.vpnFailures() == 1,
                    "failures " + disk.vpnFailures());
            V2rayCoreManager.totalDownloadBytes = PROOF_BYTES;
            check("carried downlink traffic proves the restore", proofTick(s), "not proved");
            check("a proved restore clears the failure budget", disk.vpnFailures() == 0,
                    "failures " + disk.vpnFailures());
            check("tun2socks that cannot start stops the SERVICE, not just its resources",
                    s.stopSelfCalled, "onDestroy-style cleanup left the service alive");
            check("tun2socks failure stops the core too", !V2rayCoreManager.coreRunning,
                    "core still running");
        });

        // 8. IDEMPOTENT. The framework re-sends the always-on start intent; tearing a
        //    healthy tunnel down to rebuild it would be a self-inflicted outage.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            V2rayCoreManager.coreRunning = true;
            TestVpn s = new TestVpn(disk);
            s.onCreate();
            int r = s.onStartCommand(null, 0, 1);
            check("restore is idempotent while the core runs",
                    r == android.app.Service.START_STICKY && V2rayCoreManager.startCoreCalls == 1,
                    "returned " + r + ", startCore " + V2rayCoreManager.startCoreCalls);
        });

        // 9. THE BUDGET ENDS THE LOOP. Always-on is retried by the framework whatever we
        //    return, so a config that never produces a tun must be dropped eventually.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            VpnService.establishResult = null;
            int last = 0;
            for (int i = 0; i < 3; i++) {
                TestVpn s = new TestVpn(disk);
                last = stickyRestart(s);
                s.startService();
            }
            check("three tunnel-less restores are allowed", last == android.app.Service.START_STICKY,
                    "returned " + last);
            TestVpn fourth = new TestVpn(disk);
            int r = stickyRestart(fourth);
            check("the fourth is refused", r == android.app.Service.START_NOT_STICKY, "returned " + r);
            check("and the config is dropped", !disk.hasVpnBlob(), "still stored");
        });

        // 10. ⚠ THE SECOND CONNECT INTENT MUST NOT OUTLIVE THE GRANT. A restore chain
        //     that WORKS is exactly the one nothing is metering: there is no app process
        //     behind it. noteTunnelCarriedTraffic may reset the failure budget; it must not
        //     reset the unattended-restore budget, or the chain refills itself forever.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config("{\"max_unattended_restores\":2}"));
            for (int i = 0; i < 2; i++) {
                TestVpn s = new TestVpn(disk);
                stickyRestart(s);
                restoreAndProve(s);
            }
            check("successful restores still count against the unattended budget",
                    disk.vpnRestores() == 2, "restores " + disk.vpnRestores());
            TestVpn third = new TestVpn(disk);
            int r = stickyRestart(third);
            check("an unattended chain ends at its bound", r == android.app.Service.START_NOT_STICKY,
                    "returned " + r);
            check("the config is dropped, so the app must re-arm it", !disk.hasVpnBlob(), "still stored");

            // And an app-initiated connect refills it - that is the whole point.
            TestVpn again = new TestVpn(disk);
            userStart(again, config("{\"max_unattended_restores\":2}"));
            check("an app-initiated connect re-arms the store",
                    disk.hasVpnBlob() && disk.vpnRestores() == 0,
                    "blob " + disk.hasVpnBlob() + ", restores " + disk.vpnRestores());
        });

        // 11. AN EXPIRY THE BLOB CARRIES - WHEN THE CONNECT ASKED FOR ONE. A session that
        //     an app process has to meter (free tier, trial, anything counted against a
        //     cap) is connected with a finite `_doft_autostart.ttl_ms`, and the service
        //     must refuse it once it is past. ⚠ There is NO default expiry any more; case
        //     19 is why. The bound that is always on is failure, not age.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config("{\"ttl_ms\":3600000}"));
            // an hour and a second later, on a device that has not run the app since
            disk.state().map.put("vpn_saved_at", System.currentTimeMillis() - 3_601_000L);
            int r = stickyRestart(new TestVpn(disk));
            check("a metered blob past its ttl does not restore",
                    r == android.app.Service.START_NOT_STICKY, "returned " + r);
            check("a metered blob past its ttl is dropped", !disk.hasVpnBlob(), "still stored");
        });

        // 12. ttl_ms:0 IS A KILL SWITCH THE DART SIDE CAN SET AT CONNECT TIME, for a
        //     session it must be present to meter. It is refused at save() time, so it
        //     works even when the later stop cannot be delivered.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            check("armed before the non-restorable connect", disk.hasVpnBlob(), "nothing stored");
            userStart(new TestVpn(disk), config("{\"ttl_ms\":0}"));
            check("ttl_ms:0 is not persisted", !disk.hasVpnBlob(), "persisted anyway");
            int r = stickyRestart(new TestVpn(disk));
            check("ttl_ms:0 cannot be resurrected", r == android.app.Service.START_NOT_STICKY,
                    "returned " + r);
        });

        // 13. THE CONFIG FILE IS NOT REWRITTEN BY A RECONNECT TO THE SAME NODE. apply()
        //     rewrites the whole XML it is staged against, so the timestamps and counters
        //     live in a different preferences file from the ~100 KB blob.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            int blobWrites = disk.config().applies;
            userStart(new TestVpn(disk), config(null));
            check("same config again does not rewrite the blob file",
                    disk.config().applies == blobWrites,
                    "applies " + blobWrites + " -> " + disk.config().applies);
            check("but the state file is updated", disk.state().applies > 1,
                    "applies " + disk.state().applies);
        });

        // ── proxy-only service ───────────────────────────────────────────────────────

        // 14. Same null-intent defect, same answer.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestProxy p = new TestProxy(disk);
            p.onCreate();
            p.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, config(null)), 0, 1);
            check("proxy: a user start persists the config", disk.hasProxyBlob(), "nothing stored");

            V2rayCoreManager.coreRunning = false;
            TestProxy restarted = new TestProxy(disk);
            restarted.onCreate();
            int r = restarted.onStartCommand(null, 0, 1);
            check("proxy: sticky restart returns START_STICKY",
                    r == android.app.Service.START_STICKY, "returned " + r);
            check("proxy: sticky restart starts the core",
                    V2rayCoreManager.startCoreCalls == 2, "" + V2rayCoreManager.startCoreCalls);
            // ⚠ resetWorld() left this at VPN_TUN, which is what a fresh process has. If
            // the proxy restore does not re-assert PROXY_ONLY, showNotification() aims the
            // notification's stop button at V2rayVPNService - a button that stops nothing,
            // on the only notification the user has.
            check("proxy: the restore aims the notification's stop button at the proxy",
                    AppConfigs.V2RAY_CONNECTION_MODE == AppConfigs.V2RAY_CONNECTION_MODES.PROXY_ONLY,
                    "" + AppConfigs.V2RAY_CONNECTION_MODE);
        });

        // 15. ⚠ THE PROXY HAS NO TUN TO POINT AT, so it has NO evidence that a restore
        //     worked - startCore() returning true only means startLoop() did not throw.
        //     Clearing the budget there is the pattern V2rayVPNService's own comment
        //     forbids: the chain would refill its own budget and never end.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestProxy p = new TestProxy(disk);
            p.onCreate();
            p.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, config(null)), 0, 1);
            int last = 0;
            for (int i = 0; i < 3; i++) {
                V2rayCoreManager.coreRunning = false;
                TestProxy s = new TestProxy(disk);
                s.onCreate();
                last = s.onStartCommand(null, 0, 1);
            }
            check("proxy: three restores are allowed", last == android.app.Service.START_STICKY,
                    "returned " + last);
            V2rayCoreManager.coreRunning = false;
            TestProxy fourth = new TestProxy(disk);
            fourth.onCreate();
            int r = fourth.onStartCommand(null, 0, 1);
            check("proxy: a restore does not refill its own budget",
                    r == android.app.Service.START_NOT_STICKY, "returned " + r);
            check("proxy: the config is dropped", !disk.hasProxyBlob(), "still stored");
        });

        // 16. ⚠ onRevoke() IS THE OTHER WAY THE USER SAYS NO, and it had no test at all:
        //     deleting its AutoStartStore.clear() left this whole suite green. The
        //     framework calls it when VPN consent is revoked in Settings or when another
        //     app takes the VPN slot. That is the tunnel being turned off by someone, so
        //     the credential blob must go with it - otherwise the next sticky restart or
        //     always-on start puts back the tunnel the user just took away.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            userStart(s, config(null));
            check("armed before the revoke", disk.hasVpnBlob(), "nothing stored");

            s.onRevoke();
            joinLane(s);
            check("onRevoke drops the credential-bearing blob", !disk.hasVpnBlob(), "still stored");
            check("onRevoke stops the core", !V2rayCoreManager.coreRunning, "core still running");
            check("onRevoke stops the service for real", s.stopSelfCalled, "service left alive");

            int r = stickyRestart(new TestVpn(disk));
            check("nothing restores after a revoke", r == android.app.Service.START_NOT_STICKY,
                    "returned " + r);
            check("and the core was not started again", V2rayCoreManager.startCoreCalls == 1,
                    "" + V2rayCoreManager.startCoreCalls);
        });

        // 17. ⚠ CONSENT CAN BE LOST BETWEEN onStartCommand AND setup(). restoreLastKnownGood()
        //     checks prepare() before it starts the core, but setup() runs later, from
        //     xray's startup() callback - by which time another VPN may hold the slot.
        //     setup()'s prepare() branch used to be a BARE RETURN, which leaves a core
        //     that is already running with no tun to put its traffic in. Reverting it to
        //     a bare return leaves the suite green unless this case exists.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));

            TestVpn s = new TestVpn(disk);
            stickyRestart(s);                                   // consent was granted here
            check("the restore started the core", V2rayCoreManager.coreRunning, "core not running");
            VpnService.prepareResult = new Intent("consent");    // another VPN takes the slot
            int establishBefore = VpnService.establishCalls;

            s.startService();                                   // the startup() callback
            check("consent lost before setup(): no tun is attempted",
                    VpnService.establishCalls == establishBefore,
                    "establish calls " + establishBefore + " -> " + VpnService.establishCalls);
            check("consent lost before setup(): the core is stopped, not left running",
                    !V2rayCoreManager.coreRunning, "a core is running with no tun");
            check("consent lost before setup(): the service is stopped for real",
                    s.stopSelfCalled, "service left alive");
            check("consent lost before setup(): the attempt stays charged",
                    disk.vpnFailures() == 1, "failures " + disk.vpnFailures());
        });

        // 18. sendFileDescriptor() WITH NO INTERFACE. The tun2socks watcher thread
        //     re-enters runTun2socks() when the binary exits, and stopAllProcess() may
        //     already have closed and nulled the interface by then. This used to be an
        //     NPE, swallowed by runTun2socks' own catch, which then hand-called
        //     onDestroy() - cleanup without a stop, on a service that had returned
        //     START_STICKY. It is invoked directly here because the race cannot be
        //     scheduled deterministically from outside the class.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            s.onCreate();
            boolean threw = false;
            try {
                java.lang.reflect.Method m =
                        V2rayVPNService.class.getDeclaredMethod("sendFileDescriptor");
                m.setAccessible(true);
                m.invoke(s); // mInterface is null: setup() never ran
            } catch (java.lang.reflect.InvocationTargetException e) {
                threw = true;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            check("sendFileDescriptor with no tun interface does not throw", !threw,
                    "it threw, so runTun2socks would hand-call onDestroy()");
        });

        // 19. ⚠ ALWAYS-ON IS THE FEATURE WHOSE USERS NEVER OPEN THE APP. An earlier
        //     revision bounded the restore chain at 8 unattended restores and a 7-day
        //     expiry, both counted from the last app-initiated connect - so a phone with
        //     always-on and the kill switch on lost the network entirely a week later,
        //     until somebody launched an app they had no reason to launch. Failure is
        //     bounded (cases 9 and 15); success is not.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            // the app has not been opened since; the clock says over a year
            disk.state().map.put("vpn_saved_at",
                    System.currentTimeMillis() - 400L * 24L * 60L * 60L * 1000L);

            int last = 0;
            for (int i = 0; i < 30; i++) {
                TestVpn s = new TestVpn(disk);
                last = stickyRestart(s);
                restoreAndProve(s);                     // a tun that CARRIES TRAFFIC
            }
            check("30 reboots over a year with the app never opened still restore",
                    last == android.app.Service.START_STICKY, "returned " + last);
            // ⚠ THIS IS THE HALF THAT KEEPS THE ROUND-4 FIX FROM BECOMING THE ROUND-2
            // DEFECT. A working tunnel clears its own budget from inside the daemon
            // process, with no app running, so "keeps working and nobody opened the app"
            // stays unbounded while "keeps failing" is bounded at three.
            check("...with the failure budget clear, because traffic moved every time",
                    disk.vpnFailures() == 0, "failures " + disk.vpnFailures());
            check("...and the config is still there for the next boot", disk.hasVpnBlob(),
                    "always-on switched itself off");
            check("...having counted every one of them", disk.vpnRestores() == 30,
                    "restores " + disk.vpnRestores());
        });

        // 20. AND THE FAILING CHAIN STILL DIES, EVEN WHEN EVERY ATTEMPT KILLS THE PROCESS
        //     BEFORE AN apply() COULD FLUSH. This is the pairing for case 19: what bounds
        //     the loop is failure, so failure has to be recorded durably.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            disk.losingApplies();
            int last = 0;
            for (int i = 0; i < 3; i++) {
                TestVpn s = new TestVpn(disk);
                last = stickyRestart(s);
                disk.processDied();   // the config took :RunSoLibV2RayDaemon down with it
            }
            check("three process-killing restores are allowed",
                    last == android.app.Service.START_STICKY, "returned " + last);
            TestVpn fourth = new TestVpn(disk);
            int r = stickyRestart(fourth);
            disk.processDied();
            check("the fourth is refused even though no apply() ever flushed",
                    r == android.app.Service.START_NOT_STICKY, "returned " + r);
            check("and the blob is gone from disk, not just from memory",
                    !disk.hasVpnBlob(), "still stored");
        });

        // 21. ⚠ THE STOP PATH RE-ENTERS THE SERVICE, and the stub used to hide that. The
        //     real V2rayCoreManager.stopCore() ends in v2rayServicesListener.stopService(),
        //     and V2rayVPNService.stopService() IS stopAllProcess(), which calls stopCore()
        //     - so every "the core is stopped" assertion in this file runs through a cycle.
        //     It terminates for one reason: stopLoop() clears the running flag before the
        //     callback, so the second stopCore() takes the "not running" branch. The stub
        //     models that exactly; these numbers are what pins it.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            userStart(s, config(null));
            V2rayCoreManager.stopCoreCalls = 0;
            V2rayCoreManager.stopServiceCallbacks = 0;

            s.stopService(); // == stopAllProcess(), what the core calls back into

            check("stopping re-enters the service exactly once",
                    V2rayCoreManager.stopServiceCallbacks == 1,
                    "callbacks " + V2rayCoreManager.stopServiceCallbacks);
            check("the stop cycle is two deep and terminates",
                    V2rayCoreManager.stopCoreCalls == 2, "stopCore calls " + V2rayCoreManager.stopCoreCalls);
            check("the core ends up stopped", !V2rayCoreManager.coreRunning, "still running");
            check("the service ends up stopped", s.stopSelfCalled, "still alive");
        });

        // 22. ⚠⚠ THE BLACK HOLE. THIS IS THE CASE THE ROUND-4 FIX EXISTS FOR.
        //     Round 3 made the restore chain unbounded, correctly, to stop always-on from
        //     switching itself off. The signal it rested on was `builder.establish() !=
        //     null`, and a BLACK-HOLED ENTRY IP satisfies it completely: the tun
        //     establishes, the core starts, the handshake completes, and zero bytes come
        //     back. That is not hypothetical - it is 85.189.101.44 on this fleet, 0 KB/s
        //     on every transport and both engines while the node looked healthy from
        //     outside, and reality-on-.89, where the CDN connects and the volume is 0.
        //     With the budget cleared on establish(), such a device restored a dead
        //     tunnel on every boot forever; with the kill switch on the user then has no
        //     connectivity AND no signal.
        //
        //     ⚠ REVERTING setup()'s startRestoreProofWatcher() TO
        //     AutoStartStore.noteTunnelCarriedTraffic(this, SLOT_VPN) MAKES THIS RED.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));

            // Every restore from here on gets a perfect tun and a running core, and the
            // downlink counter never moves.
            VpnService.establishResult = new ParcelFileDescriptor();
            V2rayCoreManager.totalDownloadBytes = 0L;

            int last = 0;
            for (int i = 0; i < 3; i++) {
                TestVpn s = new TestVpn(disk);
                last = stickyRestart(s);
                s.startService();
                check("black hole: a tun with no traffic is not a proved restore",
                        !proofTick(s), "the proof passed on zero downlink bytes");
            }
            check("black hole: three restores are allowed", last == android.app.Service.START_STICKY,
                    "returned " + last);
            check("black hole: every one of them stayed charged", disk.vpnFailures() == 3,
                    "failures " + disk.vpnFailures());

            TestVpn fourth = new TestVpn(disk);
            int r = stickyRestart(fourth);
            check("black hole: the chain ENDS instead of restoring forever",
                    r == android.app.Service.START_NOT_STICKY, "returned " + r);
            check("black hole: the config is dropped, so the app must re-arm it",
                    !disk.hasVpnBlob(), "still stored");
        });

        // 23. THE PROOF IS ACTUALLY WIRED TO A WATCHER, not only to a method the harness
        //     can call. setup() must arm a DAEMON thread that clears the budget on its
        //     own once bytes move - there is no app process on this path to do it, and a
        //     non-daemon thread would hold :RunSoLibV2RayDaemon up.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));

            TestVpn s = new TestVpn(disk);
            stickyRestart(s);
            check("watcher: the attempt is charged", disk.vpnFailures() == 1,
                    "failures " + disk.vpnFailures());

            // Poll fast enough to observe inside a bounded run, and keep the service
            // "running" so the watcher does not exit before it ticks.
            privSet(null, V2rayVPNService.class, "PROOF_POLL_MS", 5L);
            privSet(s, V2rayVPNService.class, "isRunning", true);
            V2rayCoreManager.totalDownloadBytes = 0L;
            java.util.List<Thread> before = liveThreads("RestoreProof_Thread");
            call(s, "startRestoreProofWatcher", new Class<?>[] {});
            java.util.List<Thread> after = liveThreads("RestoreProof_Thread");
            after.removeAll(before);
            Thread w = after.isEmpty() ? null : after.get(0);

            check("watcher: setup() arms a proof watcher", w != null, "no such thread");
            check("watcher: it is a daemon, so it cannot hold the daemon process up",
                    w == null || w.isDaemon(), "non-daemon");
            check("watcher: it does not clear the budget while nothing moves",
                    disk.vpnFailures() == 1, "failures " + disk.vpnFailures());

            V2rayCoreManager.totalDownloadBytes = PROOF_BYTES;
            check("watcher: it clears the budget on its own once downlink bytes move",
                    waitFor(3000L, () -> disk.vpnFailures() == 0),
                    "failures " + disk.vpnFailures());
            privSet(s, V2rayVPNService.class, "isRunning", false);
            privSet(null, V2rayVPNService.class, "PROOF_POLL_MS", 5000L);

            // ⚠ AND THE COUNTER HAS TO BE RUNNING FOR ANY OF THAT TO MEAN ANYTHING.
            // V2rayCoreManager only polls queryStats when the running config asks for
            // traffic statistics, so a restored session with the flag off could never
            // prove itself and would burn its failure budget while working perfectly.
            resetWorld();
            Disk quiet = new Disk();
            V2rayConfig noStats = config(null);
            noStats.ENABLE_TRAFFIC_STATICS = false;
            userStart(new TestVpn(quiet), noStats);
            stickyRestart(new TestVpn(quiet));
            check("a restore turns the traffic counters on, or the proof can never happen",
                    V2rayCoreManager.lastConfig instanceof V2rayConfig
                            && ((V2rayConfig) V2rayCoreManager.lastConfig).ENABLE_TRAFFIC_STATICS,
                    "the restored config was started with statistics off");
        });

        // 24. ⚠ stopCleanly() AT THE VPN CALL SITES THE SUITE NEVER REACHED. Restoring
        //     the pre-fix pair "this.onDestroy(); return START_NOT_STICKY;" at three
        //     separate sites at once left the round-3 suite at 146/0 while a probe showed
        //     the service alive on all three paths. onDestroy() runs the CLEANUP; only
        //     stopSelf() ends the SERVICE, and after a START_STICKY the framework brings
        //     back whatever is still alive.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            s.onCreate();
            int r = s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, null), 0, 1);
            check("null V2RAY_CONFIG: START_NOT_STICKY", r == android.app.Service.START_NOT_STICKY,
                    "returned " + r);
            check("null V2RAY_CONFIG: the service is stopped, not just cleaned up",
                    s.stopSelfCalled, "onDestroy-style cleanup left the service alive");
            check("null V2RAY_CONFIG: nothing is persisted", !disk.hasVpnBlob(), "stored");
        });
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            V2rayCoreManager.startCoreResult = false;
            TestVpn s = new TestVpn(disk);
            s.onCreate();
            int r = s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, config(null)), 0, 1);
            check("core refused to start: START_NOT_STICKY", r == android.app.Service.START_NOT_STICKY,
                    "returned " + r);
            check("core refused to start: the service is stopped for real", s.stopSelfCalled,
                    "service left alive");
            check("core refused to start: a config that never started is not persisted",
                    !disk.hasVpnBlob(), "stored");
        });
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            V2rayCoreManager.startCoreResult = false;
            TestVpn s = new TestVpn(disk);
            int r = stickyRestart(s);
            check("restored config did not start the core: START_NOT_STICKY",
                    r == android.app.Service.START_NOT_STICKY, "returned " + r);
            check("restored config did not start the core: the service stops for real",
                    s.stopSelfCalled, "service left alive");
            check("restored config did not start the core: the attempt stays charged",
                    disk.vpnFailures() == 1, "failures " + disk.vpnFailures());
        });

        // 25. ⚠ THE PRECONDITION FOR THIS ENTIRE STREAM, AND NOTHING ASSERTED IT FOR THE
        //     VPN SERVICE. Every restore case above is worth nothing if a user-initiated
        //     START returns START_NOT_STICKY, because then the framework never restarts
        //     the service and there is no sticky restart to answer.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            s.onCreate();
            int r = s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, config(null)), 0, 1);
            check("a user-initiated START returns START_STICKY, or nothing ever restarts",
                    r == android.app.Service.START_STICKY, "returned " + r);
            int r2 = s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.MEASURE_DELAY, null), 0, 2);
            check("MEASURE_DELAY does not change the restart contract",
                    r2 == android.app.Service.START_STICKY, "returned " + r2);
        });
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestProxy s = new TestProxy(disk);
            s.onCreate();
            int r = s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, config(null)), 0, 1);
            check("proxy: a user-initiated START returns START_STICKY too",
                    r == android.app.Service.START_STICKY, "returned " + r);
        });

        // 26. ⚠ THE PROXY SLOT HOLDS THE SAME CREDENTIAL-BEARING BLOB AS THE VPN SLOT.
        //     Deleting AutoStartStore.clear(this, SLOT_PROXY) from the proxy's
        //     STOP_SERVICE branch left the round-3 suite green, so a config the user
        //     explicitly stopped could be replayed by the next system start.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestProxy s = new TestProxy(disk);
            s.onCreate();
            s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, config(null)), 0, 1);
            check("proxy: armed before the stop", disk.hasProxyBlob(), "nothing stored");
            s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE, null), 0, 2);
            check("proxy: STOP_SERVICE clears the credential-bearing blob",
                    !disk.hasProxyBlob(), "blob survived the stop");

            TestProxy after = new TestProxy(disk);
            V2rayCoreManager.coreRunning = false;
            after.onCreate();
            int r = after.onStartCommand(null, 0, 3);
            check("proxy: nothing restores after an explicit stop",
                    r == android.app.Service.START_NOT_STICKY, "returned " + r);
            check("proxy: and the service stops for real", after.stopSelfCalled, "left alive");
        });

        // 27. THE PROXY'S OWN stopCleanly() AND RESTORE GUARDS. Same three shapes as the
        //     VPN service, in the class that was copied from it.
        run(() -> {
            resetWorld();
            TestProxy s = new TestProxy(new Disk());
            s.onCreate();
            int r = s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, null), 0, 1);
            check("proxy: null V2RAY_CONFIG stops the service for real",
                    r == android.app.Service.START_NOT_STICKY && s.stopSelfCalled,
                    "returned " + r + ", stopSelf " + s.stopSelfCalled);
        });
        run(() -> {
            resetWorld();
            V2rayCoreManager.startCoreResult = false;
            TestProxy s = new TestProxy(new Disk());
            s.onCreate();
            int r = s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, config(null)), 0, 1);
            check("proxy: a core that refuses to start stops the service for real",
                    r == android.app.Service.START_NOT_STICKY && s.stopSelfCalled,
                    "returned " + r + ", stopSelf " + s.stopSelfCalled);
        });
        run(() -> {
            resetWorld();
            TestProxy s = new TestProxy(new Disk());
            s.onCreate();
            int r = s.onStartCommand(null, 0, 1);
            check("proxy: nothing armed stops the service for real",
                    r == android.app.Service.START_NOT_STICKY && s.stopSelfCalled,
                    "returned " + r + ", stopSelf " + s.stopSelfCalled);
        });
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestProxy armed = new TestProxy(disk);
            armed.onCreate();
            armed.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, config(null)), 0, 1);
            V2rayCoreManager.startCoreResult = false;
            V2rayCoreManager.coreRunning = false;
            TestProxy s = new TestProxy(disk);
            s.onCreate();
            int r = s.onStartCommand(null, 0, 2);
            check("proxy: a restored config that will not start stops the service for real",
                    r == android.app.Service.START_NOT_STICKY && s.stopSelfCalled,
                    "returned " + r + ", stopSelf " + s.stopSelfCalled);
        });
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestProxy armed = new TestProxy(disk);
            armed.onCreate();
            armed.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, config(null)), 0, 1);
            int before = V2rayCoreManager.startCoreCalls;
            V2rayCoreManager.coreRunning = true;
            TestProxy s = new TestProxy(disk);
            s.onCreate();
            int r = s.onStartCommand(null, 0, 2);
            check("proxy: a restart while the core runs does not rebuild it",
                    r == android.app.Service.START_STICKY
                            && V2rayCoreManager.startCoreCalls == before,
                    "returned " + r + ", startCore " + V2rayCoreManager.startCoreCalls);
        });
        // 28. ⚠ mInterface = null IN stopAllProcess() IS THE ONLY PRODUCTION LINE THAT
        //     PRODUCES THE NULL the round-3 sendFileDescriptor() guard guards against.
        //     Deleting it left the round-3 suite green: a closed-but-non-null descriptor
        //     is still reachable by the sendFd thread, which then hands a dead fd to
        //     tun2socks instead of taking the "no tun" branch. close() is not enough.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            TestVpn s = new TestVpn(disk);
            stickyRestart(s);
            ParcelFileDescriptor tun = new ParcelFileDescriptor();
            VpnService.establishResult = tun;
            s.startService();   // establishes, then fails to exec tun2socks => stopAllProcess()
            check("teardown closes the tun interface", tun.closed, "still open");
            check("teardown NULLS the tun interface, it does not just close it",
                    priv(s, V2rayVPNService.class, "mInterface") == null,
                    "a closed descriptor is still reachable by the tun2socks watcher");
        });

        // 29. ⚠ isRunning = false IN stopAllProcess() IS THE ONLY THING THAT ENDS THE
        //     Tun2socks_Thread RESPAWN LOOP. That loop is `while (isRunning) { exec;
        //     waitFor; }`, so without the assignment a teardown leaves a thread respawning
        //     tun2socks forever in the daemon process. Deleting it left the round-3 suite
        //     green because nothing ever let runTun2socks() exec a real process.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            java.io.File libs;
            try {
                libs = Tun2socks.fakeLibDir();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            userStart(new TestVpn(disk), config(null));
            TestVpn s = new TestVpn(disk).withNativeLibs(libs.getAbsolutePath());
            stickyRestart(s);
            VpnService.establishResult = new ParcelFileDescriptor();
            s.startService();   // this time the binary EXISTS, so the loop really runs

            int seen = Tun2socks.execCount();
            check("the watcher respawns tun2socks while the tunnel is up",
                    waitFor(3000L, () -> Tun2socks.execCount() > seen + 1),
                    "execs " + Tun2socks.execCount());

            call(s, "stopAllProcess", new Class<?>[] {});
            int atStop = Tun2socks.execCount();
            // Give a loop that ignored the flag time to prove it: several poll intervals.
            waitFor(700L, () -> false);
            check("a teardown ends the respawn loop instead of running it forever",
                    Tun2socks.execCount() <= atStop + 1,
                    "execs " + atStop + " -> " + Tun2socks.execCount());
        });

        // 30. ⚠ THE NOTIFICATION'S STOP BUTTON, WHICH IS WHAT EVERY
        //     AppConfigs.V2RAY_CONNECTION_MODE ASSIGNMENT EXISTS FOR. The adversary was
        //     right that those two assignments were a tested no-op: showNotification()
        //     computed a stopIntent from the mode, wrapped it in a PendingIntent, and then
        //     attached the OTHER PendingIntent - the launcher one - to the Disconnect
        //     action. So the button opened the app and disconnected nothing, on both
        //     services, for every user. The decision taken was WIRE IT, not delete it.
        //
        //     ⚠ THIS IS A SOURCE-SHAPE ASSERTION. showNotification() needs a real
        //     NotificationManager and a real PendingIntent, neither of which exists in a
        //     JVM; what can be held here is that the wiring is not silently undone.
        run(() -> {
            String src;
            try {
                src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                        "../android/src/main/java/dev/amirzr/flutter_v2ray_client/v2ray/core/V2rayCoreManager.java")),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            check("the connection mode still selects the stop intent's target service",
                    src.contains("stopIntent = new Intent(context, V2rayProxyOnlyService.class)")
                            && src.contains("stopIntent = new Intent(context, V2rayVPNService.class)"),
                    "showNotification no longer branches on the mode");
            check("the disconnect action carries the STOP_SERVICE PendingIntent, not the launcher",
                    src.contains(".addAction(0, v2rayConfig.NOTIFICATION_DISCONNECT_BUTTON_NAME, pendingIntent)"),
                    "the mode assignments are a no-op again");
            check("the daemon exposes the downlink counter the restore chain is bound on",
                    src.contains("public long getTotalDownloadBytes()"), "signal gone");
        });

        // 31. ⚠ THE THREE HOLES A ROUND-4 MUTATION RUN FOUND STILL GREEN after everything
        //     above was written. Each was reverted one line at a time and the suite did
        //     not move. They are here so that cannot happen again.

        // 31a. THE ONE stopCleanly() CALL SITE NO TEST CAN REACH, GUARDED BY SHAPE.
        //      `else { return stopCleanly("unknown command received"); }` is the default
        //      branch of a 3-value enum whose three values are all handled above it, so
        //      no intent this harness can build reaches it. It is still shipping code,
        //      and it is still the branch a fourth enum value falls into on the day
        //      somebody adds one.
        //
        //      ⚠ THIS IS A SOURCE-SHAPE ASSERTION, NOT A BEHAVIOURAL ONE. It cannot tell
        //      you the service stops; the cases above do that for every reachable site.
        //      What it does is make the PRE-FIX PAIR itself unspellable anywhere in
        //      either service class - including in branches no test can drive - which is
        //      the only way to hold a line that is unreachable by construction.
        run(() -> {
            String[] files = {
                "../android/src/main/java/dev/amirzr/flutter_v2ray_client/v2ray/services/V2rayVPNService.java",
                "../android/src/main/java/dev/amirzr/flutter_v2ray_client/v2ray/services/V2rayProxyOnlyService.java",
            };
            for (String f : files) {
                String src;
                try {
                    src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(f)),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                String name = f.substring(f.lastIndexOf('/') + 1);
                // Comments in these files quote the pre-fix pair on purpose (that is how
                // the defect is documented), so only CODE counts: strip // and /* */.
                String code = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
                check(name + ": no self-destruct stop survives anywhere in the class",
                        !code.contains("this.onDestroy()"),
                        "the round-2 pre-fix pair is back in the source");
                check(name + ": the unknown-command branch still stops cleanly",
                        code.contains("stopCleanly(\"unknown command received\")"),
                        "the one call site no test can reach lost its stopCleanly()");
            }
        });

        // 31b. prepare() CAN THROW, NOT ONLY RETURN NON-NULL. It is a binder call; a dead
        //      system_server or an OEM policy hook surfaces as a RuntimeException here.
        //      The catch branch must stop the service: falling through would start a core
        //      with no tun, which is the shape where traffic leaves in clear. Reverting
        //      that stopCleanly() to `return START_STICKY` left the suite green because
        //      nothing ever made prepare() throw.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));
            VpnService.prepareThrows = true;

            TestVpn s = new TestVpn(disk);
            int r = stickyRestart(s);
            check("prepare() that THROWS: START_NOT_STICKY", r == android.app.Service.START_NOT_STICKY,
                    "returned " + r);
            check("prepare() that THROWS: the service is stopped for real", s.stopSelfCalled,
                    "left alive with no tun");
            check("prepare() that THROWS: the core is never started",
                    V2rayCoreManager.startCoreCalls == 1,
                    "startCore calls " + V2rayCoreManager.startCoreCalls);
            check("prepare() that THROWS: the budget is not charged for a broken framework",
                    disk.vpnFailures() == 0, "failures " + disk.vpnFailures());
            check("prepare() that THROWS: the config is kept for later", disk.hasVpnBlob(),
                    "dropped");
            VpnService.prepareThrows = false;
        });

        // 31c. THE WATCHER'S GENERATION GUARD. One process can build a tun, tear it down
        //      and build another. Without tunGeneration.incrementAndGet() in
        //      startRestoreProofWatcher(), the FIRST tunnel's watcher is still running
        //      when the SECOND tunnel's bytes start moving, and it clears the failure
        //      budget for a tunnel it never measured - handing back exactly the "any
        //      traffic anywhere proves any restore" semantics the round-4 fix removed.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            userStart(new TestVpn(disk), config(null));

            TestVpn s = new TestVpn(disk);
            stickyRestart(s);
            privSet(null, V2rayVPNService.class, "PROOF_POLL_MS", 5L);
            privSet(s, V2rayVPNService.class, "isRunning", true);
            V2rayCoreManager.totalDownloadBytes = 0L;

            java.util.List<Thread> before = liveThreads("RestoreProof_Thread");
            call(s, "startRestoreProofWatcher", new Class<?>[] {});
            java.util.List<Thread> after = liveThreads("RestoreProof_Thread");
            after.removeAll(before);
            final Thread first = after.isEmpty() ? null : after.get(0);
            check("generation: the first tunnel's watcher is running", first != null,
                    "no new watcher thread appeared");

            call(s, "startRestoreProofWatcher", new Class<?>[] {});
            check("generation: arming a second watcher retires the first",
                    waitFor(3000L, () -> first != null && !first.isAlive()),
                    "the stale watcher is still crediting the newer tunnel's bytes");

            privSet(s, V2rayVPNService.class, "isRunning", false);
            privSet(null, V2rayVPNService.class, "PROOF_POLL_MS", 5000L);
        });

        // ── 32. THE COMMAND LINE THE REAL SERVICE WILL EXEC ─────────────────────────
        // ⚠ THE WIRING, NOT THE HELPER. Tun2socksHarness proves Tun2socksArgs.build()
        // picks the right flag; this proves the SERVICE hands it the mode that arrived
        // in the config, off the real private field, on a real instance that has been
        // started the way Android starts it. Point `runTun2socks` back at a hardcoded
        // vector — the shape that shipped and broke every datagram on Android — and
        // these three go red while every other assertion in this file stays green,
        // which is exactly how the defect survived until now.
        run(() -> {
            Disk d = new Disk();
            TestVpn s = new TestVpn(d).withNativeLibs("/data/app/doft/lib/arm64");
            V2rayConfig cfg = config(null);
            cfg.LOCAL_SOCKS5_PORT = 10807;
            cfg.TUN2SOCKS_UDP_MODE = "socks5";
            userStart(s, cfg);
            java.util.List<String> cmd = s.tun2socksCommand();
            check("service: the tun2socks vector asks for a real SOCKS5 UDP ASSOCIATE",
                    cmd.contains("--socks5-udp") && !cmd.contains("--enable-udprelay"),
                    String.join(" ", cmd));
            check("service: the socks port comes from the config, not a constant",
                    cmd.contains("127.0.0.1:10807"), String.join(" ", cmd));
            check("service: the binary is taken from nativeLibraryDir",
                    cmd.get(0).equals("/data/app/doft/lib/arm64/libtun2socks.so"), cmd.get(0));
        });

        // 33. And the escape hatch reaches the process, or it is not an escape hatch.
        run(() -> {
            Disk d = new Disk();
            TestVpn s = new TestVpn(d).withNativeLibs("/data/app/doft/lib/arm64");
            V2rayConfig cfg = config(null);
            cfg.TUN2SOCKS_UDP_MODE = "udpgw";
            userStart(s, cfg);
            java.util.List<String> cmd = s.tun2socksCommand();
            check("service: `udpgw` in the config actually reaches the command line",
                    cmd.contains("--enable-udprelay") && !cmd.contains("--socks5-udp"),
                    String.join(" ", cmd));
        });

        // ── 34. THE TUNNEL'S OWN PROCESS WATCHES THE NETWORK ────────────────────────
        // ⚠ THE POINT IS THE PROCESS, NOT THE CALLBACK. Until 2026-08-21 the only
        // NetworkCallback in the whole tree lived in the Flutter Activity: a different
        // process, torn down the moment the app is backgrounded, and absent entirely on an
        // always-on or sticky start where there is no Activity at all. So a Wi-Fi -> LTE
        // handover happened with NOTHING in the tunnel's process noticing, and
        // setUnderlyingNetworks was never called even once. These assertions are about the
        // service doing it itself.
        run(() -> {
            if (android.net.ConnectivityManager.last != null) {
                android.net.ConnectivityManager.last.reset();
            }
            android.net.VpnService.declaredUnderlying = null;
            android.net.VpnService.declareCalls = 0;
            Disk d = new Disk();
            TestVpn s = new TestVpn(d);
            // A real tun, or setup() bails at `establish() == null` long before it ever
            // reaches the watcher — and the assertion below would be about the fixture.
            VpnService.establishResult = new ParcelFileDescriptor();
            userStart(s, config(null));
            s.startService(); // what xray's startup() callback does: runs setup()

            android.net.ConnectivityManager cm = android.net.ConnectivityManager.last;
            // ⚠ ASSERT ON THE REGISTER, NOT ON A LIVE REGISTRATION. In a test JVM
            // tun2socks cannot exec, so setup() registers and then stops the service in the
            // same breath — which unregisters. That the service DID it from its own process
            // is the property; `lastRegisteredCallback` is what the harness drives.
            check("service: the tunnel's own process registers a network callback",
                    cm != null && cm.registerCalls > 0 && cm.lastRegisteredCallback != null,
                    cm == null ? "no ConnectivityManager asked for" : "registers=" + cm.registerCalls);
            if (cm == null || cm.lastRegisteredCallback == null) return;

            // ⚠ THE REQUEST MUST NAME BOTH CAPABILITIES. Without NOT_VPN the framework may
            // offer our OWN tunnel as the network underneath it, which is a loop.
            check("service: the request asks for INTERNET and NOT_VPN",
                    cm.registeredRequest != null
                            && cm.registeredRequest.required.contains(
                                    android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && cm.registeredRequest.required.contains(
                                    android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                    String.valueOf(cm.registeredRequest == null ? null : cm.registeredRequest.required));

            // ⚠ THE CALLBACK REFUSES TO ACT ON A TUNNEL THAT IS GONE, which is right in
            // production and is exactly what a test JVM produces: tun2socks cannot exec, so
            // setup() stopped the service. Put the flag back so the assertions below are
            // about the DECLARATION rule and not about that.
            privSet(s, V2rayVPNService.class, "isRunning", true);
            android.net.Network wifi = new android.net.Network(1);
            android.net.NetworkCapabilities good = new android.net.NetworkCapabilities()
                    .add(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .add(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .add(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            cm.lastRegisteredCallback.onCapabilitiesChanged(wifi, good);
            check("service: a validated network is declared as the carrier",
                    android.net.VpnService.declaredUnderlying != null
                            && android.net.VpnService.declaredUnderlying.length == 1
                            && android.net.VpnService.declaredUnderlying[0] == wifi,
                    "declareCalls=" + android.net.VpnService.declareCalls);

            // The captive-portal shape: joined, routable, reaching nothing. It must not
            // displace a good declaration.
            int before = android.net.VpnService.declareCalls;
            android.net.NetworkCapabilities unvalidated = new android.net.NetworkCapabilities()
                    .add(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .add(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
            cm.lastRegisteredCallback.onCapabilitiesChanged(new android.net.Network(2), unvalidated);
            check("service: an UNVALIDATED network never displaces the declaration",
                    android.net.VpnService.declareCalls == before
                            && android.net.VpnService.declaredUnderlying[0] == wifi, "");

            // ⚠ A LOSS CLEARS TO null — "follow the system default" — and never promotes a
            // guess. The callback reporting the loss does not know what replaced it.
            cm.lastRegisteredCallback.onLost(wifi);
            check("service: a lost network clears the declaration to null",
                    android.net.VpnService.declaredUnderlying == null, "");

            // ⚠ AND THE CALLBACK MUST NOT OUTLIVE THE TUNNEL. The framework holds the
            // reference, so a stop that skipped the unregister would leak one per connect
            // and keep declaring for a tunnel that no longer exists.
            s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE, null), 0, 2);
            joinLane(s);
            check("service: stopping unregisters the callback",
                    cm.unregisterCalls > 0, "unregister=" + cm.unregisterCalls);
        });

        // 40. THE TEARDOWN THAT FROZE THE DAEMON.
        //
        //     Reported 2026-08-31: Disconnect does nothing for ten taps, then "the app is
        //     not responding"; Connect afterwards reports connected with no internet and
        //     "— KB/s" where the counters belong. The service lives in
        //     :RunSoLibV2RayDaemon and every stop ran WHOLE on that process's main thread
        //     — stopLoop() into Go, stopTuic(), process.destroy(), mInterface.close() —
        //     while three things needed that same looper: the 1 Hz stats tick, the NEXT
        //     onStartCommand, and the ANR watchdog.
        //
        //     ⚠ THE OTHER 134 SERVICE CHECKS PASS WITH THE TEARDOWN ON EITHER THREAD.
        //     They assert on state the stop leaves behind, and a fast stub leaves it
        //     behind wherever it runs. Nothing below asks about state; each asks WHEN.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            userStart(s, config(null));

            // ⚠ A BARRIER QUEUED FIRST, NOT A GATE INSIDE stopCore — see wedgeLane. A
            // gate inside the teardown lets anything a regression moved onto the lane
            // AHEAD of stopCore run before the checks look, and a mutant that deferred
            // this branch's own bookkeeping survived a suite built that way.
            java.util.concurrent.CountDownLatch open = wedgeLane(s);
            int r = s.onStartCommand(
                    command(AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE, null), 0, 2);

            check("a stop RETURNS before its teardown has even started",
                    !V2rayCoreManager.stopCoreEntered, "the teardown ran on this thread");
            check("a stop still answers START_STICKY",
                    r == android.app.Service.START_STICKY, "returned " + r);
            // ⚠ AND THE BOOKKEEPING IS STILL SYNCHRONOUS. "An explicit stop is final"
            // rests on the slot being clear the instant we return; deferring it onto the
            // lane leaves a window in which a kill resurrects the tunnel the user just
            // switched off.
            check("a stop clears the auto-start slot BEFORE it returns",
                    !disk.hasVpnBlob(), "blob survived — the clear went onto the lane");

            open.countDown();
            check("the lane drains", joinLane(s), "still busy");
            check("and the teardown really happened", V2rayCoreManager.laneStopFinished,
                    "the lane never finished it");
            check("it ran on the lane, not on the caller",
                    "v2ray-teardown".equals(V2rayCoreManager.stopCoreThread),
                    "ran on " + V2rayCoreManager.stopCoreThread);
        });

        // 41. A START WAITS FOR AN IN-FLIGHT TEARDOWN. IT DOES NOT REFUSE, AND IT DOES NOT
        //     WALK INTO ONE.
        //
        //     ⚠⚠ THE FIRST VERSION REFUSED, AND THAT WAS WORSE THAN THE BUG. It waited
        //     4 s and answered START_NOT_STICKY, on the reasoning that "the dial ladder
        //     treats a start that did not reach connected as a failed rung". False, and
        //     the code that makes it false is in this repo: FlutterV2rayPlugin answers
        //     result.success(null) for startV2Ray unconditionally, so a refusal never
        //     reaches Dart as an error; the ladder's escalation is in its catch arm only.
        //     A refused start therefore burns the app's 30 s connect watchdog and lands on
        //     a 1-minute backoff against the SAME node. And since every bridge.start()
        //     sends a stop and sleeps 400 ms first, on any device whose stop takes longer
        //     than ~4.4 s — the devices that HAVE this bug — every reconnect would have
        //     been refused.
        //
        //     ⚠ AND THE OTHER HALF: it must not start into one either. stopAllProcess()
        //     ends in mInterface.close(), mInterface = null and stopSelf(), so a start
        //     that raced it builds a tun the teardown then closes and a service the
        //     teardown then stops.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            userStart(s, config(null));
            int startsBefore = V2rayCoreManager.startCoreCalls;
            int offLaneBefore = V2rayCoreManager.stopCoreCallsOffLane;

            java.util.concurrent.CountDownLatch open = wedgeLane(s);
            s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE, null), 0, 2);

            // The start has to run somewhere that can BLOCK, so it goes on its own thread
            // and this one observes it.
            final int[] answer = { Integer.MIN_VALUE };
            final java.util.concurrent.CountDownLatch started =
                    new java.util.concurrent.CountDownLatch(1);
            Thread dial = new Thread(() -> {
                answer[0] = s.onStartCommand(
                        command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, config(null)),
                        0, 3);
                started.countDown();
            }, "harness-start");
            dial.setDaemon(true);
            dial.start();

            // Long enough that a start which did NOT wait would be well past startCore.
            boolean answeredEarly;
            try {
                answeredEarly = started.await(400L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                answeredEarly = false;
            }
            check("a start does not answer while a teardown is in flight",
                    !answeredEarly, "onStartCommand returned " + answer[0] + " early");
            check("and it starts no core into one",
                    V2rayCoreManager.startCoreCalls == startsBefore,
                    "startCore ran " + (V2rayCoreManager.startCoreCalls - startsBefore) + " time(s)");
            // ⚠⚠ AND IT HAS NOT TORN ANYTHING DOWN ON ITS OWN THREAD EITHER. A MUTANT
            // SURVIVED BOTH CHECKS ABOVE: moving the inline "replace a running core"
            // stopCore() from AFTER joinTeardown() to BEFORE it leaves the start blocked
            // and startCore unreached — both assertions still green — while the caller's
            // thread has meanwhile run a COMPLETE teardown of the same core, on the
            // daemon's main looper, which is the entire thing this change exists to stop.
            // The two checks above pin only where startCore sits.
            check("and it has not run a teardown on the caller's thread",
                    V2rayCoreManager.stopCoreCallsOffLane == offLaneBefore,
                    "stopCore ran " + (V2rayCoreManager.stopCoreCallsOffLane - offLaneBefore)
                            + " time(s) OFF the lane while the start was waiting");

            open.countDown();
            try {
                dial.join(10000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            check("once the teardown finishes the start goes through",
                    answer[0] == android.app.Service.START_STICKY, "returned " + answer[0]);
            // ⚠ NOT `!s.stopSelfCalled`: the STOP's own teardown ends in stopSelf(), so
            // that flag is set by the thing this case is waiting for, not by a refusal.
            // "It started a core and answered START_STICKY" is the whole of "not refused".
            check("and it is the ORDINARY start, not a refusal",
                    V2rayCoreManager.startCoreCalls == startsBefore + 1,
                    "startCore calls " + (V2rayCoreManager.startCoreCalls - startsBefore));
        });

        // 42. onDestroy() WAITS FOR THE LANE, WITHOUT A BOUND, AND DOES NOT TEAR DOWN TWICE.
        //
        //     ⚠ THE BOUNDED VERSION HAD TWO TEETH. On timeout it went on to call stopCore()
        //     itself while the lane was still inside stopLoop() — a second concurrent
        //     teardown of the same core, on the very thread this change exists to keep
        //     free, in exactly the slow-stop case it exists for. And it then shut the lane
        //     down and returned, so a teardown could OUTLIVE the service: V2rayCoreManager
        //     is a process-wide singleton whose listener onCreate re-points at each new
        //     instance, so the abandoned teardown's stopService() callback would land on
        //     the NEXT service and close a tun a reconnect had just established.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            userStart(s, config(null));

            // The LATE gate: the flag is already clear, so onDestroy's own
            // `if (isV2rayCoreRunning())` cannot satisfy this by doing the work inline —
            // and `laneStopFinished` is set only by the lane thread. Both are needed: an
            // entry gate let a mutant that deleted the wait pass by blocking on the same
            // latch and finishing the teardown itself.
            java.util.concurrent.CountDownLatch late = new java.util.concurrent.CountDownLatch(1);
            V2rayCoreManager.stopCoreLateGate = late;
            s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE, null), 0, 2);
            waitFor(() -> !V2rayCoreManager.coreRunning, "the flag never cleared");

            int offLaneBefore = V2rayCoreManager.stopCoreCallsOffLane;
            final java.util.concurrent.CountDownLatch g = late;
            Thread opener = new Thread(() -> {
                try { Thread.sleep(150L); } catch (InterruptedException ignored) { }
                g.countDown();
            }, "gate-opener");
            opener.setDaemon(true);
            opener.start();

            s.onDestroy();
            check("onDestroy does not return until the lane is quiet",
                    V2rayCoreManager.laneStopFinished,
                    "onDestroy raced a teardown that had not finished");
            check("and it does not run a SECOND teardown of its own",
                    V2rayCoreManager.stopCoreCallsOffLane == offLaneBefore,
                    "stopCore ran " + (V2rayCoreManager.stopCoreCallsOffLane - offLaneBefore)
                            + " extra time(s) OFF the lane");
            V2rayCoreManager.stopCoreLateGate = null;
        });

        // 43. A SYSTEM-INITIATED RESTORE TAKES THE SAME EXCLUSION — AHEAD OF THE
        //     IDEMPOTENCE CHECK, AND WITHOUT PAYING FOR IT.
        //
        //     ⚠ PLACED BELOW `if (isV2rayCoreRunning()) return START_STICKY;` the wait is
        //     unreachable in the case that matters: a teardown parked BEFORE stopLoop()
        //     clears the flag makes that early return fire, the restore answers "already
        //     running, nothing to do", and the lane then kills the core it declined to
        //     rebuild. An always-on device comes back with no tunnel and nothing left to
        //     bring it back.
        //
        //     ⚠ AND ABOVE beginRestoreAttempt(): a teardown we are waiting out is no more
        //     the config's fault than a consent with nobody to ask, and this class already
        //     refuses to charge the budget for that.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            userStart(s, config(null));                       // arms the slot, core running
            int failuresBefore = disk.vpnFailures();
            int startsBefore = V2rayCoreManager.startCoreCalls;

            // Park the teardown at its ENTRY, so the running flag is still SET — the state
            // that makes the early return fire.
            java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
            V2rayCoreManager.stopCoreGate = gate;
            // A REVOKE, not a stop: it tears down without clearing the slot the restore
            // has to find. (A STOP_SERVICE would clear it and the restore would refuse
            // for an unrelated reason, which is how a green test proves nothing.)
            s.onRevoke();
            waitFor(() -> V2rayCoreManager.stopCoreEntered, "the lane never picked it up");
            check("the parked teardown still shows the core as running",
                    V2rayCoreManager.coreRunning, "the flag cleared before the gate");
            check("the revoke left the slot cleared", !disk.hasVpnBlob(), "slot survived");

            // Re-arm a slot for the restore to find, exactly as a previous successful
            // user start would have left it.
            AutoStartStore.save(s, AutoStartStore.SLOT_VPN, config(null));

            final int[] answer = { Integer.MIN_VALUE };
            final java.util.concurrent.CountDownLatch done =
                    new java.util.concurrent.CountDownLatch(1);
            // ⚠ NOT stickyRestart(): that helper zeroes coreRunning and calls onCreate()
            // first, which would erase the very state this case is about — a teardown
            // parked while the flag is STILL SET. The null intent is the sticky-restart
            // shape and is all the restore path reads.
            Thread restore = new Thread(() -> {
                answer[0] = s.onStartCommand(null, 0, 4);
                done.countDown();
            }, "harness-restore");
            restore.setDaemon(true);
            restore.start();

            boolean early;
            try {
                early = done.await(400L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                early = false;
            }
            check("a restore does not answer while a teardown is in flight",
                    !early, "returned " + answer[0] + " without waiting");

            gate.countDown();
            V2rayCoreManager.stopCoreGate = null;
            try {
                restore.join(10000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            check("and once it drains the restore rebuilds the tunnel",
                    V2rayCoreManager.startCoreCalls == startsBefore + 1,
                    "startCore ran " + (V2rayCoreManager.startCoreCalls - startsBefore) + " time(s)");
            // ⚠ THE BUDGET IS NOT THE ASSERTION HERE, AND MY FIRST VERSION SAID IT WAS.
            // A restore that goes AHEAD spends an attempt on purpose — beginRestoreAttempt
            // charges, and setup() clears it once a tun exists. There is nothing left on
            // this path that can refuse, so "the wait was not charged" is only meaningful
            // as "the wait happens before the charge", which is what the ordering in
            // restoreLastKnownGood says and what mutating that ordering has to break.
            check("the restore charged exactly one attempt, as a real restore does",
                    disk.vpnFailures() == failuresBefore + 1,
                    "budget went " + failuresBefore + " -> " + disk.vpnFailures());
        });

        // 44. onRevoke() IS A MAIN-THREAD FRAMEWORK CALLBACK TOO.
        //
        //     The other way the user says no, and it ran the whole of stopAllProcess() —
        //     process.destroy(), mInterface.close(), stopCore() — on the thread the
        //     framework called it on. Case 16 proves the EFFECT; this proves the timing,
        //     which case 16 cannot see because it joins first.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            userStart(s, config(null));

            java.util.concurrent.CountDownLatch open = wedgeLane(s);
            s.onRevoke();
            check("onRevoke RETURNS before its teardown has even started",
                    !V2rayCoreManager.stopCoreEntered, "the teardown ran inline");
            // The consent bookkeeping is synchronous for the same reason the stop's is:
            // nothing may restore a tunnel whose consent has just gone, and between the
            // return and the lane there is a window in which a kill would.
            check("onRevoke clears the slot before it returns", !disk.hasVpnBlob(),
                    "blob survived — the clear was deferred onto the lane");

            open.countDown();
            joinLane(s);
            check("and the revoke's teardown still happens", V2rayCoreManager.laneStopFinished,
                    "the lane never finished it");
        });

        // 45. A LANE THAT CANNOT ACCEPT WORK MUST NOT DROP IT.
        //
        //     onDestroy() shuts the lane down. Anything arriving afterwards is rejected,
        //     and a rejection handled by logging is a tunnel left up with the app believing
        //     it is down — strictly worse than the synchronous teardown this replaced. The
        //     fallback runs it inline, i.e. degrades to exactly what shipped before.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            userStart(s, config(null));
            s.onDestroy();                        // shuts the lane down

            V2rayCoreManager.coreRunning = true;  // a core is up again
            V2rayCoreManager.stopCoreCalls = 0;
            V2rayCoreManager.stopCoreFinished = false;
            s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE, null), 0, 3);
            // TWO, not one — the documented two-deep recursion the stub models and the
            // real core has: stopCore() -> stopService() -> stopAllProcess() -> stopCore(),
            // whose second call takes the "not running" branch.
            check("a shut-down lane runs the teardown inline instead of dropping it",
                    V2rayCoreManager.stopCoreCalls == 2,
                    "stopCore ran " + V2rayCoreManager.stopCoreCalls + " time(s)");
            check("and the core really is stopped", !V2rayCoreManager.coreRunning,
                    "a core is still running after the user stopped it");
        });

        // 46. AND joinTeardown() SAYS SO WHEN IT CANNOT JOIN.
        //
        //     ⚠ IT USED TO ANSWER `true` ON A REJECTED SUBMIT, under the comment "nothing
        //     can be queued, so nothing is in flight to wait for". shutdown() is an
        //     ORDERLY shutdown: it refuses NEW work and lets what is already running run
        //     on. So the one honest answer — "I could not establish the exclusion" — was
        //     reported as "the exclusion holds", and every caller after a shutdown
        //     silently lost it.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            userStart(s, config(null));
            check("the lane joins while it is alive", joinLane(s), "could not join a live lane");
            s.onDestroy();
            check("and answers FALSE once it cannot be joined at all", !joinLane(s),
                    "claimed the exclusion held on a shut-down lane");
        });

        // 41b. THE NEW CONFIG IS NOT PUBLISHED WHILE THE OLD TUNNEL IS STILL COMING DOWN.
        //
        //      ⚠ A WINDOW THE LANE CREATED, WHICH IS THE ONLY KIND THAT COUNTS AS A
        //      REGRESSION HERE. The tun2socks watcher thread re-enters runTun2socks() when
        //      the binary exits, and reads v2rayConfig.LOCAL_SOCKS5_PORT and
        //      TUN2SOCKS_UDP_MODE — while belonging to the tunnel being torn down. With
        //      the teardown ahead of this method on the same thread, the field could not
        //      change under it. Assigning the field and joining afterwards put it back:
        //      the OLD tunnel's watcher would respawn tun2socks against the NEW config's
        //      port, i.e. a relay pointed at a listener that does not exist yet.
        //
        //      Reading the extra into a local costs nothing and keeps the cheap null check
        //      where it was; only the PUBLICATION moves behind the join.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            V2rayConfig first = config(null);
            first.REMARK = "the-old-tunnel";
            userStart(s, first);

            java.util.concurrent.CountDownLatch open = wedgeLane(s);
            s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE, null), 0, 2);

            V2rayConfig second = config(null);
            second.REMARK = "the-new-tunnel";
            Thread dial = new Thread(() -> s.onStartCommand(
                    command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, second), 0, 3),
                    "harness-start");
            dial.setDaemon(true);
            dial.start();

            // Long enough that a start which published first would have done so by now.
            // ⚠ AND THE FIELD IS `volatile`, WHICH IS WHAT MAKES THIS A TEST. Read from a
            // third thread off a plain field, a STALE value is indistinguishable from an
            // ordered one — the pass value and the bug's value are the same observation.
            // The publication the production code needs is a visibility property, so the
            // field carries one; without it neither this assertion nor the tun2socks
            // watcher it protects would mean anything.
            try { Thread.sleep(300L); } catch (InterruptedException ignored) { }
            Object live = priv(s, V2rayVPNService.class, "v2rayConfig");
            check("the field still names the tunnel that is coming down",
                    live instanceof V2rayConfig
                            && "the-old-tunnel".equals(((V2rayConfig) live).REMARK),
                    "field already reads "
                            + (live instanceof V2rayConfig ? ((V2rayConfig) live).REMARK : live)
                            + " — the old watcher can respawn against the new port");

            open.countDown();
            try { dial.join(10000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            Object after = priv(s, V2rayVPNService.class, "v2rayConfig");
            check("and it names the new one once the lane is idle",
                    after instanceof V2rayConfig
                            && "the-new-tunnel".equals(((V2rayConfig) after).REMARK),
                    "field reads "
                            + (after instanceof V2rayConfig ? ((V2rayConfig) after).REMARK : after));
        });

        // 46b. THE "REPLACE A RUNNING CORE" BRANCH HAS NO PRODUCTION CALLER, AND IF IT
        //      EVER GETS ONE IT WILL NOT DO WHAT ITS NAME SAYS.
        //
        //      ⚠⚠ MY FIRST VERSION OF THIS CASE BLESSED IT AS A WORKING RE-DIAL. Both
        //      halves of that were wrong, and an adversarial pass showed it:
        //
        //      * THE PREMISE. The comment said "switchNode() and every failover send a
        //        START with no STOP in front of it". They do not. `v2ray_vpn_bridge.dart`
        //        sends stopV2Ray() and sleeps 400 ms before EVERY start, and switchNode()
        //        in vpn_controller.dart additionally awaits a teardown first. Since the
        //        start now joins the lane, the core is always already stopped by the time
        //        this branch is evaluated — so the case was pinning a path nothing takes.
        //
        //      * THE OUTCOME. `stopCore()` on the stub, exactly as on the device, calls
        //        back through stopService() into stopAllProcess() — which closes the tun,
        //        nulls mInterface and calls stopSelf(). A start that reaches this branch
        //        therefore starts a core into a service the framework is about to destroy,
        //        and onDestroy() then stops that core again. "The re-dial succeeds" was
        //        asserted about a sequence that ends with no tunnel and no service.
        //
        //      So this case pins the two things that are actually true: the production
        //      sequence does NOT reach the branch, and the branch is not a working re-dial.
        //      Deleting the branch is a separate change with its own reasoning; leaving it
        //      undocumented is what let a test bless it.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            userStart(s, config(null));

            // The production sequence, in order: a stop, then a start.
            int offLaneBefore = V2rayCoreManager.stopCoreCallsOffLane;
            int startsBefore = V2rayCoreManager.startCoreCalls;
            s.onStartCommand(command(AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE, null), 0, 2);
            joinLane(s);
            int r = s.onStartCommand(
                    command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, config(null)), 0, 3);

            check("the ordinary stop-then-start reaches no re-dial branch",
                    V2rayCoreManager.stopCoreCallsOffLane == offLaneBefore,
                    "stopCore ran " + (V2rayCoreManager.stopCoreCallsOffLane - offLaneBefore)
                            + " time(s) on the caller — the branch was taken after all");
            check("and it starts the core normally",
                    V2rayCoreManager.startCoreCalls == startsBefore + 1,
                    "startCore ran " + (V2rayCoreManager.startCoreCalls - startsBefore) + " time(s)");
            check("and answers START_STICKY", r == android.app.Service.START_STICKY,
                    "returned " + r);
        });

        // 46c. AND WHAT THE BRANCH WOULD DO, MEASURED RATHER THAN ASSUMED.
        //
        //      Reached only by forcing the state the production sequence cannot produce:
        //      a START while the core is genuinely still running. The point is not that
        //      this is good — it is that the record says what it costs, so the next person
        //      to consider relying on it has the measurement instead of the name.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            userStart(s, config(null));
            check("a core is up before the re-dial", V2rayCoreManager.coreRunning, "no core");

            s.onStartCommand(
                    command(AppConfigs.V2RAY_SERVICE_COMMANDS.START_SERVICE, config(null)), 0, 2);

            check("the branch tears the SERVICE down, not just the core",
                    s.stopSelfCalled,
                    "stopSelf was not called — the note above is out of date");
            check("and it closes the tun the new core was about to use",
                    priv(s, V2rayVPNService.class, "mInterface") == null,
                    "mInterface survived — the note above is out of date");
        });

        // 47. THE STATS TICKER IS CANCELLED BEFORE THE CORE IS CLOSED, NOT AFTER.
        //
        //     ⚠ THIS ONE CANNOT BE RUN HERE, AND SAYING SO IS THE POINT. V2rayCoreManager
        //     is the class this harness STUBS — it owns libv2ray — so the real stopCore()
        //     never executes in this JVM and no behavioural assertion can reach it. What
        //     can be checked is the ORDER in the source, which is the whole property:
        //     countDownTimer.cancel() must precede coreController.stopLoop().
        //
        //     Why it matters: onTick() calls coreController.queryStats(tag, ...) once a
        //     second on the daemon looper. While the teardown ran on that same looper no
        //     tick could interleave with it; now that the teardown has its own lane the
        //     looper is free for every second of a multi-second stop, and the two land on
        //     the same CoreController. `javap -p` on libv2ray.aar shows both as plain
        //     `native`, not synchronized. Cancelling first removes the window instead of
        //     asking Go to tolerate it.
        run(() -> {
            String src = readCode(
                    "../android/src/main/java/dev/amirzr/flutter_v2ray_client/v2ray/core/"
                            + "V2rayCoreManager.java");
            int body = src.indexOf("public void stopCore()");
            check("stopCore() is where this file says it is", body > 0, "method not found");
            int cancel = src.indexOf("countDownTimer.cancel()", body);
            int stop = src.indexOf("coreController.stopLoop()", body);
            check("stopCore() still cancels the duration timer", cancel > 0,
                    "no cancel inside stopCore()");
            check("stopCore() still closes the core", stop > 0, "no stopLoop inside stopCore()");
            check("the ticker is cancelled BEFORE the core is closed", cancel < stop,
                    "cancel at " + cancel + ", stopLoop at " + stop
                            + " — a stats tick can now call queryStats() on a closing core");

            // ⚠ AND CANCELLING IS NOT ENOUGH, BECAUSE THE TIMER RE-ARMS ITSELF. onFinish()
            // runs every 7.2 s and builds a NEW CountDownTimer whenever the core reads as
            // running — which it does for the whole of stopLoop(). A cancel landing while
            // the looper is in the final handleMessage kills a timer that has already been
            // replaced, and the replacement calls queryStats() once a second for the rest
            // of the stop: the same window, reopened by the object's own lifecycle. So the
            // re-arm has to consult a flag that is true for the whole teardown.
            int fin = src.indexOf("public void onFinish()");
            check("the duration timer still re-arms itself", fin > 0, "onFinish not found");
            int guard = src.indexOf("stopping", fin);
            int rearm = src.indexOf("makeDurationTimer", fin);
            check("the re-arm is gated on a teardown flag, not only on the running core",
                    guard > 0 && rearm > 0 && guard < rearm,
                    "onFinish re-arms without consulting `stopping`");
            check("`stopping` is set for the whole of stopCore()",
                    src.indexOf("stopping = true", body) > 0
                            && src.indexOf("stopping = true", body) < stop,
                    "the flag is not raised before the core is closed");
            // ⚠ AFTER startCore(), NOT ANYWHERE. `private volatile boolean stopping =
            // false;` is a field initialiser and answered this check for free, so deleting
            // the assignment in startCore left it green — and a `stopping` nothing clears
            // means the ticker never comes back after the first stop.
            int sc = src.indexOf("public boolean startCore(");
            check("startCore() is where this file says it is", sc > 0, "method not found");
            int clear = src.indexOf("stopping = false", sc);
            int tags = src.indexOf("statsTags = readOutboundTags", sc);
            // ⚠ AND THE ONE THING THAT TELLS THE APP THE TUNNEL IS DOWN MUST SURVIVE A
            // THROW. The Go shutdown() callback NULLS v2rayServicesListener and stopLoop()
            // is what triggers it, so an NPE on `listener.stopService()` was swallowed by
            // the catch as an ordinary Exception — and took sendDisconnectedBroadCast()
            // with it. The app then never received V2RAY_DISCONNECTED and its UI stayed
            // "connected" over a torn-down tunnel: the worst shape available, because it
            // is the one a user cannot tell from a working one.
            //
            // ⚠ "AFTER stopTuic()" IS NOT THE TEST, AND MY FIRST VERSION USED IT — a
            // mutant that moved the call back INSIDE the try was still after stopTuic and
            // stayed green. The catch's own log line is the last thing in the block, so
            // "after that" is what actually means "outside it".
            // ⚠ THE CALL, NOT THE DECLARATION. `private void sendDisconnectedBroadCast()`
            // sits BELOW stopCore() in this file, so searching without the semicolon found
            // the method's own definition — and a mutant that deleted the CALL outright
            // stayed green, because the definition is still there and still after the
            // catch. A structural check that matches the thing it is looking for by name
            // rather than by shape finds the wrong occurrence sooner or later.
            int announce = src.indexOf("sendDisconnectedBroadCast();", body);
            // ⚠ THE CATCH'S LOG, AND ONLY IT. `"stopCore failed => v2ray core not
            // running."` is a DIFFERENT line, inside the try, and it shares this prefix —
            // so anchoring on the prefix let a mutant that moved the call back inside the
            // try (after that line) still read as "outside". The catch's is the one that
            // passes the exception.
            int caught = src.indexOf("\"stopCore failed =>\", e)", body);
            int guarded = src.indexOf("if (listener != null)", body);
            check("the listener is null-guarded before it is called",
                    guarded > 0 && announce > 0 && guarded < announce,
                    "a shutdown racing stopLoop() NPEs and swallows the disconnect");
            check("the disconnect is announced OUTSIDE the try that can throw",
                    announce > 0 && caught > 0 && announce > caught,
                    "sendDisconnectedBroadCast is still reachable only on the happy path");

            check("a start clears the flag, or the ticker never comes back",
                    clear > 0 && tags > 0 && clear < tags,
                    "startCore does not clear `stopping` before it rebuilds the ticker");
        });

        // 48. TWO ORDERINGS THIS HARNESS CANNOT EXECUTE, PINNED IN THE SOURCE.
        //
        //     Both were found by a mutation run surviving: the checks below exist because
        //     the behavioural ones could not be written here, not instead of writing them.
        //
        //     * THE TUN2SOCKS WATCHER'S GENERATION GUARD needs a real tun2socks binary to
        //       exit and re-enter; this JVM has none (every run prints "Cannot run program
        //       /nonexistent-doft-test-libs/libtun2socks.so"). What it protects: the
        //       watcher re-enters runTun2socks() on its own thread, holds nothing, and is
        //       NOT on the teardown lane — so it can pass its own `isRunning` check
        //       microseconds before stopAllProcess() clears it and then respawn tun2socks
        //       against the config the NEXT start published. `isRunning` alone cannot see
        //       that; `tunGeneration` is bumped by every setup() that establishes an
        //       interface, which is exactly "a different tunnel from the one I belong to".
        //
        //     * THE DELAY PROBE ON THE LANE. MEASURE_DELAY ends in
        //       coreController.measureDelay() on the same object stopLoop() is closing.
        //       The stub's getConnectedV2rayServerDelay() returns -1 immediately, so no
        //       assertion here can tell which thread it ran on.
        run(() -> {
            String src = readCode(
                    "../android/src/main/java/dev/amirzr/flutter_v2ray_client/v2ray/"
                            + "services/V2rayVPNService.java");
            int watcher = src.indexOf("\"Tun2socks_Thread\"");
            check("the tun2socks watcher is where this file says it is", watcher > 0,
                    "thread not found");
            int body = src.lastIndexOf("new Thread(", watcher);
            String block = body > 0 ? src.substring(body, watcher) : "";
            check("the watcher checks the generation it belongs to, not only isRunning",
                    block.contains("tunGeneration.get()"),
                    "a watcher from the old tunnel can respawn against the new config");
            check("and it waits on the process it started, not on whatever the field holds",
                    block.contains("mine.waitFor()"),
                    "`process` is read again after another thread may have replaced it");

            int measure = src.indexOf("MEASURE_DELAY");
            check("the MEASURE_DELAY branch is where this file says it is", measure > 0,
                    "branch not found");
            int probe = src.indexOf("getConnectedV2rayServerDelay", measure);
            int lane = src.indexOf("offTheMainThread", measure);
            check("the delay probe is ordered against the teardown lane",
                    lane > 0 && probe > 0 && lane < probe,
                    "measureDelay() can run against a CoreController stopLoop() is closing");
        });

        // 49. onDestroy() UNREGISTERS THE NETWORK CALLBACK TOO, NOT ONLY stopAllProcess().
        //
        //     The framework holds a NetworkCallback until it is unregistered, so every
        //     destruction that did NOT come through stopAllProcess() — the core already
        //     stopped, an external stopService(), a start that failed after setup() —
        //     leaked one, each still calling setUnderlyingNetworks on a tunnel that no
        //     longer exists. One per connect, for the life of the process.
        run(() -> {
            resetWorld();
            Disk disk = new Disk();
            TestVpn s = new TestVpn(disk);
            // ⚠ resetWorld() DOES NOT TOUCH THE CONNECTIVITY FAKE, so `last` and its
            // counters carry across cases. Without this, the "a callback was registered"
            // check passes on a callback some EARLIER case registered and the case proves
            // nothing about this service at all — which is exactly what it did first time.
            if (android.net.ConnectivityManager.last != null) {
                android.net.ConnectivityManager.last.reset();
            }
            userStart(s, config(null));
            // ⚠ DRIVEN DIRECTLY, BECAUSE setup() CANNOT LEAVE ONE REGISTERED HERE. This
            // harness has no tun2socks binary, so runTun2socks() always throws and its
            // catch runs stopAllProcess() — which unregisters on the way out. Going
            // through setup() therefore ends with nothing registered, and the case would
            // pass on an onDestroy that does nothing. The property under test is "a
            // callback that IS registered does not survive onDestroy", so the case
            // registers one the same way production does and then destroys the service.
            call(s, "watchUnderlyingNetwork", new Class<?>[] { });
            android.net.ConnectivityManager cm = android.net.ConnectivityManager.last;
            check("this tunnel registered a network callback",
                    cm != null && cm.registerCalls > 0,
                    "nothing to leak — this case can no longer see the defect");
            check("and it is still registered going into onDestroy",
                    priv(s, V2rayVPNService.class, "netCb") != null, "already gone");

            // The core is ALREADY stopped, so onDestroy takes neither the stopAllProcess
            // path nor the lane: exactly the destruction that used to leak.
            V2rayCoreManager.coreRunning = false;
            int before = cm.unregisterCalls;
            s.onDestroy();
            check("onDestroy unregisters it even with no core to stop",
                    cm.unregisterCalls > before,
                    "the callback outlived the service that registered it");
        });

        // 50. THE FIXTURES LIVE SOMEWHERE THIS JVM OWNS, NOT IN A SHARED NAMESPACE.
        //
        //     ⚠ THIS IS THE ONE THAT ONLY FAILED SOMEWHERE ELSE. Both fixture paths were
        //     fixed names — `${java.io.tmpdir}/doft-fake-tun2socks` and the literal
        //     `/tmp/doft-assets`. On macOS java.io.tmpdir is a PER-USER directory under
        //     /var/folders, so eighteen consecutive local runs were 316/0. On Linux it is
        //     `/tmp`: shared by every user and every job on the host, and kept BETWEEN jobs
        //     on a self-hosted runner. Writing the fake libtun2socks.so over a copy an
        //     earlier run had left threw AccessDeniedException, the case died before its
        //     first assertion, and the suite came back 315 assertions with 1 failure —
        //     one fewer than it should run, which is the signature of a case that threw.
        //
        //     It took a gate that runs this suite at the pinned ref to see it at all. This
        //     case is so that the next person does not need one.
        run(() -> {
            String tmp = System.getProperty("java.io.tmpdir");
            java.io.File assets = android.content.Context.ASSETS;
            java.io.File fake;
            try {
                fake = Tun2socks.fakeLibDir();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            for (java.io.File d : new java.io.File[] { assets, fake }) {
                String name = d.getName();
                // createTempDirectory appends a random suffix; a fixed name has none.
                check("fixture " + name + " is private to this JVM",
                        name.matches(".*\\d{4,}$") || d.getAbsolutePath().startsWith(tmp + "/doft"),
                        d.getAbsolutePath() + " is a name any other job on this host would "
                                + "pick too — the second run cannot write it");
                check("fixture " + name + " is writable",
                        d.isDirectory() && d.canWrite(),
                        d.getAbsolutePath() + " cannot be written by this run");
            }
            check("the fake binary was written, not inherited",
                    new java.io.File(fake, "libtun2socks.so").canRead(),
                    "no fake libtun2socks.so at " + fake.getAbsolutePath());
        });

        System.out.println(failures == 0 ? "ALL PASS" : (failures + " FAILURES"));
        System.out.println("RESULT services checks=" + checks + " failures=" + failures);
        // ⚠ ALWAYS exit(), never fall off the end of main(). Case 29 starts the REAL
        // tun2socks watcher thread, which is not a daemon; if the isRunning=false it
        // depends on is ever deleted, that thread respawns forever and a JVM that merely
        // returned from main() would hang instead of reporting. A hang is a worse test
        // result than a failure because run.sh cannot tell it from a slow machine.
        System.exit(failures == 0 ? 0 : 1);
    }
}
