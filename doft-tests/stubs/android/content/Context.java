package android.content;
import java.io.File;
import android.content.res.AssetManager;
public class Context {
  public File getExternalFilesDir(String s){ return new File("/tmp/doft-assets"); }
  public File getDir(String s,int m){ return new File("/tmp/doft-assets"); }
  public AssetManager getAssets(){ return new AssetManager(); }
}
