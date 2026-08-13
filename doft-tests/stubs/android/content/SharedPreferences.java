package android.content;
/** Android's interface, reduced to what AutoStartStore uses. Implemented by the harness. */
public interface SharedPreferences {
  interface Editor {
    Editor putString(String k, String v);
    Editor putInt(String k, int v);
    Editor putLong(String k, long v);
    Editor remove(String k);
    void apply();
  }
  String getString(String k, String def);
  int getInt(String k, int def);
  long getLong(String k, long def);
  Editor edit();
}
