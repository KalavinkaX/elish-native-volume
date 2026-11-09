package android.net;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory IPC fixture. Does not model Android SELinux or kernel credentials. */
public final class LocalSocket {
    public static final List<String> calls=Collections.synchronizedList(new ArrayList<String>());
    public static final Map<String,Integer> saved=new ConcurrentHashMap<>();
    public static final AtomicInteger closes=new AtomicInteger();
    public static volatile boolean blockFirst,reject;
    public static CountDownLatch reached=new CountDownLatch(1),release=new CountDownLatch(1);
    final BlockingQueue<Integer> response=new LinkedBlockingQueue<>();
    boolean closed,connected;
    public void setSoTimeout(int timeout)throws IOException{
        if(!connected||closed)throw new IOException("socket not created");
    }
    public void connect(LocalSocketAddress address)throws IOException{
        if(closed)throw new IOException("socket closed");
        connected=true;
    }
    void reply(String text){for(byte b:text.getBytes(StandardCharsets.US_ASCII))response.add((int)b&255);}
    void command(String command)throws IOException{
        calls.add(command);
        if(command.equals("HELLO")){reply("OK\tELISH_NATIVE_1\n");return;}
        if(command.equals("UI")){reply("DATA\tELISH_NATIVE_1\nAPP\tcom.spotify.music\t10\t1010212\t100\tsingle\nEND\n");return;}
        if(command.startsWith("SET ")){
            if(blockFirst){blockFirst=false;reached.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new IOException("Test gate timed out");}catch(InterruptedException e){throw new IOException(e);}}
            if(reject){reply("ERROR\tRejected\n");return;}
            String[] p=command.split(" ");saved.put(p[2]+":"+p[1],Integer.parseInt(p[3]));reply("OK\tSAVED\n");return;
        }
        throw new IOException("Unexpected command");
    }
    public InputStream getInputStream(){return new InputStream(){public int read()throws IOException{
        try{Integer c=response.poll(2,TimeUnit.SECONDS);if(c==null)throw new IOException("Fixture timeout");return c;}catch(InterruptedException e){throw new IOException(e);}
    }};}
    public OutputStream getOutputStream(){return new OutputStream(){
        final ByteArrayOutputStream record=new ByteArrayOutputStream();
        public void write(int c)throws IOException{if(c=='\n'){String line=new String(record.toByteArray(),StandardCharsets.US_ASCII);record.reset();command(line);}else record.write(c);}
    };}
    public void close(){if(!closed){closed=true;closes.incrementAndGet();response.add(-1);}}
}
