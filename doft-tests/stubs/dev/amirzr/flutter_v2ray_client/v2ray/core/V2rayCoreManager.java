package dev.amirzr.flutter_v2ray_client.v2ray.core;
/**
 * Stands in for the class that owns libv2ray. Everything is a static hook so a harness in
 * the default package can drive it.
 *
 * <p>⚠ startCore() DOES NOT CALL BACK INTO THE SERVICE HERE. In the real core, xray's
 * startup() callback invokes v2rayServicesListener.startService() — which is what runs
 * V2rayVPNService.setup(). The harness makes that call itself, so the two halves
 * (onStartCommand deciding to restore, setup() finding out whether a tun exists) can be
 * observed separately, which is exactly where the null-establish() defect lived.
 */
public class V2rayCoreManager {
  private static final V2rayCoreManager INSTANCE = new V2rayCoreManager();
  public static V2rayCoreManager getInstance(){ return INSTANCE; }

  public static boolean coreRunning = false;
  public static boolean startCoreResult = true;
  public static int startCoreCalls = 0;
  public static int stopCoreCalls = 0;
  public static Object lastConfig = null;
  public static Object listener = null;

  public static void reset(){
    coreRunning = false; startCoreResult = true; startCoreCalls = 0;
    stopCoreCalls = 0; lastConfig = null; listener = null;
  }

  public void setUpListener(Object l){ listener = l; }
  public boolean isV2rayCoreRunning(){ return coreRunning; }
  public boolean startCore(Object config){
    startCoreCalls++;
    lastConfig = config;
    coreRunning = startCoreResult;
    return startCoreResult;
  }
  public void stopCore(){ stopCoreCalls++; coreRunning = false; }
  public Long getConnectedV2rayServerDelay(){ return -1L; }
}
