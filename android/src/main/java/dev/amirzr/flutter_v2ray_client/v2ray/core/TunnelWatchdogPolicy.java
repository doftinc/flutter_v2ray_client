package dev.amirzr.flutter_v2ray_client.v2ray.core;

/**
 * What to do when the tunnel is up and carrying nothing — with NO dependency on any Android
 * class, so it can be compiled and run on a laptop.
 *
 * <h2>What was wrong</h2>
 *
 * <p>The dead-tunnel detector lived entirely in Dart: {@code TunnelHealthMonitor}, a
 * {@code Timer.periodic} started and stopped by {@code VpnController} in the Flutter app
 * process. The core runs in {@code :RunSoLibV2RayDaemon}, a DIFFERENT process, and it
 * survives the app being killed. So on any device that kills backgrounded apps — MIUI is
 * the loudest, but every OEM does it — the tunnel stays up, the detector does not, and
 * nothing is left that can tell "carrying" from "black hole".
 *
 * <p>Measured on a MIUI tablet, 2026-08-31: the app process was gone, the daemon kept
 * running, and for <b>37 minutes</b> it logged websocket timeouts to the web-proxy member
 * every few seconds, tuic timeouts to the entry, and finally DNS timeouts for the CDN
 * front — with nobody to report them to. The user had a VPN key icon and no internet. The
 * moment the app was reopened the existing Dart logic found it in 21 seconds: the probe
 * got no 204, then no payload (6002 ms x2), and the route check saw the device's own ISP
 * address. It tore the tunnel down and rebuilt it, and payload resumed at 327 KB/s.
 *
 * <p>The 21 seconds were never the problem. The problem is that they only start when a
 * human opens the app.
 *
 * <h2>Why a policy class and not a byte counter</h2>
 *
 * <p>{@code startRestoreProofWatcher} already counts downlink bytes, and its own note says
 * why that cannot be the answer here: "from inside {@code :RunSoLibV2RayDaemon} an idle
 * tunnel and a black-holed one are the same observation". A sleeping tablet moves no bytes
 * either. So the daemon has to ASK — {@code measureDelay} through the running core, which
 * is the same question the Dart probe asks — and this class decides what the answers mean.
 *
 * <h2>The rules, and why each one is this narrow</h2>
 *
 * <ul>
 *   <li><b>The app owns remediation whenever the app is alive.</b> It has the chain, the
 *       country list, the endpoint memory and the failover budget; the daemon has none of
 *       that. Two failovers racing is worse than one that is slow, so while the app is
 *       there this class watches and does nothing. It keeps counting, so an app that dies
 *       mid-failure does not start the daemon's own clock from zero.
 *   <li><b>Restart before alarm, and a bounded number of times.</b> The config the daemon
 *       replays carries the whole group, so restarting the core re-runs the balancer's
 *       urltest and can land on a different member — which is the cheapest thing that has
 *       ever fixed this. Unbounded, it would be a restart loop on a device with no
 *       network at all.
 *   <li><b>Never stop the tunnel.</b> Failing closed leaves the user with no internet;
 *       failing OPEN leaves their traffic outside the tunnel while they believe it is
 *       inside. For this product the second is the worse harm, so the last resort is to
 *       TELL them, not to expose them.
 *   <li><b>One alert per outage.</b> The latch clears on the first probe that carries, so
 *       a tunnel that recovers and dies again is reported again.
 * </ul>
 */
public final class TunnelWatchdogPolicy {

    /** What the daemon should do about this tick. */
    public enum Action {
        /** Nothing. Either the tunnel carries, or it is too early, or the app has it. */
        NONE,
        /** Replay the last known good config: the balancer re-picks inside the group. */
        RESTART,
        /** Restarts are spent. Tell the user, and keep the tunnel up. */
        ALERT
    }

    private final int failureThreshold;
    private final int maxRestarts;

    private int consecutiveFailures;
    private int restartsUsed;
    private boolean alerted;

    /**
     * @param failureThreshold consecutive failed probes before the daemon acts. Two, for
     *                         the same reason the Dart monitor uses two: one failure is a
     *                         lost packet, two in a row on a bounded probe is a pattern.
     * @param maxRestarts      how many times to replay the config before giving up on
     *                         fixing it silently.
     */
    public TunnelWatchdogPolicy(final int failureThreshold, final int maxRestarts) {
        this.failureThreshold = failureThreshold;
        this.maxRestarts = maxRestarts;
    }

    /**
     * Fold one probe result into the decision.
     *
     * @param carried  the probe reached the far side THROUGH the tunnel
     * @param appAlive the Flutter app process exists, so the Dart failover is running
     */
    public Action onProbe(final boolean carried, final boolean appAlive) {
        if (carried) {
            consecutiveFailures = 0;
            restartsUsed = 0;
            alerted = false;
            return Action.NONE;
        }
        consecutiveFailures++;
        if (consecutiveFailures < failureThreshold) {
            return Action.NONE;
        }
        // ⚠ COUNTED BUT NOT ACTED ON. Returning early ABOVE the increment would reset the
        // daemon's evidence every time the app happened to be alive for one tick, and an
        // app that is being killed and restarted in a loop — which is exactly what an
        // aggressive OEM does — would keep the daemon permanently at zero.
        if (appAlive) {
            return Action.NONE;
        }
        if (restartsUsed < maxRestarts) {
            restartsUsed++;
            consecutiveFailures = 0;
            return Action.RESTART;
        }
        if (alerted) {
            return Action.NONE;
        }
        alerted = true;
        return Action.ALERT;
    }

    /**
     * Forget everything. Called when a NEW tunnel is established, so the budget belongs to
     * the tunnel in hand and not to the one it replaced.
     */
    public void reset() {
        consecutiveFailures = 0;
        restartsUsed = 0;
        alerted = false;
    }

    /** For the trail and for tests: how many consecutive probes have failed. */
    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    /** For the trail and for tests: how many restarts this tunnel has already spent. */
    public int restartsUsed() {
        return restartsUsed;
    }
}
