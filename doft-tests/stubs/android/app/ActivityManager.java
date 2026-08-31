package android.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Enough of ActivityManager to answer ONE question: is our own app process alive?
 *
 * <p>The real one has returned only the caller's own processes since API 22, which is why
 * the daemon can ask it about the Flutter process with no permission and get an
 * authoritative answer.
 */
public class ActivityManager {
    public static class RunningAppProcessInfo {
        public String processName;
        public RunningAppProcessInfo(String name) { this.processName = name; }
    }

    /** What the next getRunningAppProcesses() returns. Null models the "cannot tell" case. */
    public static List<RunningAppProcessInfo> processes = new ArrayList<>();

    /** Models the device that refuses the query outright — the "cannot tell" shape. */
    public static boolean throwOnQuery = false;

    public static void reset() {
        processes = new ArrayList<>();
        throwOnQuery = false;
    }

    public List<RunningAppProcessInfo> getRunningAppProcesses() {
        if (throwOnQuery) {
            throw new SecurityException("no");
        }
        return processes;
    }
}
