package elish.volume;

import java.io.*;
import java.lang.reflect.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Root app_process helper. Uses only runtime-discovered Android interfaces. */
public final class AppVolume {
    public static final int SHAPER_ID = 0x454c4953; // "ELIS", distinct from system IDs 1..4.
    public static final class Rule {
        final int user, percent;
        final String pkg;
        Rule(int user, String pkg, int percent) {
            if (user < 0 || user > 21474) throw new IllegalArgumentException("Invalid user ID");
            if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
                throw new IllegalArgumentException("Use a full package name, e.g. com.example.player");
            if (percent < 0 || percent > 100) throw new IllegalArgumentException("Percent must be 0..100");
            this.user = user; this.pkg = pkg; this.percent = percent;
        }
        String key() { return user + ":" + pkg; }
        String line() { return user + "\t" + pkg + "\t" + percent; }
    }
    public static final class Player {
        final int id, uid, type, usage, state, session;
        final Object proxy;
        Player(int id, int uid, int type, int usage, int state, Object proxy) {
            this(id,uid,type,usage,state,proxy,0);
        }
        Player(int id, int uid, int type, int usage, int state, Object proxy, int session) {
            this.id=id; this.uid=uid; this.type=type; this.usage=usage; this.state=state; this.proxy=proxy;
            this.session=session;
        }
        boolean supported() { return (type == 1 || type == 2) && proxy != null && uid >= 10000; }
        boolean media() { return usage == 0 || usage == 1 || usage == 14; }
    }
    public interface Backend {
        List<Player> players() throws Exception;
        int uid(Rule rule) throws Exception;
        void apply(Player player, int percent, boolean replace) throws Exception;
        void clear(Player player) throws Exception;
    }
    /** Pure decision logic, also exercised against a fake backend on the host. */
    public static final class Engine {
        private final Backend backend;
        private final Map<Integer,String> applied = new HashMap<Integer,String>();
        private final Map<Integer,Integer> seen = new HashMap<Integer,Integer>();
        public int requested, unsupported, failures, matched;
        public Engine(Backend backend) { this.backend = backend; }
        public synchronized void tick(List<Player> players, Map<Integer,Integer> levels) {
            requested=unsupported=failures=matched=0;
            Set<Integer> live = new HashSet<Integer>();
            for (Player p : players) {
                live.add(p.id);
                Integer level = p.media() ? levels.get(p.uid) : null;
                if (level != null && p.state == 2) matched++;
                if (!p.supported()) {
                    if (level != null && p.state == 2) unsupported++;
                    continue;
                }
                try {
                    // Remove only our own fixed-ID curve, including after an abrupt old exit.
                    if (!Integer.valueOf(p.uid).equals(seen.get(p.id))) {
                        backend.clear(p);
                        applied.remove(p.id);
                        seen.put(p.id, p.uid);
                    }
                    if (level == null || level.intValue() == 100) {
                        if (applied.containsKey(p.id)) {
                            backend.clear(p);
                            applied.remove(p.id);
                        }
                    } else if (p.state == 2) {
                        String desired = p.uid + ":" + level;
                        if (!desired.equals(applied.get(p.id))) {
                            backend.apply(p, level, applied.containsKey(p.id));
                            applied.put(p.id, desired);
                            requested++;
                        }
                    }
                } catch (Exception e) { failures++; log("Player " + p.id + ": " + message(e)); }
            }
            seen.keySet().retainAll(live);
            applied.keySet().retainAll(live);
        }
        public synchronized void restore() throws Exception {
            int failed=0;
            for (Player p : backend.players()) if (p.supported()) {
                try { backend.clear(p); } catch (Exception e) { failed++; log("Restore: " + message(e)); }
            }
            applied.clear(); seen.clear();
            if (failed != 0) throw new IOException("Some players disappeared or rejected restore: " + failed);
        }
        public int controlledCount() { return applied.size(); }
    }
    /** Runtime API adapter; never calls setStreamVolume or PlayerProxy.setVolume. */
    public static final class AndroidBackend implements Backend {
        final Object audio, pm;
        final Method getPlayers, getUid, applyShape;
        final Class<?> configClass, configBuilderClass, opBuilderClass;
        final Object terminate, reference;
        AndroidBackend() throws Exception {
            Class<?> sm=Class.forName("android.os.ServiceManager");
            Class<?> binder=Class.forName("android.os.IBinder");
            Object ab=sm.getMethod("getService",String.class).invoke(null,"audio");
            Object pb=sm.getMethod("getService",String.class).invoke(null,"package");
            if (ab == null || pb == null) throw new IOException("Audio/package service unavailable");
            audio=Class.forName("android.media.IAudioService$Stub").getMethod("asInterface",binder).invoke(null,ab);
            pm=Class.forName("android.content.pm.IPackageManager$Stub").getMethod("asInterface",binder).invoke(null,pb);
            getPlayers=Class.forName("android.media.IAudioService").getMethod("getActivePlaybackConfigurations");
            getUid=Class.forName("android.content.pm.IPackageManager").getMethod("getPackageUid",String.class,long.class,int.class);
            configClass=Class.forName("android.media.VolumeShaper$Configuration");
            configBuilderClass=Class.forName("android.media.VolumeShaper$Configuration$Builder");
            opBuilderClass=Class.forName("android.media.VolumeShaper$Operation$Builder");
            Class<?> op=Class.forName("android.media.VolumeShaper$Operation");
            applyShape=Class.forName("android.media.PlayerProxy").getMethod("applyVolumeShaper",configClass,op);
            Object builder=opBuilderClass.getConstructor().newInstance();
            opBuilderClass.getMethod("terminate").invoke(builder);
            terminate=opBuilderClass.getMethod("build").invoke(builder);
            reference=configClass.getConstructor(int.class).newInstance(SHAPER_ID);
            // Fail the capability probe before any volume mutation if methods are missing.
            configBuilderClass.getMethod("setId",int.class);
            configBuilderClass.getMethod("setCurve",float[].class,float[].class);
            opBuilderClass.getMethod("replace",int.class,boolean.class);
            opBuilderClass.getMethod("setXOffset",float.class);
        }
        @SuppressWarnings("unchecked")
        public List<Player> players() throws Exception {
            List<?> configs=(List<?>)getPlayers.invoke(audio);
            if (configs == null) throw new IOException("AudioService returned null configurations");
            Class<?> cls=Class.forName("android.media.AudioPlaybackConfiguration");
            Method id=cls.getMethod("getPlayerInterfaceId"), uid=cls.getMethod("getClientUid"),
                type=cls.getMethod("getPlayerType"), state=cls.getMethod("getPlayerState"),
                proxy=cls.getMethod("getPlayerProxy"), attributes=cls.getMethod("getAudioAttributes"), session=cls.getMethod("getSessionId");
            Method usage=Class.forName("android.media.AudioAttributes").getMethod("getUsage");
            List<Player> result=new ArrayList<Player>();
            for (Object c : configs) {
                Object attr=attributes.invoke(c);
                result.add(new Player((Integer)id.invoke(c),(Integer)uid.invoke(c),(Integer)type.invoke(c),
                    (Integer)usage.invoke(attr),(Integer)state.invoke(c),proxy.invoke(c),(Integer)session.invoke(c)));
            }
            return result;
        }
        public int uid(Rule rule) throws Exception {
            int uid=(Integer)getUid.invoke(pm,rule.pkg,0L,rule.user);
            if (uid < 0) throw new IllegalArgumentException("Package not installed for user " + rule.user + ": " + rule.pkg);
            if (uid / 100000 != rule.user || uid % 100000 < 10000 || uid % 100000 >= 90000)
                throw new IllegalArgumentException("Only application UIDs are supported: " + uid);
            return uid;
        }
        public void clear(Player p) throws Exception { applyShape.invoke(p.proxy,reference,terminate); }
        public void apply(Player p, int percent, boolean replace) throws Exception {
            float gain=percent / 100.0f;
            Object b=configBuilderClass.getConstructor().newInstance();
            configBuilderClass.getMethod("setId",int.class).invoke(b,SHAPER_ID);
            configBuilderClass.getMethod("setInterpolatorType",int.class).invoke(b,1);
            configBuilderClass.getMethod("setDuration",long.class).invoke(b,1L);
            configBuilderClass.getMethod("setCurve",float[].class,float[].class).invoke(b,
                new float[]{0f,1f},new float[]{gain,gain});
            Object configuration=configBuilderClass.getMethod("build").invoke(b);
            Object operation=opBuilderClass.getConstructor().newInstance();
            opBuilderClass.getMethod("setXOffset",float.class).invoke(operation,1f);
            if (replace) opBuilderClass.getMethod("replace",int.class,boolean.class).invoke(operation,SHAPER_ID,false);
            applyShape.invoke(p.proxy,configuration,opBuilderClass.getMethod("build").invoke(operation));
        }
    }
    public interface SessionEffect {
        void level(int percent) throws Exception;
        void release() throws Exception;
    }
    public interface EffectFactory { SessionEffect create(int session, int percent) throws Exception; }
    /** A per-session insert effect covers mixed AAudio tracks as well as Java AudioTrack. */
    public static final class DynamicsEffect implements SessionEffect {
        final Object effect;
        final Class<?> cls;
        DynamicsEffect(int session) throws Exception { this(session,100); }
        DynamicsEffect(int session,int percent) throws Exception {
            if (session <= 0) throw new IllegalArgumentException("Global/session-zero effects forbidden");
            cls=Class.forName("android.media.audiofx.DynamicsProcessing");
            Class<?> builder=Class.forName("android.media.audiofx.DynamicsProcessing$Config$Builder");
            Class<?> config=Class.forName("android.media.audiofx.DynamicsProcessing$Config");
            Object b=builder.getConstructor(int.class,int.class,boolean.class,int.class,boolean.class,int.class,boolean.class,int.class,boolean.class)
                .newInstance(0,2,false,0,false,0,false,0,false);
            // Configure attenuation before creating/enabling the native effect: no unity-gain window.
            builder.getMethod("setInputGainAllChannelsTo",float.class).invoke(b,gainDb(percent));
            Object cfg=builder.getMethod("build").invoke(b);
            effect=cls.getConstructor(int.class,int.class,config).newInstance(0,session,cfg);
            try {
                if (!(Boolean)cls.getMethod("hasControl").invoke(effect)) throw new IOException("Another effect client owns DynamicsProcessing control");
                int result=(Integer)cls.getMethod("setEnabled",boolean.class).invoke(effect,true);
                if (result != 0 || !(Boolean)cls.getMethod("getEnabled").invoke(effect)) throw new IOException("Effect enable rejected: "+result);
            } catch (Exception e) { cls.getMethod("release").invoke(effect); throw e; }
        }
        public void level(int percent) throws Exception {
            if (!(Boolean)cls.getMethod("hasControl").invoke(effect)) throw new IOException("Effect control lost");
            float db=gainDb(percent);
            cls.getMethod("setInputGainAllChannelsTo",float.class).invoke(effect,db);
            float actual=(Float)cls.getMethod("getInputGainByChannelIndex",int.class).invoke(effect,0);
            if (!Float.isFinite(actual) || Math.abs(actual-db)>0.2f) throw new IOException("Effect gain readback mismatch: "+actual+" vs "+db);
            if (!(Boolean)cls.getMethod("getEnabled").invoke(effect)) {
                // Recover only while we still own control, with the requested gain already set.
                if (!(Boolean)cls.getMethod("hasControl").invoke(effect))throw new IOException("Effect control lost before re-enable");
                int result=(Integer)cls.getMethod("setEnabled",boolean.class).invoke(effect,true);
                if(result!=0||!(Boolean)cls.getMethod("getEnabled").invoke(effect))throw new IOException("Effect re-enable rejected: "+result);
                log("Re-enabled owned DynamicsProcessing at "+db+" dB");
            }
        }
        public void release() throws Exception { cls.getMethod("release").invoke(effect); }
    }
    public static float gainDb(int percent) {
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("Invalid percent");
        return percent==0 ? -120f : (float)(20*Math.log10(percent/100.0));
    }
    public static final class SessionEngine {
        final EffectFactory factory;
        final Map<Integer,SessionEffect> effects=new HashMap<Integer,SessionEffect>();
        final Map<Integer,Integer> owners=new HashMap<Integer,Integer>();
        final Map<Integer,Long> lastSeen=new HashMap<Integer,Long>(),checked=new HashMap<Integer,Long>();
        final Map<Integer,Integer> applied=new HashMap<Integer,Integer>();
        final Map<Integer,String> report=new TreeMap<Integer,String>();
        public SessionEngine(EffectFactory factory) { this.factory=factory; }
        public synchronized void tick(List<Player> players, Map<Integer,Integer> levels) {
            tick(players,levels,System.nanoTime()/1000000L);
        }
        public synchronized void tick(List<Player> players, Map<Integer,Integer> levels,long now) {
            Map<Integer,Integer> desired=new HashMap<Integer,Integer>();
            Map<Integer,Integer> sessions=new HashMap<Integer,Integer>();
            Set<Integer> conflicts=new HashSet<Integer>();
            Set<Integer> pending=new HashSet<Integer>(),unsafe=new HashSet<Integer>();
            report.clear();
            for (Player p:players) {
                if (p.session<=0 || p.state==0) continue;
                if (!p.media() || p.uid%100000<10000) conflicts.add(p.session);
                Integer owner=sessions.put(p.session,p.uid);
                if (owner!=null && owner!=p.uid) conflicts.add(p.session);
            }
            for (Player p:players) {
                if (!p.media() || p.uid%100000<10000 || p.state==0) continue;
                Integer level=levels.get(p.uid);
                if (level==null) continue;
                if(level==100&&!effects.containsKey(p.session))continue;
                if (p.session<=0) {
                    if(p.state==2)pending.add(p.uid);
                    continue;
                }
                if(conflicts.contains(p.session)){if(p.state==2)unsafe.add(p.uid);continue;}
                // Keep an already attached insert at unity. Releasing it at the
                // slider endpoint rebuilds the audio chain and can cause a burst.
                // Apps first seen at 100% still need no effect.
                if (p.state==2 || effects.containsKey(p.session)) {
                    desired.put(p.session,p.uid);lastSeen.put(p.session,now);
                }
            }
            // A transient AudioService snapshot gap must not remove attenuation.
            // Known ownership conflicts and explicit rule removal bypass this short grace period.
            for(Integer session:effects.keySet()){
                int uid=owners.get(session);Integer level=levels.get(uid);
                if(!desired.containsKey(session)&&level!=null&&!conflicts.contains(session)
                    &&(!sessions.containsKey(session)||sessions.get(session)==uid)
                    &&now-lastSeen.get(session)<1200) desired.put(session,uid);
            }
            for (Integer session:new ArrayList<Integer>(effects.keySet())) {
                if (!desired.containsKey(session) || !Objects.equals(owners.get(session),desired.get(session))) {
                    try { effects.get(session).release(); effects.remove(session); owners.remove(session);lastSeen.remove(session);applied.remove(session);checked.remove(session); }
                    catch (Exception ex) { log("Effect release "+session+": "+message(ex)); }
                }
            }
            for (Map.Entry<Integer,Integer> entry:desired.entrySet()) {
                int session=entry.getKey(),uid=entry.getValue();
                try {
                    SessionEffect effect=effects.get(session);
                    boolean created=effect==null;
                    if (effect==null) {
                        effect=factory.create(session,levels.get(uid)); effects.put(session,effect); owners.put(session,uid);
                    }
                    if(created||!Objects.equals(applied.get(session),levels.get(uid))||now-checked.get(session)>=1000){
                        effect.level(levels.get(uid));applied.put(session,levels.get(uid));checked.put(session,now);
                    }
                    String previous=report.get(uid);
                    if (previous==null || !previous.startsWith("ERROR:")) report.put(uid,"PARAMETER_OK: session="+session+" gain="+gainDb(levels.get(uid))+"dB");
                } catch(Exception ex) {
                    report.put(uid,"ERROR: session="+session+" "+message(ex));
                    SessionEffect failed=effects.get(session);
                    // Keep an established attenuator on a transient readback/control error.
                    // Releasing it would unexpectedly restore full output.
                    if (failed!=null&&!applied.containsKey(session)) try { failed.release(); effects.remove(session); owners.remove(session);checked.remove(session); } catch(Exception ignored) {}
                }
            }
            for(Integer uid:pending)if(!report.containsKey(uid))report.put(uid,"WAITING: audio session not ready");
            for(Integer uid:unsafe){
                String current=report.get(uid);
                report.put(uid,current!=null&&current.startsWith("PARAMETER_OK:")?current+"; WARNING: shared session skipped":"ERROR: shared audio session skipped");
            }
        }
        public synchronized void restore() throws Exception {
            for (Integer session:new ArrayList<Integer>(effects.keySet())) {
                effects.get(session).release(); effects.remove(session); owners.remove(session);lastSeen.remove(session);applied.remove(session);checked.remove(session);
            }
            report.clear();
        }
        public int controlledCount() { return effects.size(); }
    }
    public static LinkedHashMap<String,Rule> readRules(File file) throws IOException {
        LinkedHashMap<String,Rule> rules=new LinkedHashMap<String,Rule>();
        if (!file.exists()) return rules;
        if (file.length() > 65536) throw new IOException("Rules file too large (64 KiB maximum)");
        for (String line : Files.readAllLines(file.toPath(),StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] parts=line.split("\t",-1);
            try {
                if (parts.length != 3) throw new IllegalArgumentException("Expected user<TAB>package<TAB>percent");
                Rule r=new Rule(decimal(parts[0],21474),parts[1],decimal(parts[2],100));
                if (rules.put(r.key(),r) != null) throw new IllegalArgumentException("Duplicate package/user rule");
            } catch (IllegalArgumentException e) { throw new IOException("Invalid rules: " + message(e)); }
        }
        return rules;
    }
    public static Map<Integer,Integer> resolve(Collection<Rule> rules, Backend backend, boolean strict) throws Exception {
        Map<Integer,Integer> levels=new HashMap<Integer,Integer>();
        Set<Integer> conflicts=new HashSet<Integer>();
        for (Rule r : rules) {
            try {
                int uid=backend.uid(r);
                Integer old=levels.put(uid,r.percent);
                if (old != null && old.intValue() != r.percent) conflicts.add(uid);
            } catch (Exception e) {
                if (strict) throw e;
                log("Unresolved rule " + r.key() + ": " + message(e));
            }
        }
        for (Integer uid : conflicts) {
            levels.remove(uid);
            if (strict) throw new IllegalArgumentException("Shared UID " + uid + " has conflicting levels; use the same level or remove one rule");
            log("Skipped conflicting shared UID " + uid);
        }
        return levels;
    }
    public static int decimal(String value, int max) {
        if (!value.matches("0|[1-9][0-9]{0,4}")) throw new IllegalArgumentException("Use a canonical decimal integer");
        int parsed=Integer.parseInt(value);
        if (parsed > max) throw new IllegalArgumentException("Value exceeds " + max);
        return parsed;
    }
    static void atomic(File target, String text) throws IOException {
        File parent=target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        Path temp=Files.createTempFile(parent.toPath(),".elish-",".tmp");
        try {
            Files.write(temp,text.getBytes(StandardCharsets.UTF_8));
            Files.move(temp,target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    static void save(File file, Collection<Rule> rules) throws IOException {
        StringBuilder text=new StringBuilder("# user\tpackage\tpercent; 100 = no extra attenuation\n");
        for (Rule r : rules) text.append(r.line()).append('\n');
        atomic(file,text.toString());
    }
    static int currentUser() throws Exception {
        return (Integer)Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
    }
    static String message(Throwable e) {
        while (e instanceof InvocationTargetException && e.getCause() != null) e=e.getCause();
        return e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
    }
    static volatile File eventLog;
    static synchronized void log(String text) {
        String line=new Date() + " " + text;
        if (eventLog == null) { System.err.println(line); return; }
        try {
            if (eventLog.length() > 131072) {
                File previous=new File(eventLog.getPath()+".previous");
                Files.move(eventLog.toPath(),previous.toPath(),StandardCopyOption.REPLACE_EXISTING);
            }
            try (FileOutputStream out=new FileOutputStream(eventLog,true)) {
                out.write((line+"\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) { System.err.println(line); }
    }
    static void textFile(File file) throws IOException {
        if (file.isFile()) System.out.print(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        else System.out.println("Not available: " + file);
    }
    static synchronized void trace(File state,String text) throws IOException {
        File file=new File(state,"panel-lifecycle.log");
        if(file.length()>65536)Files.move(file.toPath(),new File(state,"panel-lifecycle.log.previous").toPath(),StandardCopyOption.REPLACE_EXISTING);
        try(FileOutputStream out=new FileOutputStream(file,true)){
            out.write((System.currentTimeMillis()+" "+text.replace('\n',' ') +"\n").getBytes(StandardCharsets.UTF_8));
        }
    }
    static String playbackSnapshot(Backend backend,Map<Integer,Integer> levels,SessionEngine engine) {
        StringBuilder result=new StringBuilder("targets="+levels+" effects="+engine.controlledCount()+" "+sessionReport(engine).replace('\n',';'));
        try{
            for(Player p:backend.players())if(levels.containsKey(p.uid))result.append(" [uid=").append(p.uid).append(" piid=").append(p.id).append(" state=").append(p.state).append(" usage=").append(p.usage).append(" session=").append(p.session).append(']');
        }catch(Exception ex){result.append(" snapshot_error=").append(message(ex));}
        return result.toString();
    }
    static void daemon(final File module, final File data) throws Exception {
        final File state=new File(module,"state");
        if (!state.isDirectory() && !state.mkdirs()) throw new IOException("Cannot create state directory");
        eventLog=new File(state,"app-volume-events.log");
        try (RandomAccessFile file=new RandomAccessFile(new File(state,"app-volume.lock"),"rw");
             FileChannel channel=file.getChannel()) {
            FileLock lock=channel.tryLock();
            if (lock == null) { System.out.println("App-volume daemon already running"); return; }
            try {
                final Backend backend=new AndroidBackend();
                final NativeBridge nativeBridge=new NativeBridge(module,data,(AndroidBackend)backend);
                try { nativeBridge.start(); } catch(Exception e) { log("Native IPC unavailable: "+message(e)); }
                final SessionEngine engine=new SessionEngine(new EffectFactory(){public SessionEffect create(int session,int percent) throws Exception{return new DynamicsEffect(session,percent);}});
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){ public void run(){
                    nativeBridge.close();
                    try { engine.restore(); } catch (Exception e) { log("Shutdown restore: " + message(e)); }
                }}));
                // Clear curves left by an abruptly stopped older helper, even with no rules.
                // Release stale v2.1 curves once; new backend does not send shaper requests.
                new Engine(backend).restore();
                try {
                Map<Integer,Integer> levels=Collections.emptyMap();
                long refresh=0, heartbeat=0;
                String lastConfig=null,lastStatus="";
                String panelEvent="";
                long afterOne=0,afterThree=0;
                int ruleCount=0, errors=0;
                boolean wasEnabled=false;
                while (!new File(module,"disable").exists() && !new File(module,"remove").exists() && module.isDirectory()) {
                    long now=System.currentTimeMillis();
                    boolean enabled=!new File(data,"disabled").exists();
                    try {
                        // Resolve package->UID afresh, so a reinstalled package cannot inherit an old UID rule indefinitely.
                        String config=rulesText(new File(data,"rules.tsv"));
                        boolean configChanged=!config.equals(lastConfig);
                        if (enabled && (now >= refresh || !config.equals(lastConfig) || !wasEnabled)) {
                            Collection<Rule> rules=readRules(new File(data,"rules.tsv")).values();
                            levels=resolve(rules,backend,false); ruleCount=rules.size(); refresh=now+5000;lastConfig=config;
                            // Record target values, independently of panel visibility.
                            if(configChanged)trace(state,"RULES "+levels);
                        }
                        if (enabled && (!levels.isEmpty() || engine.controlledCount() != 0)) {
                            engine.tick(backend.players(),levels);
                        } else if (wasEnabled && !enabled) engine.restore();
                        // Empty config also releases any previously controlled players.
                        wasEnabled=enabled;
                        String nextEvent=rulesText(new File(data,"panel-event.txt"));
                        if(!nextEvent.equals(panelEvent)){
                            panelEvent=nextEvent;afterOne=now+1000;afterThree=now+3000;
                            trace(state,"PANEL "+panelEvent+" "+playbackSnapshot(backend,levels,engine));
                        }
                        if(afterOne!=0&&now>=afterOne){trace(state,"AFTER_1S "+playbackSnapshot(backend,levels,engine));afterOne=0;}
                        if(afterThree!=0&&now>=afterThree){trace(state,"AFTER_3S "+playbackSnapshot(backend,levels,engine));afterThree=0;}
                        errors=0;
                        String status="enabled="+enabled+"\nrules="+ruleCount+
                            "\nresolved_uids="+levels.size()+"\nattached_session_effects="+engine.controlledCount()+
                            "\nbackend=session DynamicsProcessing; gain parameter readback, listening test still required\n"+sessionReport(engine);
                        if (now >= heartbeat || !status.equals(lastStatus)) {
                            atomic(new File(state,"app-volume-status.txt"),
                                "heartbeat_epoch_ms="+now+"\n"+status);
                            heartbeat=now+2000;lastStatus=status;
                        }
                    } catch (Exception e) {
                        log(message(e)); errors++;
                        atomic(new File(state,"app-volume-status.txt"),"ERROR="+message(e)+"\nheartbeat_epoch_ms="+now+"\n");
                        if (errors >= 10) throw new IOException("Repeated backend errors; run doctor",e);
                    }
                    Thread.sleep(50);
                }
                engine.restore();
                if (module.isDirectory()) atomic(new File(state,"app-volume-status.txt"),"stopped=true\nOwned curves were asked to terminate.\n");
                } finally { nativeBridge.close(); }
            } finally { lock.release(); }
        }
    }
    static String sessionReport(SessionEngine engine) {
        StringBuilder result=new StringBuilder();
        for (Map.Entry<Integer,String> e:engine.report.entrySet()) result.append("UID_").append(e.getKey()).append('=').append(e.getValue().replace('\n',' ')).append('\n');
        return result.toString();
    }
    static String rulesText(File file) throws IOException {
        if(!file.exists())return "";
        if(file.length()>65536)throw new IOException("Rules file too large");
        return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }
    static void setRule(File data,File rulesFile,Backend backend,Rule rule) throws Exception {
        try(RandomAccessFile f=new RandomAccessFile(new File(data,"config.lock"),"rw");
            FileChannel ch=f.getChannel();FileLock ignored=ch.lock()){
            backend.uid(rule);
            LinkedHashMap<String,Rule> rules=readRules(rulesFile);rules.put(rule.key(),rule);
            resolve(rules.values(),backend,true);save(rulesFile,rules.values());
        }
    }
    // One app_process + root shell per UI process, instead of one cold start per slider value.
    static void stream(File module,File data,File rulesFile) throws Exception {
        Backend backend=new AndroidBackend();
        BufferedReader reader=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
        String line;
        while((line=reader.readLine())!=null){
            if(line.length()>512)throw new IOException("Stream record too long");
            String[] p=line.split("\t",-1);String seq=p.length>0&&p[0].matches("[0-9]{1,18}")?p[0]:"0";
            try{
                if(new File(module,"disable").exists()||new File(module,"remove").exists())throw new IOException("Module disabled");
                if(p.length!=4)throw new IllegalArgumentException("Expected sequence/package/percent/user");
                Rule rule=new Rule(decimal(p[3],21474),p[1],decimal(p[2],100));
                setRule(data,rulesFile,backend,rule);
                System.out.println("OK\t"+seq+"\tSAVED");
            }catch(Exception ex){System.out.println("ERROR\t"+seq+"\t"+message(ex).replace('\n',' ').replace('\t',' '));}
            System.out.flush();
        }
    }
    public static void main(String[] args) {
        try { execute(args); }
        catch (Exception e) { System.err.println("ERROR: " + message(e)); System.exit(1); }
    }
    static void execute(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("Expected moduleDir dataDir command");
        File module=new File(args[0]), data=new File(args[1]), rulesFile=new File(data,"rules.tsv");
        if (!data.isDirectory() && !data.mkdirs()) throw new IOException("Cannot create private configuration directory");
        String command=args[2];
        if ("run".equals(command)) { daemon(module,data); return; }
        if ("stream".equals(command)) { stream(module,data,rulesFile); return; }
        if ("panel".equals(command)) {
            if(args.length!=4||!args[3].matches("[a-z_]{1,32}"))throw new IllegalArgumentException("Invalid panel event");
            // A marker only: never resolve/change rules, create effects, or request audio focus.
            atomic(new File(data,"panel-event.txt"),System.currentTimeMillis()+" "+args[3]);
            System.out.println("Panel event recorded; audio unchanged");return;
        }
        if ("report".equals(command)) {
            System.out.println("Elish panel/background diagnosis; contains configured package names/UIDs, no song titles\n=== Saved rules ===");textFile(rulesFile);
            System.out.println("\n=== Backend status ===");textFile(new File(module,"state/app-volume-status.txt"));
            System.out.println("\n=== Panel/playback transitions ===");textFile(new File(module,"state/panel-lifecycle.log"));return;
        }
        if ("status".equals(command)) {
            System.out.println("Elish per-app volume 3.1.0-key-test1; enabled="+!new File(data,"disabled").exists());
            File stamp=new File(module,"state/app-volume-status.txt");
            if (!stamp.exists() || System.currentTimeMillis()-stamp.lastModified() > 10000)
                System.out.println("WARNING: no fresh daemon heartbeat. Run start/doctor.");
            textFile(stamp); return;
        }
        if ("list".equals(command)) {
            Collection<Rule> rules=readRules(rulesFile).values();
            System.out.println("USER\tPACKAGE\tPERCENT (100 = no extra attenuation)");
            for (Rule r : rules) System.out.println(r.line());
            if (rules.isEmpty()) System.out.println("No per-app attenuation configured.");
            return;
        }
        if ("ui".equals(command)) {
            AndroidBackend backend=new AndroidBackend();
            Map<Integer,Integer> levels=resolve(readRules(rulesFile).values(),backend,false);
            Method packages=Class.forName("android.content.pm.IPackageManager").getMethod("getPackagesForUid",int.class);
            Set<Integer> seen=new HashSet<Integer>();
            Map<Integer,Player> representatives=new LinkedHashMap<Integer,Player>();
            for (Player p:backend.players()) {
                if (!p.media() || p.state!=2 || p.uid%100000<10000) continue;
                Player previous=representatives.get(p.uid);
                if(previous==null||(previous.session<=0&&p.session>0))representatives.put(p.uid,p);
            }
            for (Player p:representatives.values()) {
                seen.add(p.uid);
                String[] names=(String[])packages.invoke(backend.pm,p.uid);
                if (names==null || names.length==0) continue;
                for (String name:names) System.out.println("APP\t"+name+"\t"+(p.uid/100000)+"\t"+p.uid+"\t"+(levels.containsKey(p.uid)?levels.get(p.uid):100)+"\t"+p.type+"\t"+p.session);
            }
            for (Rule r:readRules(rulesFile).values()) {
                int uid;
                try { uid=backend.uid(r); } catch(Exception ignored) { continue; }
                if (!seen.contains(uid)) System.out.println("APP\t"+r.pkg+"\t"+r.user+"\t"+uid+"\t"+r.percent+"\t0\t0");
            }
            System.out.println("STATUS\t"+(new File(data,"disabled").exists()?"disabled":"enabled"));
            File status=new File(module,"state/app-volume-status.txt");
            if (!status.exists() || System.currentTimeMillis()-status.lastModified()>10000) System.out.println("NOTICE\t后台未运行：请在首页点启动服务，或重启平板");
            if (status.isFile()) for(String line:Files.readAllLines(status.toPath(),StandardCharsets.UTF_8))
                if(line.startsWith("UID_") || line.startsWith("ERROR=")) System.out.println("RESULT\t"+line.replace('\t',' '));
            return;
        }
        if ("players".equals(command) || "doctor".equals(command)) {
            Backend backend=new AndroidBackend();
            List<Player> players=backend.players();
            System.out.println("Reflection APIs reachable; player configs="+players.size());
            System.out.println("PIID\tUID\tTYPE\tUSAGE\tSTATE\tSESSION");
            for (Player p : players) System.out.println(p.id+"\t"+p.uid+"\t"+p.type+"\t"+p.usage+"\t"+p.state+"\t"+p.session);
            System.out.println("Session backend: requires positive isolated session + working DynamicsProcessing effect; includes mixed AAudio candidates.");
            if ("doctor".equals(command)) {
                System.out.println("Resolved configured UIDs: "+resolve(readRules(rulesFile).values(),backend,false));
                textFile(new File(module,"state/app-volume-status.txt"));
            }
            return;
        }
        try (RandomAccessFile f=new RandomAccessFile(new File(data,"config.lock"),"rw");
             FileChannel ch=f.getChannel(); FileLock ignored=ch.lock()) {
            if ("enable".equals(command)) { Files.deleteIfExists(new File(data,"disabled").toPath()); System.out.println("Per-app attenuation enabled"); return; }
            if ("disable".equals(command)) { atomic(new File(data,"disabled"),"disabled\n"); System.out.println("Disabled. Running daemon restores owned curves on its next tick; global volume is unchanged."); return; }
            if ("reset".equals(command)) { save(rulesFile,Collections.<Rule>emptyList()); System.out.println("All per-app rules removed; daemon will release owned curves."); return; }
            LinkedHashMap<String,Rule> rules=readRules(rulesFile);
            if ("set".equals(command)) {
                if (args.length < 5 || args.length > 6) throw new IllegalArgumentException("set PACKAGE PERCENT [USER]");
                int user=args.length==6 ? decimal(args[5],21474) : currentUser();
                Rule rule=new Rule(user,args[3],decimal(args[4],100));
                Backend backend=new AndroidBackend();
                int uid=backend.uid(rule);
                rules.put(rule.key(),rule);
                resolve(rules.values(),backend,true);
                save(rulesFile,rules.values());
                System.out.println("Saved "+rule.pkg+" user="+user+" uid="+uid+" multiplier="+rule.percent+"%. Daemon applies asynchronously.");
            } else if ("remove".equals(command)) {
                if (args.length < 4 || args.length > 5) throw new IllegalArgumentException("remove PACKAGE [USER]");
                int user=args.length==5 ? decimal(args[4],21474) : currentUser();
                Rule key=new Rule(user,args[3],100);
                rules.remove(key.key()); save(rulesFile,rules.values());
                System.out.println("Rule removed: "+key.key()+". Owned attenuation will be released.");
            } else throw new IllegalArgumentException("Unknown command: "+command);
        }
    }
}
