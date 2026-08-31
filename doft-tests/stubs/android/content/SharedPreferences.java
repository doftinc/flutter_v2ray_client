package android.content;
/**
 * Android's interface, reduced to what AutoStartStore uses. Implemented by the harness.
 *
 * <p>⚠ BOTH WRITE METHODS ARE HERE ON PURPOSE, AND THEY ARE NOT INTERCHANGEABLE. apply()
 * mutates the in-memory map and queues the disk write on a background thread; its flush is
 * only guaranteed to have completed by the time the process exits NORMALLY. commit() writes
 * synchronously and returns whether it landed. A harness whose apply() is synchronous — as
 * this one's was — cannot tell the two apart, so it will pass with either, which is exactly
 * how a budget that is never persisted before the crash it is bounding gets shipped. The
 * harness fakes therefore model apply() as LOSABLE (see Prefs.dropUnflushedApplies).
 */
public interface SharedPreferences {
  interface Editor {
    Editor putString(String k, String v);
    Editor putInt(String k, int v);
    Editor putLong(String k, long v);
    Editor remove(String k);
    /** asynchronous, and lost if the process dies before the flush thread runs */
    void apply();
    /** synchronous; on disk when it returns */
    boolean commit();
  }
  String getString(String k, String def);
  int getInt(String k, int def);
  long getLong(String k, long def);
  Editor edit();
}
