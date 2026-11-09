package elish.volume.nativepanel;
import android.net.LocalSocket;
import android.os.Handler;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

public final class NativeClientHostTest {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    static void waitFor(BooleanSupplier predicate)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!predicate.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(10);
        check(predicate.getAsBoolean(),"Timed out");
    }
    public static void main(String[] args)throws Exception{
        final List<String> errors=new CopyOnWriteArrayList<>();final CountDownLatch rows=new CountDownLatch(1);
        NativeClient c=new NativeClient(new Handler(),new NativeClient.Listener(){
            public void rows(List<NativeClient.Row> value){check(value.size()==1,"bad rows");rows.countDown();}
            public void failure(String message){errors.add(message);}
        });
        try{
            c.visible(true);check(rows.await(3,TimeUnit.SECONDS),"No UI reply");
            if(args[0].equals("socket-lifecycle")){
                check(errors.isEmpty(),"client failed Android socket lifecycle");
                check(LocalSocket.calls.contains("HELLO")&&LocalSocket.calls.contains("UI"),"handshake/list missing");
                LocalSocket unconnected=new LocalSocket();
                boolean rejected=false;
                try{unconnected.setSoTimeout(1500);}catch(java.io.IOException expected){rejected=true;}
                check(rejected,"fixture must reject timeout before socket creation");
                unconnected.close();
            }else if(args[0].equals("untouched")){
                c.visible(false);waitFor(()->LocalSocket.closes.get()>0);
                check(LocalSocket.calls.stream().noneMatch(s->s.startsWith("SET ")),"untouched close wrote a value");
            }else if(args[0].equals("coalesce-close")){
                for(int round=0;round<10;round++)for(int p=0;p<100;p++)c.set("com.spotify.music",10,p);
                c.set("com.spotify.music",10,61);c.visible(false);
                waitFor(()->Integer.valueOf(61).equals(LocalSocket.saved.get("10:com.spotify.music"))&&!c.pending("10:com.spotify.music"));
                waitFor(()->LocalSocket.closes.get()>0);
                check(Integer.valueOf(61).equals(LocalSocket.saved.get("10:com.spotify.music")),"IPC close changed saved value");
                check(LocalSocket.calls.stream().filter(s->s.startsWith("SET ")).count()<20,"obsolete edits queued");
            }else if(args[0].equals("late-ack")){
                LocalSocket.blockFirst=true;c.set("com.spotify.music",10,20);
                check(LocalSocket.reached.await(3,TimeUnit.SECONDS),"No in-flight update");
                c.set("com.spotify.music",10,35);c.visible(false);LocalSocket.release.countDown();
                waitFor(()->Integer.valueOf(35).equals(LocalSocket.saved.get("10:com.spotify.music"))&&!c.pending("10:com.spotify.music"));
                check(LocalSocket.calls.indexOf("SET com.spotify.music 10 20")<LocalSocket.calls.indexOf("SET com.spotify.music 10 35"),"late ACK lost newer value");
            }else if(args[0].equals("rejected")){
                LocalSocket.reject=true;c.set("com.spotify.music",10,27);
                waitFor(()->!errors.isEmpty()&&!c.pending("10:com.spotify.music"));c.visible(false);
                waitFor(()->LocalSocket.closes.get()>0);
                check(LocalSocket.saved.isEmpty(),"rejected edit persisted");
                check(LocalSocket.calls.stream().filter(s->s.startsWith("SET ")).count()==1,"definitively rejected edit retried");
            }else throw new AssertionError("Unknown scenario");
            System.out.println("PASS "+args[0]);
        }finally{c.visible(false);LocalSocket.release.countDown();c.worker.shutdownNow();c.close();}
    }
}
