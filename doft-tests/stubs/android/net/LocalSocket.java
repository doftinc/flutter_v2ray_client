package android.net;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
public class LocalSocket {
  public void connect(LocalSocketAddress a) throws IOException { throw new IOException("no sock_path in a test JVM"); }
  public boolean isConnected(){ return false; }
  public OutputStream getOutputStream() throws IOException { throw new IOException("not connected"); }
  public void setFileDescriptorsForSend(FileDescriptor[] fds){}
  public void shutdownOutput() throws IOException {}
  public void close() throws IOException {}
}
