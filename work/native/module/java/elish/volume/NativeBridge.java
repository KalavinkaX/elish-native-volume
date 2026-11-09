package elish.volume;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, authenticated local IPC. No shell commands or arbitrary file paths. */
public final class NativeBridge implements Closeable {
    public static final String SOCKET = "elish.volume.native.v1";
    final File module, data;
    final AppVolume.AndroidBackend backend;
    final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 2, 10, TimeUnit.SECONDS,
        new SynchronousQueue<Runnable>(), new ThreadPoolExecutor.AbortPolicy());
    volatile boolean closed;
    Object server;
    int systemUiUid=-1;
    NativeBridge(File module, File data, AppVolume.AndroidBackend backend) {
        this.module=module; this.data=data; this.backend=backend;
    }
    public static String readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int c; (c=in.read())!=-1;) {
            if(c=='\n') return new String(out.toByteArray(), StandardCharsets.UTF_8);
            if(c=='\r'||c<32||c>126) throw new IOException("Non-ASCII protocol record");
            if(out.size()>=max) throw new IOException("Protocol record too long");
            out.write(c);
        }
        if(out.size()!=0) throw new EOFException("Truncated protocol record");
        return null;
    }
    public static boolean allowedPeer(int expectedUid, int uid, String processName) {
        return expectedUid>0 && uid==expectedUid && "com.android.systemui".equals(processName);
    }
    int resolveSystemUiUid() throws Exception {
        int uid=(Integer)backend.getUid.invoke(backend.pm,"com.android.systemui",0L,0);
        if(uid<=0)throw new SecurityException("Cannot resolve SystemUI UID");
        return uid;
    }
    static String processName(int pid) throws IOException {
        try(InputStream in = new FileInputStream("/proc/"+pid+"/cmdline")) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            for(int i=0;i<128;i++){int c=in.read();if(c<=0)break;out.write(c);}
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    static void closeSocket(Object socket) {
        if(socket!=null)try{socket.getClass().getMethod("close").invoke(socket);}catch(Exception ignored){}
    }
    public void start() throws Exception {
        systemUiUid=resolveSystemUiUid();
        server=Class.forName("android.net.LocalServerSocket").getConstructor(String.class).newInstance(SOCKET);
        Thread accept=new Thread(()->{
            while(!closed) {
                try {
                    final Object socket=server.getClass().getMethod("accept").invoke(server);
                    try { workers.execute(()->serve(socket)); }
                    catch(RejectedExecutionException busy){closeSocket(socket);}
                } catch(Exception e){if(!closed)AppVolume.log("Native IPC accept: "+AppVolume.message(e));break;}
            }
        },"elish-native-ipc");
        accept.setDaemon(true);accept.start();
        AppVolume.log("Native IPC listening; requires SO_PEERCRED uid="+systemUiUid+" and com.android.systemui (PackageManager user 0)");
    }
    void serve(Object socket) {
        try {
            Class<?> sc=socket.getClass();
            sc.getMethod("setSoTimeout",int.class).invoke(socket,2500);
            Object peer=sc.getMethod("getPeerCredentials").invoke(socket);
            int uid=(Integer)peer.getClass().getMethod("getUid").invoke(peer);
            int pid=(Integer)peer.getClass().getMethod("getPid").invoke(peer);
            String name=processName(pid);
            if(!allowedPeer(systemUiUid,uid,name)){
                AppVolume.log("Native IPC rejected peer uid="+uid+" pid="+pid+" process="+name+" expectedUid="+systemUiUid);
                return;
            }
            InputStream in=(InputStream)sc.getMethod("getInputStream").invoke(socket);
            OutputStream out=(OutputStream)sc.getMethod("getOutputStream").invoke(socket);
            for(String line;!closed&&(line=readLine(in,512))!=null;) {
                String response;
                try { response=request(line); }
                catch(Exception e){response="ERROR\t"+AppVolume.message(e).replace('\n',' ').replace('\t',' ')+'\n';}
                if(line.equals("HELLO"))AppVolume.log("Native IPC HELLO accepted uid="+uid+" pid="+pid);
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
                if(bytes.length>65536)throw new IOException("Response too large");
                out.write(bytes);out.flush();
            }
        } catch(Exception e){if(!closed)AppVolume.log("Native IPC client: "+AppVolume.message(e));}
        finally{closeSocket(socket);}
    }
    String request(String line) throws Exception {
        if(new File(module,"disable").exists()||new File(module,"remove").exists())throw new IOException("Module disabled");
        String[] p=line.split(" ",-1);
        if(p.length==1&&p[0].equals("HELLO"))return "OK\tELISH_NATIVE_1\n";
        if(p.length==1&&p[0].equals("UI")) {
            if(new File(data,"disabled").exists())return "ERROR\tPer-app backend disabled; use Magisk Action or CLI to enable\n";
            int user=AppVolume.currentUser();
            LinkedHashMap<String,AppVolume.Rule> rules=AppVolume.readRules(new File(data,"rules.tsv"));
            Map<Integer,Integer> levels=AppVolume.resolve(rules.values(),backend,false);
            Map<Integer,AppVolume.Player> players=new LinkedHashMap<>();
            for(AppVolume.Player player:backend.players()) {
                if(!player.media()||player.state!=2||player.uid/100000!=user||player.uid%100000<10000)continue;
                AppVolume.Player old=players.get(player.uid);
                if(old==null||(old.session<=0&&player.session>0))players.put(player.uid,player);
            }
            Method packages=Class.forName("android.content.pm.IPackageManager").getMethod("getPackagesForUid",int.class);
            LinkedHashMap<Integer,String> names=new LinkedHashMap<>();
            for(AppVolume.Rule rule:rules.values())if(rule.user==user)try{names.put(backend.uid(rule),rule.pkg);}catch(Exception ignored){}
            StringBuilder result=new StringBuilder("DATA\tELISH_NATIVE_1\n");
            Set<Integer> seen=new HashSet<>();int count=0;
            for(AppVolume.Player player:players.values()) {
                String[] pkgs=(String[])packages.invoke(backend.pm,player.uid);
                if(pkgs==null||pkgs.length==0)continue;
                String name=names.containsKey(player.uid)?names.get(player.uid):pkgs[0];
                if(++count>64)break;
                result.append(row(name,user,player.uid,levels.getOrDefault(player.uid,100),pkgs.length>1));
                seen.add(player.uid);
            }
            // Saved rules supply levels/names, never extra inactive rows.
            return result.append("END\n").toString();
        }
        if(p.length==4&&p[0].equals("SET")) {
            if(new File(data,"disabled").exists())throw new IOException("Per-app backend disabled");
            int user=AppVolume.decimal(p[2],21474);
            if(user!=AppVolume.currentUser())throw new SecurityException("Foreground user changed");
            AppVolume.Rule rule=new AppVolume.Rule(user,p[1],AppVolume.decimal(p[3],100));
            saveLevel(rule);
            return "OK\tSAVED\n";
        }
        throw new IllegalArgumentException("Unknown native command");
    }
    synchronized void saveLevel(AppVolume.Rule rule) throws Exception {
        try(RandomAccessFile file=new RandomAccessFile(new File(data,"config.lock"),"rw");
            FileChannel channel=file.getChannel();FileLock lock=channel.lock()) {
            int uid=backend.uid(rule);
            File target=new File(data,"rules.tsv");
            LinkedHashMap<String,AppVolume.Rule> saved=AppVolume.readRules(target);
            // The UI exposes one slider per UID. Keep any older same-UID rules consistent.
            for(Map.Entry<String,AppVolume.Rule> e:saved.entrySet()) {
                AppVolume.Rule previous=e.getValue();
                int previousUid;
                try { previousUid=backend.uid(previous); }catch(Exception missing){continue;}
                if(previousUid==uid)e.setValue(new AppVolume.Rule(previous.user,previous.pkg,rule.percent));
            }
            saved.put(rule.key(),rule);
            AppVolume.resolve(saved.values(),backend,true);AppVolume.save(target,saved.values());
        }
    }
    static String row(String pkg,int user,int uid,int percent,boolean shared) {
        return "APP\t"+pkg+'\t'+user+'\t'+uid+'\t'+percent+'\t'+(shared?"shared":"single")+'\n';
    }
    public void close() {
        closed=true;closeSocket(server);workers.shutdownNow();
    }
}
