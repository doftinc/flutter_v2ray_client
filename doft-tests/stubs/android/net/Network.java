package android.net;
/** Opaque handle. The tests only ever check identity, which is all the real one guarantees. */
public class Network {
  public final int id;
  public Network(int id){ this.id = id; }
  @Override public String toString(){ return "Network[" + id + "]"; }
}
