package androidx.core.app;

import android.content.Context;

/** A builder that builds nothing — the assertions are on NotificationManager. */
public class NotificationCompat {
    public static class Builder {
        public Builder(Context c, String channelId) {}
        public Builder setSmallIcon(int i) { return this; }
        public Builder setContentTitle(CharSequence s) { return this; }
        public Builder setContentText(CharSequence s) { return this; }
        public Builder setAutoCancel(boolean b) { return this; }
        public Builder setOnlyAlertOnce(boolean b) { return this; }
        public Builder setContentIntent(Object p) { return this; }
        public Object build() { return new Object(); }
    }
}
