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
      deleteTreeOnExit(d);
      return d;
    } catch (Exception e) {
      // ⚠ NO SILENT FALLBACK, AND THE ONE THAT WAS HERE RESTORED THE DEFECT THIS FIELD
      // EXISTS TO DOCUMENT. It caught the exception and returned `new File("/tmp/doft-assets")`
      // — the exact pre-fix path — with `mkdirs()`'s return value dropped. On the Linux
      // runner that is the shared namespace again, and the sibling case that checks this
      // (ServiceHarness case 50) would have passed on it, because its second clause
      // ACCEPTED any path under `/tmp/doft`. Two guards shielding each other, so the bug
      // could come back with the suite green.
      //
      // A fixture that cannot be made private is a suite that cannot be trusted, so it
      // fails here and says why. `createTempDirectory` fails only if the temp directory is
      // unwritable, which is not a condition any run should paper over.
      throw new ExceptionInInitializerError(
          "cannot create a private assets fixture under java.io.tmpdir="
              + System.getProperty("java.io.tmpdir") + ": " + e
              + " — refusing to fall back to the shared /tmp/doft-assets this field exists "
              + "to avoid");
    }
  }

  /**
   * Remove [d] and everything under it when this JVM exits.
   *
   * ⚠ `File.deleteOnExit()` DOES NOT DO THIS, and the comment that used to imply it was
   * wrong in the direction that leaks. It unlinks a DIRECTORY only when the directory is
   * empty at exit; the sibling tun2socks fixture always holds `libtun2socks.so` and
   * `execs.log`, so every run left one more 0700 directory — each containing a file made
   * executable for everyone — behind in the runner's temp namespace, unbounded. Measured
   * on 2026-09-01: several from a single day's runs, all still present.
   */
  public static void deleteTreeOnExit(final File d){
    Runtime.getRuntime().addShutdownHook(new Thread(){
      @Override public void run(){ deleteTree(d); }
    });
  }

  private static void deleteTree(File d){
    File[] kids = d.listFiles();
    if (kids != null) {
      for (File k : kids) deleteTree(k);
    }
    // Best effort: a fixture we cannot remove is not worth failing an exiting JVM over,
    // and there is no reporter left to tell at this point anyway.
    d.delete();
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
