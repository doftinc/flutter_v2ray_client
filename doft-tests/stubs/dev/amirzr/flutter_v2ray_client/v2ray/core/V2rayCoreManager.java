package dev.amirzr.flutter_v2ray_client.v2ray.core;

import dev.amirzr.flutter_v2ray_client.v2ray.interfaces.V2rayServicesListener;

/**
 * Stands in for the class that owns libv2ray. Everything is a static hook so a harness in
 * the default package can drive it.
 *
 * <p>⚠ startCore() DOES NOT CALL BACK INTO THE SERVICE HERE. In the real core, xray's
 * startup() callback invokes v2rayServicesListener.startService() — which is what runs
 * V2rayVPNService.setup(). The harness makes that call itself, so the two halves
 * (onStartCommand deciding to restore, setup() finding out whether a tun exists) can be
 * observed separately, which is exactly where the null-establish() defect lived.
 *
 * <p>⚠ stopCore() DOES CALL BACK, BECAUSE THE REAL ONE DOES. The real body is
 * {@code stopLoop(); stopTuic(); v2rayServicesListener.stopService();} under an
 * {@code isV2rayCoreRunning()} gate — so every "the core is stopped" path in the services
 * re-enters the service through stopService(), and V2rayVPNService.stopAllProcess() calls
 * stopCore() itself. A stub that swallowed the callback tested a call graph the device
 * never runs, and would hide a stop that recurses. The termination argument is the same
 * one the real code relies on: stopLoop() clears the running flag BEFORE the callback, so
 * the re-entrant stopCore() takes the "not running" branch and the recursion is two deep.
 */
public class V2rayCoreManager {
  private static final V2rayCoreManager INSTANCE = new V2rayCoreManager();
  public static V2rayCoreManager getInstance(){ return INSTANCE; }

  public static boolean coreRunning = false;
  public static boolean startCoreResult = true;
  public static int startCoreCalls = 0;
  public static int stopCoreCalls = 0;
  /** how many of those reached v2rayServicesListener.stopService() */
  public static int stopServiceCallbacks = 0;
  public static Object lastConfig = null;
  public static Object listener = null;
  /**
   * The DOWNLINK byte counter the restore chain is bound on. ⚠ A HARNESS THAT COULD NOT
   * HOLD THIS AT ZERO WHILE HANDING BACK A TUN COULD NOT MODEL A BLACK HOLE AT ALL — the
   * whole point of the round-4 fix is that establish() succeeding and bytes moving are
   * different events, so the fake has to be able to separate them.
   */
  public static long totalDownloadBytes = 0L;

  /**
   * Held open, stopCore() parks here before it does anything — the only way to ask "did
   * onStartCommand return while the teardown was still running", which is the whole
   * property the teardown lane exists to provide. Bounded so a broken test cannot hang
   * the suite; run.sh cannot tell a hang from a slow machine.
   */
  public static volatile java.util.concurrent.CountDownLatch stopCoreGate = null;
  /** Set on ENTRY, so "started" and "finished" are separately observable. */
  public static volatile boolean stopCoreEntered = false;
  public static volatile boolean stopCoreFinished = false;
  /** Which thread ran it. A teardown on the caller's thread is the defect itself. */
  public static volatile String stopCoreThread = "";
  /**
   * The LATE gate: held, stopCore() parks AFTER it has cleared the running flag and
   * before the listener callback that closes the tun and stops the service.
   *
   * <p>⚠ THIS IS THE DANGEROUS WINDOW, AND ONLY THE LATE GATE CAN OPEN IT. The real
   * stopLoop() clears the flag before it returns, so a start arriving here sees
   * isV2rayCoreRunning() == false and walks straight past the "replace a running core"
   * branch — while stopAllProcess() is still ahead of it with mInterface.close(),
   * mInterface = null and stopSelf(). Two mutations survived a suite that could only
   * park stopCore at its ENTRY: the start-path guard and onDestroy's wait were both
   * being proved by a DIFFERENT guard that happened to fire first.
   */
  public static volatile java.util.concurrent.CountDownLatch stopCoreLateGate = null;
  /** Set only by the lane thread, so "the lane finished" cannot be satisfied inline. */
  public static volatile boolean laneStopFinished = false;

  public static void reset(){
    coreRunning = false; startCoreResult = true; startCoreCalls = 0;
    stopCoreCalls = 0; stopServiceCallbacks = 0; lastConfig = null; listener = null;
    totalDownloadBytes = 0L;
    stopCoreGate = null; stopCoreEntered = false; stopCoreFinished = false;
    stopCoreThread = ""; stopCoreLateGate = null; laneStopFinished = false;
  }

  public long getTotalDownloadBytes(){ return totalDownloadBytes; }

  public void setUpListener(Object l){ listener = l; }
  public boolean isV2rayCoreRunning(){ return coreRunning; }
  public boolean startCore(Object config){
    startCoreCalls++;
    lastConfig = config;
    coreRunning = startCoreResult;
    return startCoreResult;
  }

  public void stopCore(){
    stopCoreCalls++;
    stopCoreEntered = true;
    stopCoreThread = Thread.currentThread().getName();
    final java.util.concurrent.CountDownLatch gate = stopCoreGate;
    if (gate != null) {
      try {
        gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    final boolean wasRunning = coreRunning;
    // stopLoop() clears the flag before anything below runs; without this the callback
    // below would recurse forever.
    coreRunning = false;
    final java.util.concurrent.CountDownLatch late = stopCoreLateGate;
    if (late != null) {
      try {
        late.await(10, java.util.concurrent.TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (wasRunning && listener instanceof V2rayServicesListener) {
      stopServiceCallbacks++;
      ((V2rayServicesListener) listener).stopService();
    }
    stopCoreFinished = true;
    if ("v2ray-teardown".equals(Thread.currentThread().getName())) {
      laneStopFinished = true;
    }
  }

  public Long getConnectedV2rayServerDelay(){ return -1L; }
}
