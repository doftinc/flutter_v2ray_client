package android.content.res;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
public class AssetManager {
  public String[] list(String p){ return new String[0]; }
  public InputStream open(String n){ return new ByteArrayInputStream(new byte[0]); }
}
