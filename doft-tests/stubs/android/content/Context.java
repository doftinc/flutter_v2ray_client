package android.content;
import java.io.File;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.pm.ApplicationInfo;
public class Context {
  public static final int MODE_PRIVATE = 0;
  public Context getApplicationContext(){ return this; }
  public SharedPreferences getSharedPreferences(String name, int mode){ return null; }
  public Resources getResources(){ return new Resources(); }
  public ApplicationInfo getApplicationInfo(){ return new ApplicationInfo(); }
  public File getExternalFilesDir(String s){ return new File("/tmp/doft-assets"); }
  public File getDir(String s,int m){ return new File("/tmp/doft-assets"); }
  public File getFilesDir(){ return new File("/tmp/doft-assets"); }
  public AssetManager getAssets(){ return new AssetManager(); }
  public String getPackageName(){ return "com.doft.vpn"; }
  public void sendBroadcast(Intent i){}
  /** The tunnel's own process asks for this to watch which network carries it. */
  public static final String CONNECTIVITY_SERVICE = "connectivity";
  /** The carry watchdog asks these two: who is alive, and how do I tell the user. */
  public static final String ACTIVITY_SERVICE = "activity";
  public static final String NOTIFICATION_SERVICE = "notification";
  /** Counts the START_SERVICE the watchdog sends itself, and keeps the last one. */
  public static int startServiceCalls = 0;
  public static Intent lastStartService = null;
  public static void resetStartService(){ startServiceCalls = 0; lastStartService = null; }
  public void startService(Intent i){ startServiceCalls++; lastStartService = i; }
  public android.content.pm.PackageManager getPackageManager(){
    return new android.content.pm.PackageManager();
  }
  /**
   * ⚠ A SINGLETON, LIKE THE REAL ONE. Handing back a fresh instance per call made the
   * register and the unregister land on DIFFERENT objects, so a test could watch the
   * service register correctly and still see a counter of zero — a fixture that reports
   * the production code broken when it is not.
   */
  private static android.net.ConnectivityManager cm;
  public Object getSystemService(String name){
    if (CONNECTIVITY_SERVICE.equals(name)) {
      if (cm == null) cm = new android.net.ConnectivityManager();
      return cm;
    }
    if (ACTIVITY_SERVICE.equals(name)) return new android.app.ActivityManager();
    if (NOTIFICATION_SERVICE.equals(name)) return new android.app.NotificationManager();
    return null;
  }
}
