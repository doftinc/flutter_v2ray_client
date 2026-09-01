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
  /**
   * The app's private storage, as a directory THIS JVM owns.
   *
   * ⚠ IT WAS THE LITERAL /tmp/doft-assets, AND THAT CANNOT RUN TWICE ON ONE MACHINE.
   * `/tmp` is shared by every user and every CI job on a host; a self-hosted runner keeps
   * it between jobs. The sibling fixture in ServiceHarness had the same shape and it is
   * the one that actually broke: writing its fake libtun2socks.so over a copy left by an
   * earlier run threw AccessDeniedException, one case died before its first assertion, and
   * the suite came back 315/1 on the runner while every local run was 316/0 — because on
   * macOS `java.io.tmpdir` is a PER-USER directory under /var/folders, so the collision
   * cannot happen there. A test fixture in a shared namespace is a test that passes only
   * on the machine that ran it first.
   */
  public static final File ASSETS = makeAssets();

  private static File makeAssets(){
    try {
      File d = java.nio.file.Files.createTempDirectory("doft-assets").toFile();
      d.deleteOnExit();
      return d;
    } catch (Exception e) {
      // Never fail a whole suite over a fixture path; the old behaviour is the fallback.
      File d = new File("/tmp/doft-assets");
      d.mkdirs();
      return d;
    }
  }

  public File getExternalFilesDir(String s){ return ASSETS; }
  public File getDir(String s,int m){ return ASSETS; }
  public File getFilesDir(){ return ASSETS; }
  public AssetManager getAssets(){ return new AssetManager(); }
  public String getPackageName(){ return "com.doft.vpn"; }
  public void sendBroadcast(Intent i){}
  /** The tunnel's own process asks for this to watch which network carries it. */
  public static final String CONNECTIVITY_SERVICE = "connectivity";
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
    return null;
  }
}
