import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * A SharedPreferences fake in which <b>apply() can be lost and commit() cannot</b>.
 *
 * <p>⚠ THIS IS THE POINT OF THE CLASS, AND THE REASON A DEFECT SURVIVED TWO ROUNDS HERE.
 * The previous fake landed apply() straight into the map, synchronously. On a device
 * apply() only mutates the in-memory map and hands the disk write to a background thread,
 * and that write is guaranteed to have completed only when the process exits NORMALLY.
 * A harness that cannot tell apply() from commit() passes with either, so the one write
 * that has to survive an ABNORMAL exit — the restore budget being charged before a config
 * that may take :RunSoLibV2RayDaemon down with it — could be written asynchronously,
 * never land, and leave the restart loop the budget exists to end running forever, with a
 * green suite over it.
 *
 * <p>The model:
 * <ul>
 *   <li>{@link #map} is what is ON DISK. It survives {@link #processDied()}.</li>
 *   <li>{@link #unflushed} is what apply() has staged in memory while
 *       {@link #dropUnflushedApplies} is set. Reads in THIS process see it — that is why
 *       the bug is invisible without a modelled death — and {@link #processDied()} throws
 *       it away.</li>
 *   <li>commit() writes through to {@link #map} and returns true.</li>
 * </ul>
 */
public class LosablePrefs implements SharedPreferences {

    /** a removal that has been apply()'d but not flushed: the key is gone in memory only */
    private static final Object TOMBSTONE = new Object();

    /** on disk; survives a process death */
    public final Map<String, Object> map = new HashMap<>();
    /** staged by an unflushed apply(); visible to this process, lost on death */
    public final Map<String, Object> unflushed = new HashMap<>();

    /** every apply() rewrites the whole XML on a device; this counts them */
    public int applies = 0;
    public int commits = 0;

    /** when set, apply() stages in memory and the flush never happens */
    public boolean dropUnflushedApplies = false;

    /** the process is killed before apply()'s flush thread got to run */
    public void processDied() {
        unflushed.clear();
    }

    private Object raw(final String k) {
        final Object v = unflushed.get(k);
        if (v != null) {
            return v == TOMBSTONE ? null : v;
        }
        return map.get(k);
    }

    public String getString(String k, String def) {
        Object v = raw(k);
        return v instanceof String ? (String) v : def;
    }

    public int getInt(String k, int def) {
        Object v = raw(k);
        return v instanceof Integer ? (Integer) v : def;
    }

    public long getLong(String k, long def) {
        Object v = raw(k);
        return v instanceof Long ? (Long) v : def;
    }

    public Editor edit() {
        final Map<String, Object> staged = new HashMap<>();
        final ArrayList<String> removed = new ArrayList<>();
        return new Editor() {
            public Editor putString(String k, String v) { staged.put(k, v); return this; }
            public Editor putInt(String k, int v) { staged.put(k, v); return this; }
            public Editor putLong(String k, long v) { staged.put(k, v); return this; }
            public Editor remove(String k) { removed.add(k); return this; }

            public void apply() {
                applies++;
                if (dropUnflushedApplies) {
                    for (String k : removed) {
                        unflushed.put(k, TOMBSTONE);
                    }
                    unflushed.putAll(staged);
                    return;
                }
                land();
            }

            public boolean commit() {
                commits++;
                land();
                return true;
            }

            private void land() {
                for (String k : removed) {
                    map.remove(k);
                    unflushed.remove(k);
                }
                for (Map.Entry<String, Object> e : staged.entrySet()) {
                    map.put(e.getKey(), e.getValue());
                    unflushed.remove(e.getKey());
                }
            }
        };
    }
}
