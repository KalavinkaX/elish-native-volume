package elish.volume.nativepanel;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Persistent IPC on one worker. No su, shell, network port or audio APIs. */
final class NativeClient {
    interface Listener { void rows(List<Row> rows); void failure(String message); }
    static final class Row {
        final String pkg; final int user,uid,percent; final boolean shared;
        Row(String[] fields) throws IOException {
            if(fields.length!=6||!fields[0].equals("APP")||!fields[1].matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new IOException("Invalid app row");
            pkg=fields[1];user=number(fields[2],21474);uid=Integer.parseInt(fields[3]);percent=number(fields[4],100);
            if(uid<10000||uid/100000!=user)throw new IOException("Invalid UID");
            shared=fields[5].equals("shared");
        }
        String key(){return user+":"+pkg;}
    }
    static final class Update {
        final String pkg; final int user,percent; final long created=SystemClock.elapsedRealtime();
        Update(String pkg,int user,int percent){this.pkg=pkg;this.user=user;this.percent=percent;}
    }
    final Handler main;final Listener listener;
    final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"elish-native-client");t.setDaemon(true);return t;});
    final LinkedHashMap<String,Update> pending=new LinkedHashMap<>();
    volatile boolean visible;
    LocalSocket socket;InputStream input;OutputStream output;
    volatile long pollAt;
    long retryAt,noticeAt;
    NativeClient(Handler main,Listener listener){this.main=main;this.listener=listener;worker.scheduleWithFixedDelay(()->tick(),0,40,TimeUnit.MILLISECONDS);}
    static int number(String s,int max)throws IOException{
        if(!s.matches("0|[1-9][0-9]{0,4}"))throw new IOException("Invalid number");
        int n=Integer.parseInt(s);if(n>max)throw new IOException("Number out of range");return n;
    }
    synchronized void set(String pkg,int user,int percent){
        if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")||user<0||user>21474||percent<0||percent>100)throw new IllegalArgumentException("Invalid volume update");
        if(pending.size()>=64&&!pending.containsKey(user+":"+pkg))throw new IllegalStateException("Too many updates");
        pending.put(user+":"+pkg,new Update(pkg,user,percent));
    }
    synchronized boolean pending(String key){return pending.containsKey(key);}
    void visible(boolean value){visible=value;if(value)pollAt=0;}
    static String line(InputStream in)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(int c;(c=in.read())!=-1;){
            if(c=='\n')return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
            if(bytes.size()>=4096)throw new IOException("IPC line too long");bytes.write(c);
        }
        throw new EOFException("Backend closed connection");
    }
    void send(String command)throws IOException{output.write((command+'\n').getBytes(StandardCharsets.US_ASCII));output.flush();}
    void connect()throws IOException{
        if(socket!=null)return;
        LocalSocket next=new LocalSocket();
        try{
            next.connect(new LocalSocketAddress("elish.volume.native.v1",LocalSocketAddress.Namespace.ABSTRACT));
            next.setSoTimeout(1500);
            socket=next;input=next.getInputStream();output=next.getOutputStream();
            send("HELLO");if(!line(input).equals("OK\tELISH_NATIVE_1"))throw new IOException("Backend protocol mismatch");
        }catch(IOException e){try{next.close();}catch(IOException ignored){}socket=null;throw e;}
    }
    void close(){if(socket!=null)try{socket.close();}catch(IOException ignored){}socket=null;input=null;output=null;}
    void notice(String s){long now=SystemClock.elapsedRealtime();if(now<noticeAt)return;noticeAt=now+10000;main.post(()->listener.failure(s));}
    void tick(){
        try{
            long now=SystemClock.elapsedRealtime();
            Update update;
            synchronized(this){
                Iterator<Update> old=pending.values().iterator();
                while(old.hasNext())if(now-old.next().created>8000){old.remove();notice("应用音量提交超时，请重新展开面板后再调节");}
                update=pending.isEmpty()?null:pending.values().iterator().next();
            }
            // Finish already submitted edits even if the panel has just closed.
            if(!visible&&update==null){close();return;}
            if(now<retryAt)return;
            connect();
            if(update!=null){
                String key=update.user+":"+update.pkg;
                send("SET "+update.pkg+' '+update.user+' '+update.percent);
                String answer=line(input);
                if(!answer.equals("OK\tSAVED")&&!answer.startsWith("ERROR\t"))throw new IOException("Invalid write response");
                synchronized(this){if(pending.get(key)==update)pending.remove(key);}
                if(answer.startsWith("ERROR\t"))notice("应用音量未保存："+answer.substring(6));
                // Prefer pending gestures over polling; coalesce obsolete values.
                return;
            }
            if(visible&&now>=pollAt){
                send("UI");String header=line(input);
                if(header.startsWith("ERROR\t")){pollAt=now+1500;notice(header.substring(6));return;}
                if(!header.equals("DATA\tELISH_NATIVE_1"))throw new IOException("Invalid list response");
                List<Row> rows=new ArrayList<>();
                for(int i=0;i<=64;i++){
                    String record=line(input);
                    if(record.equals("END")){pollAt=now+700;main.post(()->{if(visible)listener.rows(rows);});return;}
                    if(i==64)throw new IOException("Too many rows");rows.add(new Row(record.split("\t",-1)));
                }
            }
        }catch(Exception e){close();retryAt=SystemClock.elapsedRealtime()+1000;notice("应用音量后台连接失败："+e.getClass().getSimpleName()+" "+e.getMessage());}
    }
}
