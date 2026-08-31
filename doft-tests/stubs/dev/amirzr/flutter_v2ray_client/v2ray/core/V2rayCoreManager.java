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

  public static void reset(){
    coreRunning = false; startCoreResult = true; startCoreCalls = 0;
    stopCoreCalls = 0; stopServiceCallbacks = 0; lastConfig = null; listener = null;
    totalDownloadBytes = 0L;
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
    final boolean wasRunning = coreRunning;
    // stopLoop() clears the flag before anything below runs; without this the callback
    // below would recurse forever.
    coreRunning = false;
    if (wasRunning && listener instanceof V2rayServicesListener) {
      stopServiceCallbacks++;
      ((V2rayServicesListener) listener).stopService();
    }
  }

  public Long getConnectedV2rayServerDelay(){ return -1L; }
}
