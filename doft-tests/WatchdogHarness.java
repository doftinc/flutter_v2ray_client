import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.Context;

import dev.amirzr.flutter_v2ray_client.v2ray.core.TunnelWatchdogPolicy;
import dev.amirzr.flutter_v2ray_client.v2ray.core.V2rayCoreManager;
import dev.amirzr.flutter_v2ray_client.v2ray.services.V2rayVPNService;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.V2rayConfig;

import java.lang.reflect.Field;

/**
 * The dead-tunnel detector that survives the app being killed.
 *
 * <h2>What this exists for</h2>
 *
 * <p>Measured on a MIUI tablet, 2026-08-31. The Flutter process was gone; the daemon
 * ({@code :RunSoLibV2RayDaemon}) was not. For <b>37 minutes</b> the core logged websocket
 * timeouts to the web-proxy member every few seconds, tuic timeouts to the entry, and
 * finally DNS timeouts for the CDN front — into a void, because every piece of code that
 * could have read those was in the process Android had killed. The user had a VPN key icon
 * and no internet, and the outage ended only when they opened the app: the existing Dart
 * logic then diagnosed it in 21 seconds and rebuilt the tunnel, which resumed at 327 KB/s.
 *
 * <p>So the fix is not faster detection. It is detection that exists at all when nobody is
 * looking, and this harness is what says it does.
 *
 * <h2>What a green run here means, and what it does not</h2>
 *
 * <p>Every assertion below was proved by REVERTING the production line and watching this
 * harness go red — the only evidence a test is worth anything. What is covered:
 *
 * <ul>
 *   <li>the policy's five rules, each driven to both answers;
 *   <li>the service's real {@code carryWatchdogTick}, including the SIDE EFFECTS: the
 *       START_SERVICE it sends itself and the notification it posts;
 *   <li>the real {@code appProcessAlive} against a fake ActivityManager, including the
 *       three "cannot tell" shapes, because that one boolean decides whether the daemon
 *       ever acts at all.
 * </ul>
 *
 * <p>What is NOT covered, stated plainly: the probe itself. {@code measureDelay} is
 * libv2ray's, it needs a running core and a network, and no stub of it would be evidence
 * of anything. The tick takes the probe's answer as an argument for exactly that reason —
 * the seam is deliberate, and it means "the probe is right" is an assumption here, not a
 * result.
 */
public class WatchdogHarness {

    static int checks = 0;
    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.printf("%-64s %s%s%n", name, ok ? "PASS" : "FAIL",
                (ok || detail == null) ? "" : "  " + detail);
    }

    static void run(Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            checks++;
            failures++;
            System.out.printf("%-64s %s  %s%n", "(case threw)", "FAIL", t);
            t.printStackTrace();
        }
    }

    // ── reaching the real methods ────────────────────────────────────────────────────
    // ⚠ REFLECTION, LIKE ServiceHarness. The two seams are package-private on purpose:
    // making them public to please a test would widen the plugin's API for every consumer,
    // and the harness lives in the default package. Same idiom as `restoreProofTick`.

    static Object call(Object target, String method, Class<?>[] sig, Object... args) {
        try {
            java.lang.reflect.Method m = V2rayVPNService.class.getDeclaredMethod(method, sig);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static TunnelWatchdogPolicy.Action tick(V2rayVPNService s, boolean carried, boolean appAlive) {
        return (TunnelWatchdogPolicy.Action) call(s, "carryWatchdogTick",
                new Class<?>[] { boolean.class, boolean.class }, carried, appAlive);
    }

    static boolean appAlive(V2rayVPNService s) {
        return (Boolean) call(s, "appProcessAlive", new Class<?>[] {});
    }

    // ── the policy, on its own ───────────────────────────────────────────────────────

    /** The policy this build ships, asked for the way the service asks for it. */
    static TunnelWatchdogPolicy shipped() {
        return (TunnelWatchdogPolicy) call(new V2rayVPNService(), "newWatchdogPolicy",
                new Class<?>[] {});
    }

    static void policyRules() {
        TunnelWatchdogPolicy p = shipped();

        check("a carrying tunnel is never acted on",
                p.onProbe(true, false) == TunnelWatchdogPolicy.Action.NONE, null);

        check("one failure is not a pattern",
                p.onProbe(false, false) == TunnelWatchdogPolicy.Action.NONE, null);
        check("two consecutive failures with no app to fix it => replay",
                p.onProbe(false, false) == TunnelWatchdogPolicy.Action.RESTART, null);

        check("the second replay is still allowed",
                p.onProbe(false, false) == TunnelWatchdogPolicy.Action.NONE
                        && p.onProbe(false, false) == TunnelWatchdogPolicy.Action.RESTART, null);
        check("and the budget is then spent => the user is told",
                p.onProbe(false, false) == TunnelWatchdogPolicy.Action.NONE
                        && p.onProbe(false, false) == TunnelWatchdogPolicy.Action.ALERT, null);
        check("told ONCE, not on every tick after that",
                p.onProbe(false, false) == TunnelWatchdogPolicy.Action.NONE
                        && p.onProbe(false, false) == TunnelWatchdogPolicy.Action.NONE, null);

        check("a tunnel that carries again clears everything",
                p.onProbe(true, false) == TunnelWatchdogPolicy.Action.NONE
                        && p.restartsUsed() == 0 && p.consecutiveFailures() == 0, null);
        check("...so the NEXT outage gets its full budget and can alert again",
                p.onProbe(false, false) == TunnelWatchdogPolicy.Action.NONE
                        && p.onProbe(false, false) == TunnelWatchdogPolicy.Action.RESTART, null);
    }

    static void theAppOwnsRemediationWhileItIsAlive() {
        TunnelWatchdogPolicy p = shipped();
        check("with the app alive the daemon does nothing, however long it fails",
                p.onProbe(false, true) == TunnelWatchdogPolicy.Action.NONE
                        && p.onProbe(false, true) == TunnelWatchdogPolicy.Action.NONE
                        && p.onProbe(false, true) == TunnelWatchdogPolicy.Action.NONE, null);
        // ⚠ THE RULE THAT IS EASY TO GET BACKWARDS. Returning before the counter is
        // incremented would reset the daemon's evidence on any tick the app happened to be
        // alive — and an OEM that kills and relaunches the app in a loop would hold the
        // daemon at zero forever.
        check("but it COUNTS: the tick after the app dies acts at once",
                p.onProbe(false, false) == TunnelWatchdogPolicy.Action.RESTART, null);
        check("...and the failure count really was carried across",
                p.consecutiveFailures() == 0 && p.restartsUsed() == 1, null);
    }

    // ── the service, with its side effects ───────────────────────────────────────────

    static V2rayVPNService serviceWithWatchdog() {
        V2rayVPNService svc = new V2rayVPNService();
        try {
            Field w = V2rayVPNService.class.getDeclaredField("watchdog");
            w.setAccessible(true);
            // ⚠ THE SHIPPED POLICY, NOT ONE THIS TEST INVENTED. Building
            // `new TunnelWatchdogPolicy(2, 2)` here left the service's own constants
            // undefended: changing the threshold to 1, or the restart budget to 0, kept
            // every assertion below green. Ask the service for its policy instead.
            w.set(svc, call(svc, "newWatchdogPolicy", new Class<?>[] {}));
            Field c = V2rayVPNService.class.getDeclaredField("v2rayConfig");
            c.setAccessible(true);
            c.set(svc, new V2rayConfig());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return svc;
    }

    static void theServiceActs() {
        Context.resetStartService();
        NotificationManager.reset();
        V2rayCoreManager.reset();
        V2rayVPNService svc = serviceWithWatchdog();

        tick(svc, true, false);
        check("a carrying tunnel sends nothing and posts nothing",
                Context.startServiceCalls == 0 && NotificationManager.notifyCalls == 0, null);

        tick(svc, false, false);
        check("one failure is still silent",
                Context.startServiceCalls == 0 && NotificationManager.notifyCalls == 0, null);

        TunnelWatchdogPolicy.Action a = tick(svc, false, false);
        check("the second failure replays the config THROUGH START_SERVICE",
                a == TunnelWatchdogPolicy.Action.RESTART && Context.startServiceCalls == 1,
                "startService=" + Context.startServiceCalls);
        // ⚠ NOT stopCore(). It takes the SERVICE down, not just the core, and from a
        // watchdog thread that would leave the user with no tunnel at all — the one
        // outcome this must never produce.
        check("...and it does NOT stop the core behind the service's back",
                V2rayCoreManager.stopCoreCalls == 0,
                "stopCore=" + V2rayCoreManager.stopCoreCalls);
        check("the replay carries a config, or it would start nothing",
                Context.lastStartService != null, null);

        // spend the rest of the budget
        tick(svc, false, false);
        tick(svc, false, false);
        check("the second replay is sent too",
                Context.startServiceCalls == 2, "startService=" + Context.startServiceCalls);

        tick(svc, false, false);
        TunnelWatchdogPolicy.Action alert = tick(svc, false, false);
        check("with the budget spent the user is TOLD",
                alert == TunnelWatchdogPolicy.Action.ALERT
                        && NotificationManager.notifyCalls == 1,
                "notify=" + NotificationManager.notifyCalls);
        check("...and the tunnel is left UP — never stopped, never exposed",
                V2rayCoreManager.stopCoreCalls == 0 && Context.startServiceCalls == 2, null);

        tick(svc, false, false);
        tick(svc, false, false);
        check("and told once, not once a minute forever",
                NotificationManager.notifyCalls == 1,
                "notify=" + NotificationManager.notifyCalls);
    }

    static void theServiceDoesNothingWhileTheAppIsAlive() {
        Context.resetStartService();
        NotificationManager.reset();
        V2rayVPNService svc = serviceWithWatchdog();
        for (int i = 0; i < 8; i++) {
            tick(svc, false, true);
        }
        check("eight failed probes with the app alive: nothing sent, nothing posted",
                Context.startServiceCalls == 0 && NotificationManager.notifyCalls == 0,
                "startService=" + Context.startServiceCalls
                        + " notify=" + NotificationManager.notifyCalls);
    }

    static void aServiceWithNoPolicyIsInert() {
        Context.resetStartService();
        NotificationManager.reset();
        V2rayVPNService svc = new V2rayVPNService();
        check("a tick before any tunnel was established does nothing",
                tick(svc, false, false) == TunnelWatchdogPolicy.Action.NONE
                        && Context.startServiceCalls == 0, null);
    }

    // ── the one boolean the whole thing turns on ─────────────────────────────────────

    static void whoIsAlive() {
        V2rayVPNService svc = new V2rayVPNService();

        ActivityManager.reset();
        ActivityManager.processes.add(
                new ActivityManager.RunningAppProcessInfo("com.doft.vpn"));
        check("the app's main process is recognised as alive",
                appAlive(svc), null);

        ActivityManager.reset();
        ActivityManager.processes.add(
                new ActivityManager.RunningAppProcessInfo("com.doft.vpn:RunSoLibV2RayDaemon"));
        check("our OWN daemon process is not the app",
                !appAlive(svc),
                "the daemon is always alive here — reading it as the app would disable the "
                        + "watchdog completely");

        ActivityManager.reset();
        check("an empty process list means the app is gone",
                !appAlive(svc), null);

        // ⚠ UNKNOWN MEANS ALIVE, in every shape. A daemon that cannot tell must do
        // NOTHING: racing the app's failover is worse than being a minute late.
        ActivityManager.processes = null;
        check("a null process list is 'cannot tell' => assume alive => do nothing",
                appAlive(svc), null);

        ActivityManager.reset();
        ActivityManager.throwOnQuery = true;
        check("and a device that REFUSES the query is 'cannot tell' too",
                appAlive(svc),
                "returning false here would let a daemon that cannot see the app restart "
                        + "and alert underneath a perfectly healthy Dart failover");
        ActivityManager.reset();
    }

    /**
     * ⚠⚠ THE CASE THAT NOTICES THE FEATURE IS SWITCHED OFF. Everything above drives
     * {@code carryWatchdogTick} directly, so deleting the one line in {@code setup()} that
     * STARTS the watchdog left all 27 assertions green — the whole thing unwired, and the
     * suite that exists to defend it reporting ALL PASS. Same shape as every other "the
     * writer, the reader and the value are pinned, the WIRE is not" defect.
     *
     * <p>Asserted on the field rather than on the thread: {@code startCarryWatchdog}
     * assigns the policy synchronously and only then starts a thread that sleeps out the
     * grace period, so the field is the deterministic half.
     */
    static void setupArmsTheWatchdog() {
        V2rayVPNService svc = new V2rayVPNService();
        try {
            Field c = V2rayVPNService.class.getDeclaredField("v2rayConfig");
            c.setAccessible(true);
            c.set(svc, new V2rayConfig());
            Field w = V2rayVPNService.class.getDeclaredField("watchdog");
            w.setAccessible(true);
            check("no tunnel yet, so no watchdog yet", w.get(svc) == null, null);

            android.net.VpnService.establishResult =
                    new android.os.ParcelFileDescriptor();
            // What xray's startup() callback does: runs setup().
            svc.startService();

            check("establishing a tunnel ARMS the carry watchdog",
                    w.get(svc) != null,
                    "setup() did not start it — the daemon is back to having no "
                            + "dead-tunnel detector at all");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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

    static boolean waitFor(long millis, java.util.function.BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return cond.getAsBoolean();
    }

    static void set(String field, Object value) {
        try {
            Field f = V2rayVPNService.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(null, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * ⚠ A REBUILT TUNNEL MUST NOT BE JUDGED BY THE WATCHER OF THE ONE IT REPLACED, and
     * this is the clause that says so. Without it every replay leaves another watcher
     * running: they all probe, they all count, and N of them race to replay the same
     * tunnel — a restart loop built out of the thing that exists to prevent one.
     *
     * <p>Driven as a PREDICATE, not through the thread. A thread-liveness assertion here
     * measures the fixture: no tunnel carries in this harness, {@code runTun2socks} cannot
     * exec, {@code isRunning} goes false and every watcher exits within milliseconds
     * whether the guard is there or not. Measured — the first version of this case failed
     * for exactly that reason and would have passed with the guard deleted.
     */
    static void aRebuiltTunnelRetiresTheOldWatcher() {
        try {
            V2rayVPNService svc = new V2rayVPNService();
            Field run = V2rayVPNService.class.getDeclaredField("isRunning");
            run.setAccessible(true);
            run.set(svc, true);
            Field gen = V2rayVPNService.class.getDeclaredField("tunGeneration");
            gen.setAccessible(true);
            java.util.concurrent.atomic.AtomicInteger g =
                    (java.util.concurrent.atomic.AtomicInteger) gen.get(svc);
            g.set(7);

            check("the watcher of the tunnel in hand keeps looking",
                    owns(svc, 7), null);
            g.set(8);
            check("...and the one armed for the tunnel it replaced stops",
                    !owns(svc, 7),
                    "a stale watcher would keep probing and counting alongside the new one, "
                            + "and both would replay the same tunnel");
            check("the new generation's watcher is the one that continues",
                    owns(svc, 8), null);

            run.set(svc, false);
            check("and a torn-down tunnel stops every watcher, whatever its generation",
                    !owns(svc, 8), null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static boolean owns(V2rayVPNService s, int generation) {
        return (Boolean) call(s, "watcherStillOwnsTunnel", new Class<?>[] { int.class },
                generation);
    }

    /**
     * ⚠ AND THE LOOP HAS TO ASK IT. Everything above pins the predicate; nothing pinned
     * its USE, so replacing the loop's condition with a bare {@code isRunning} left all 33
     * assertions green with the generation guard bypassed. The loop body cannot be driven
     * here — it sleeps, probes and needs a live tunnel — so this reads the source, which is
     * a weaker instrument and is stated as one: it defends the one line, not the property.
     */
    static void theLoopAsksTheGuard() {
        try {
            String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    "../android/src/main/java/dev/amirzr/flutter_v2ray_client/v2ray/"
                            + "services/V2rayVPNService.java")), "UTF-8");
            int at = src.indexOf("startCarryWatchdog()");
            int body = src.indexOf("private void startCarryWatchdog()");
            int guard = src.indexOf("while (watcherStillOwnsTunnel(generation)) {", body);
            int name = src.indexOf("CarryWatchdog_Thread", body);
            // ⚠ SEARCHED FROM THE METHOD, AND NAMED THE THREAD AS THE BOUND. The proof
            // watcher's loop is the SAME line, earlier in the file — a search from zero
            // finds it and passes while this one bypasses the guard entirely. Found the
            // hard way: the first version of this assertion was green on a build where
            // the carry watchdog still read `isRunning` alone.
            check("the watchdog thread's loop asks the guard, not isRunning alone",
                    body > 0 && guard > 0 && name > 0 && guard < name,
                    "the loop bypasses watcherStillOwnsTunnel, so every replay leaves "
                            + "another watcher running");
            check("and setup() is what arms it",
                    at > 0 && at < body,
                    "startCarryWatchdog() is defined but never called");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        run(WatchdogHarness::policyRules);
        run(WatchdogHarness::theAppOwnsRemediationWhileItIsAlive);
        run(WatchdogHarness::theServiceActs);
        run(WatchdogHarness::theServiceDoesNothingWhileTheAppIsAlive);
        run(WatchdogHarness::aServiceWithNoPolicyIsInert);
        run(WatchdogHarness::whoIsAlive);
        run(WatchdogHarness::setupArmsTheWatchdog);
        run(WatchdogHarness::aRebuiltTunnelRetiresTheOldWatcher);
        run(WatchdogHarness::theLoopAsksTheGuard);
        System.out.println(failures == 0 ? "ALL PASS" : "SOME FAILED");
        System.out.printf("RESULT watchdog checks=%d failures=%d%n", checks, failures);
        if (failures != 0) {
            System.exit(1);
        }
    }
}
