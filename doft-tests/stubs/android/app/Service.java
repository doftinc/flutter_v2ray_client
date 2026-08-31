package android.app;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
/** Android's Service, reduced to what the two service classes call. */
public class Service extends Context {
  public static final int START_STICKY = 1;
  public static final int START_NOT_STICKY = 2;
  /** Observable by the harness: the ONLY thing that actually ends a service. */
  public boolean stopSelfCalled = false;
  public boolean stopForegroundCalled = false;
  public int onDestroyCalls = 0;
  public void onCreate(){}
  public void onDestroy(){ onDestroyCalls++; }
  public int onStartCommand(Intent i,int flags,int startId){ return START_STICKY; }
  public IBinder onBind(Intent i){ return null; }
  public void stopSelf(){ stopSelfCalled = true; }
  public void stopForeground(boolean removeNotification){ stopForegroundCalled = true; }
}
