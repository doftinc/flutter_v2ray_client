package android.os;
import java.io.FileDescriptor;
import java.io.IOException;
public class ParcelFileDescriptor {
  public boolean closed = false;
  public void close() throws IOException { closed = true; }
  public FileDescriptor getFileDescriptor(){ return new FileDescriptor(); }
}
