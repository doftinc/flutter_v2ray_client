package android.net;
import java.util.HashSet;
import java.util.Set;
/**
 * ⚠ THE CONSTANTS CARRY THE REAL VALUES. A stub that renumbered them would let a test pass
 * while the shipped code asked the framework about a different capability entirely.
 */
public class NetworkCapabilities {
  public static final int NET_CAPABILITY_INTERNET = 12;
  public static final int NET_CAPABILITY_NOT_VPN = 15;
  public static final int NET_CAPABILITY_VALIDATED = 16;
  private final Set<Integer> caps = new HashSet<>();
  public NetworkCapabilities add(int c){ caps.add(c); return this; }
  public boolean hasCapability(int c){ return caps.contains(c); }
}
