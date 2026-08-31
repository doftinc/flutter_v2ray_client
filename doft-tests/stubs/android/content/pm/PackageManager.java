package android.content.pm;

import android.content.Intent;

/** Only what the alert needs: the intent that reopens the app. */
public class PackageManager {
    /** Null models a device where the launch intent cannot be resolved. */
    public static Intent launchIntent = new Intent();

    public Intent getLaunchIntentForPackage(String pkg) { return launchIntent; }
}
