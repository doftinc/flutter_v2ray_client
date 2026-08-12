package android.util;
public class Log {
  public static int e(String t,String m){System.out.println("E/"+t+": "+m);return 0;}
  public static int e(String t,String m,Throwable x){System.out.println("E/"+t+": "+m+" "+x);return 0;}
  public static int w(String t,String m){System.out.println("W/"+t+": "+m);return 0;}
  public static int w(String t,String m,Throwable x){return 0;}
  public static int i(String t,String m){return 0;}
  public static int d(String t,String m){return 0;}
}
