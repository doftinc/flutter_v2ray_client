package android.net;
/**
 * ⚠ RECORDS THE REGISTRATION RATHER THAN PERFORMING ONE. What the tests need to know is that
 * the tunnel's OWN process registers a callback at all — that is the defect this stub exists
 * for: until 2026-08-21 the only NetworkCallback in the tree was in the Flutter Activity, a
 * different process that is gone the moment the app is backgrounded.
 */
public class ConnectivityManager {
  public static ConnectivityManager last = null;
  public NetworkRequest registeredRequest = null;
  public NetworkCallback registeredCallback = null;
  /** ⚠ NEVER CLEARED. The service can register and then stop itself in the same setup()
   *  (in a test JVM tun2socks cannot exec, so it does), and a harness that could only read
   *  the CURRENT registration would have nothing left to drive. */
  public NetworkCallback lastRegisteredCallback = null;
  public int registerCalls = 0;
  public int unregisterCalls = 0;

  public ConnectivityManager(){ last = this; }
  public void reset(){ registeredRequest = null; registeredCallback = null; lastRegisteredCallback = null; registerCalls = 0; unregisterCalls = 0; }

  public static class NetworkCallback {
    public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {}
    public void onLost(Network n) {}
  }

  public void registerNetworkCallback(NetworkRequest r, NetworkCallback cb){
    registeredRequest = r; registeredCallback = cb; lastRegisteredCallback = cb; registerCalls++;
  }
  public void unregisterNetworkCallback(NetworkCallback cb){
    unregisterCalls++;
    if (cb == registeredCallback) registeredCallback = null;
  }
}
