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
}
