package android.net;
import java.util.ArrayList;
import java.util.List;
public class NetworkRequest {
  public final List<Integer> required = new ArrayList<>();
  public static class Builder {
    private final NetworkRequest r = new NetworkRequest();
    public Builder addCapability(int c){ r.required.add(c); return this; }
    public NetworkRequest build(){ return r; }
  }
}
