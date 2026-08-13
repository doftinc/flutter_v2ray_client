package android.content.res;
/** Overridden by the harness with a fake resource table. */
public class Resources {
  public int getIdentifier(String name, String defType, String defPackage){ return 0; }
  public String getResourceName(int id){ throw new RuntimeException("not found: " + id); }
  public String getResourceTypeName(int id){ throw new RuntimeException("not found: " + id); }
}
