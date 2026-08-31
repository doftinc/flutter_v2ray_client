package android.app;

/** Counts what the service posted, so a test can assert the user was actually told. */
public class NotificationManager {
    public static final int IMPORTANCE_DEFAULT = 3;

    public static int notifyCalls = 0;
    public static int lastNotifyId = -1;
    public static int cancelCalls = 0;
    public static int channelsCreated = 0;

    public static void reset() {
        notifyCalls = 0;
        lastNotifyId = -1;
        cancelCalls = 0;
        channelsCreated = 0;
    }

    public void notify(int id, Object notification) {
        notifyCalls++;
        lastNotifyId = id;
    }

    public void cancel(int id) { cancelCalls++; }

    public void createNotificationChannel(NotificationChannel c) { channelsCreated++; }
}
