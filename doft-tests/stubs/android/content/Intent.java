package android.content;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
public class Intent {
  public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
  /** The watchdog builds one of these to send itself the same START the app sends. */
  public Intent(android.content.Context c, Class<?> cls){}
  public Intent addFlags(int f){ return this; }
  public String action;
  public String pkg;
  public final Map<String,Object> extras = new HashMap<>();
  public Intent(){}
  public Intent(String action){ this.action = action; }
  public Intent setPackage(String p){ this.pkg = p; return this; }
  public Intent putExtra(String k, Serializable v){ extras.put(k,v); return this; }
  public Intent putExtra(String k, String v){ extras.put(k,v); return this; }
  public Serializable getSerializableExtra(String k){ return (Serializable) extras.get(k); }
}
