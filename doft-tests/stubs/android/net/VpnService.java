package android.net;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
/**
 * ⚠ THE POINT OF THIS STUB IS THAT establish() CAN RETURN NULL WITHOUT THROWING, which is
 * what the real one does when the caller is not (or is no longer) the prepared VPN. The
 * shipped code used to treat that as success.
 */
public class VpnService extends Service {
  /** non-null => "ask the user for consent", which a system-initiated start cannot do. */
  public static Intent prepareResult = null;
  /** what Builder.establish() hands back; null models a lost VPN slot. */
  public static ParcelFileDescriptor establishResult = null;
  /** the OTHER failure mode: some devices throw instead. */
  public static boolean establishThrows = false;
  public static int establishCalls = 0;
  /**
   * ⚠ prepare() CAN THROW, NOT ONLY RETURN NON-NULL. It is a binder call into the
   * ConnectivityService; a dead system_server, a DeadObjectException or an OEM policy
   * hook surfaces as a RuntimeException in OUR process. restoreLastKnownGood() catches
   * it and must stop the service, because the alternative is a VpnService that stays
   * alive with no tun while the core is about to be started.
   */
  public static boolean prepareThrows = false;
  public static void reset(){ protectResult = true; protectCalls = 0; lastProtectedFd = -1; prepareResult = null; establishResult = null; establishThrows = false; establishCalls = 0; prepareThrows = false; }
  public static Intent prepare(Context c){
    if (prepareThrows) { throw new IllegalStateException("system_server is gone"); }
    return prepareResult;
  }
  /** ⚠ SCRIPTABLE. It used to be a hard-coded `true`, so no test could express a
   *  REFUSAL — and a refusal is the only interesting thing VpnService.protect does. */
  public static boolean protectResult = true;
  public static int protectCalls = 0;
  public static int lastProtectedFd = -1;
  public boolean protect(int socket){ protectCalls++; lastProtectedFd = socket; return protectResult; }
  public void onRevoke(){}
  public class Builder {
    public Builder setSession(String s){ return this; }
    public Builder setMtu(int m){ return this; }
    public Builder addAddress(String a,int p){ return this; }
    public Builder addRoute(String a,int p){ return this; }
    public Builder addDnsServer(String a){ return this; }
    public Builder addDisallowedApplication(String p){ return this; }
    public Builder setMetered(boolean b){ return this; }
    public ParcelFileDescriptor establish(){
      establishCalls++;
      if (establishThrows) { throw new IllegalStateException("VPN slot taken"); }
      return establishResult;
    }
  }

  /** ⚠ RECORDED, NOT PERFORMED. What matters to a test is WHAT the service declares and
   *  when it clears it — the real call is a binder hop with no observable return. */
  public static Network[] declaredUnderlying = null;
  public static int declareCalls = 0;
  public void setUnderlyingNetworks(Network[] ns){ declaredUnderlying = ns; declareCalls++; }
}
