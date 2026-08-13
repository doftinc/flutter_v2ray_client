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
  public static void reset(){ prepareResult = null; establishResult = null; establishThrows = false; establishCalls = 0; prepareThrows = false; }
  public static Intent prepare(Context c){
    if (prepareThrows) { throw new IllegalStateException("system_server is gone"); }
    return prepareResult;
  }
  public boolean protect(int socket){ return true; }
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
}
