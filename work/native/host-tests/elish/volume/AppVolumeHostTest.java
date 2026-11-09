package elish.volume;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import android.media.*;
import android.content.pm.IPackageManager;

public final class AppVolumeHostTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static AppVolume.Player player(int id,int uid,int type,int usage,int state) {
        return new AppVolume.Player(id,uid,type,usage,state,new Object());
    }
    static class Fake implements AppVolume.Backend {
        List<AppVolume.Player> players=new ArrayList<AppVolume.Player>();
        List<String> calls=new ArrayList<String>();
        Map<String,Integer> uids=new HashMap<String,Integer>();
        public List<AppVolume.Player> players(){return players;}
        public int uid(AppVolume.Rule r){Integer uid=uids.get(r.key());if(uid==null)throw new IllegalArgumentException("missing");return uid;}
        public void clear(AppVolume.Player p){calls.add("clear:"+p.id);}
        public void apply(AppVolume.Player p,int percent,boolean replace){calls.add("apply:"+p.id+":"+percent+":"+replace);}
    }
    static Map<Integer,Integer> levels(int... values){Map<Integer,Integer> m=new HashMap<Integer,Integer>();for(int i=0;i<values.length;i+=2)m.put(values[i],values[i+1]);return m;}
    static int applyCount(Fake fake){int n=0;for(String c:fake.calls)if(c.startsWith("apply:"))n++;return n;}
    static void throwsBad(Runnable r){try{r.run();throw new AssertionError("Expected invalid input");}catch(IllegalArgumentException ok){}}
    static class SessionFake implements AppVolume.EffectFactory {
        Map<Integer,Eff> items=new HashMap<Integer,Eff>();
        int creates;
        boolean failCreate,failLevel;
        class Eff implements AppVolume.SessionEffect {
            int level=100;boolean released;
            public void level(int p) throws Exception {if(failLevel)throw new IOException("parameter rejected");level=p;}
            public void release(){released=true;}
        }
        public AppVolume.SessionEffect create(int session,int percent) throws Exception {
            if(failCreate)throw new IOException("no effect engine");creates++;Eff effect=new Eff();effect.level=percent;items.put(session,effect);return effect;
        }
    }
    public static void main(String[] args) throws Exception {
        String test=args[0]; File temp=new File(args[1]);
        Fake f=new Fake();AppVolume.Engine e=new AppVolume.Engine(f);
        f.players.add(player(1,10001,1,1,2));f.players.add(player(2,10002,2,1,2));
        if(test.startsWith("session-")){
            SessionFake sf=new SessionFake();AppVolume.SessionEngine se=new AppVolume.SessionEngine(sf);
            List<AppVolume.Player> players=new ArrayList<AppVolume.Player>();
            players.add(new AppVolume.Player(375,10212,13,1,2,null,409));
            players.add(new AppVolume.Player(311,10207,1,1,2,new Object(),345));
            if(test.equals("session-aaudio")){
                se.tick(players,levels(10212,30));check(sf.items.get(409).level==30,"AAudio excluded");check(!sf.items.containsKey(345),"other app changed");
                check(se.report.get(10212).startsWith("PARAMETER_OK:"),"success state");
            }else if(test.equals("session-multiple")){
                players.add(new AppVolume.Player(376,10212,13,1,2,null,410));
                se.tick(players,levels(10212,30,10207,80));check(sf.items.size()==3,"multiple sessions");check(sf.items.get(345).level==80,"second app");
            }else if(test.equals("session-update")){
                se.tick(players,levels(10212,30));se.tick(players,levels(10212,5));check(sf.creates==1,"effect recreated instead of updated");check(sf.items.get(409).level==5,"gain not updated");
            }else if(test.equals("session-restore")){
                se.tick(players,levels(10212,0));check(sf.items.get(409).level==0,"zero level");
                se.tick(players,levels(10212,100));check(!sf.items.get(409).released&&sf.items.get(409).level==100&&se.controlledCount()==1,"100 rebuilt chain instead of unity gain");
                se.tick(players,levels());check(sf.items.get(409).released&&se.controlledCount()==0,"rule removal did not release");
            }else if(test.equals("session-unity-reuse")){
                se.tick(players,levels(10212,100,10207,100),0);check(sf.creates==0,"untouched unity created effect");
                for(int i=0;i<10;i++){
                    se.tick(players,levels(10212,30,10207,70),i*100);
                    SessionFake.Eff original=sf.items.get(409);
                    se.tick(players,levels(10212,100,10207,70),i*100+50);
                    check(sf.items.get(409)==original&&!original.released&&original.level==100,"unity replaced effect");
                    check(sf.items.get(345).level==70,"other app level changed");
                }
                check(sf.creates==2,"drag across unity churned effects");
                players.remove(0);se.tick(players,levels(10212,100,10207,70),1050);
                check(!sf.items.get(409).released,"unity snapshot gap rebuilt chain");
                se.tick(players,levels(10212,100,10207,70),2300);
                check(sf.items.get(409).released,"ended unity session leaked");
            }else if(test.equals("session-zero-rejected")){
                players.clear();players.add(new AppVolume.Player(1,10212,13,1,2,null,0));
                se.tick(players,levels(10212,0));check(sf.creates==0,"global effect attempted");check(se.report.get(10212).startsWith("WAITING:"),"unready session must be waiting");
            }else if(test.equals("session-shared-rejected")){
                players.add(new AppVolume.Player(376,10207,1,1,2,null,409));
                se.tick(players,levels(10212,0));check(sf.creates==0,"shared session attenuated");
            }else if(test.equals("session-alarm-excluded")){
                players.add(new AppVolume.Player(376,10212,1,4,2,null,409));
                se.tick(players,levels(10212,0));check(sf.creates==0,"shared alarm session attenuated");
            }else if(test.equals("session-create-error")){
                sf.failCreate=true;se.tick(players,levels(10212,30));check(se.controlledCount()==0,"failure tracked as success");check(se.report.get(10212).contains("no effect engine"),"error not visible");
            }else if(test.equals("session-readback-error")){
                sf.failLevel=true;se.tick(players,levels(10212,30));check(sf.items.get(409).released&&se.controlledCount()==0,"failed effect leaked");check(se.report.get(10212).startsWith("ERROR:"),"parameter failure hidden");
            }else if(test.equals("session-disappears")){
                se.tick(players,levels(10212,30),0);players.remove(0);se.tick(players,levels(10212,30),1300);check(sf.items.get(409).released,"old session leak");
            }else if(test.equals("session-snapshot-gap")){
                se.tick(players,levels(10212,30),0);AppVolume.Player spotify=players.remove(0);
                se.tick(players,levels(10212,5),500);check(!sf.items.get(409).released&&sf.items.get(409).level==5,"snapshot gap caused unattenuated output");
                players.add(spotify);se.tick(players,levels(10212,5),900);check(sf.creates==1,"effect recreated during snapshot gap");
            }else if(test.equals("session-mixed-valid-zero")){
                players.add(new AppVolume.Player(400,10212,13,1,2,null,0));
                se.tick(players,levels(10212,30));check(sf.items.get(409).level==30,"valid session not processed");
                check(se.report.get(10212).startsWith("PARAMETER_OK:"),"unready sibling falsely reported whole app failure");
            }else if(test.equals("session-retain-on-error")){
                se.tick(players,levels(10212,30),0);sf.failLevel=true;
                se.tick(players,levels(10212,5),50);check(!sf.items.get(409).released,"transient error removed attenuation");
                check(se.report.get(10212).startsWith("ERROR:"),"real failure hidden");
                sf.failLevel=false;se.tick(players,levels(10212,5),100);check(sf.creates==1&&sf.items.get(409).level==5,"recovery recreated effect");
            }else if(test.equals("session-remove-during-gap")){
                se.tick(players,levels(10212,30),0);players.remove(0);
                se.tick(players,levels(),10);check(sf.items.get(409).released,"grace delayed explicit rule removal");
            }else if(test.equals("session-conflict-during-grace")){
                se.tick(players,levels(10212,30),0);players.add(new AppVolume.Player(400,10207,1,1,2,null,409));
                se.tick(players,levels(10212,30),10);check(sf.items.get(409).released,"grace retained conflicting session");
            }else if(test.equals("session-reenable-owned")){
                AppVolume.DynamicsEffect fx=new AppVolume.DynamicsEffect(409,5);
                android.media.audiofx.DynamicsProcessing.last.enabled=false;fx.level(5);
                check(android.media.audiofx.DynamicsProcessing.last.enabled,"owned disabled effect not recovered");
                check(Math.abs(android.media.audiofx.DynamicsProcessing.last.gainWhenEnabled-AppVolume.gainDb(5))<0.001,"recovery enabled at unity gain");fx.release();
            }else if(test.equals("session-reenable-no-control")){
                AppVolume.DynamicsEffect fx=new AppVolume.DynamicsEffect(409,5);
                android.media.audiofx.DynamicsProcessing.last.enabled=false;android.media.audiofx.DynamicsProcessing.controlled=false;
                try{fx.level(5);throw new AssertionError("stole effect control");}catch(IOException expected){}
                check(!android.media.audiofx.DynamicsProcessing.last.enabled,"re-enabled without control");fx.release();
            }else if(test.equals("session-preloaded-gain")){
                AppVolume.DynamicsEffect fx=new AppVolume.DynamicsEffect(409,5);
                check(Math.abs(android.media.audiofx.DynamicsProcessing.last.gainWhenEnabled-AppVolume.gainDb(5))<0.001,"effect enabled at unity before attenuation");fx.release();
            }else if(test.equals("session-gain-mapping")){
                check(AppVolume.gainDb(0)==-120f,"zero floor");check(AppVolume.gainDb(100)==0f,"unity");
                check(Math.abs(AppVolume.gainDb(50)+6.0206f)<0.001,"amplitude dB mapping");
                for(int i=1;i<100;i++)check(AppVolume.gainDb(i)<AppVolume.gainDb(i+1),"gain monotonic");
            }else if(test.equals("session-dynamics-adapter")){
                AppVolume.DynamicsEffect fx=new AppVolume.DynamicsEffect(409);fx.level(30);
                check(Math.abs(android.media.audiofx.DynamicsProcessing.last.gain-AppVolume.gainDb(30))<0.001,"adapter gain");fx.release();
                check(android.media.audiofx.DynamicsProcessing.last.released,"adapter release");
                android.media.audiofx.DynamicsProcessing.mismatch=true;
                fx=new AppVolume.DynamicsEffect(409);
                try{fx.level(30);throw new AssertionError("mismatch accepted");}catch(IOException ok){}
                fx.release();
                android.media.audiofx.DynamicsProcessing.controlled=false;
                try{new AppVolume.DynamicsEffect(409);throw new AssertionError("no-control accepted");}catch(IOException ok){}
                check(android.media.audiofx.DynamicsProcessing.last.released,"no-control leak");
            }else throw new AssertionError("Unknown session test");
        }else if(test.equals("isolation")){
            e.tick(f.players,levels(10001,30));
            check(f.calls.contains("apply:1:30:false"),"target");check(applyCount(f)==1,"unconfigured app changed");
        }else if(test.equals("multiple")){
            f.players.add(player(3,10001,1,14,2));e.tick(f.players,levels(10001,30,10002,80));
            check(applyCount(f)==3,"all players for configured UIDs");
        }else if(test.equals("deduplicate")){
            e.tick(f.players,levels(10001,30));e.tick(f.players,levels(10001,30));check(applyCount(f)==1,"stacked curve");
            e.tick(f.players,levels(10001,50));check(f.calls.contains("apply:1:50:true"),"replace fixed id");
        }else if(test.equals("restore")){
            e.tick(f.players,levels(10001,0));f.players.set(0,player(1,10001,1,1,3));
            e.tick(f.players,Collections.<Integer,Integer>emptyMap());check(e.controlledCount()==0,"paused player not restored");
            check(Collections.frequency(f.calls,"clear:1")==2,"remove owned curve");
            e.restore();check(e.controlledCount()==0,"shutdown restore");
        }else if(test.equals("excluded")){
            f.players.clear();f.players.add(player(1,10001,1,4,2));f.players.add(player(2,10001,13,14,2));
            f.players.add(player(3,1000,1,1,2));e.tick(f.players,levels(10001,0,1000,0));
            check(applyCount(f)==0,"alarm/system/unsupported changed");check(e.unsupported==2,"unsupported count");
        }else if(test.equals("hundred")){
            e.tick(f.players,levels(10001,100));check(applyCount(f)==0,"100 must leave unchanged");
            e.tick(f.players,levels(10001,40));e.tick(f.players,levels(10001,100));check(e.controlledCount()==0,"100 releases attenuation");
        }else if(test.equals("reused-player")){
            e.tick(f.players,levels(10001,30));f.players.set(0,player(1,10003,1,1,2));e.tick(f.players,levels(10001,30));
            check(e.controlledCount()==0,"reused player id inherited rule");
        }else if(test.equals("shared-uid")){
            AppVolume.Rule a=new AppVolume.Rule(0,"com.test.a",30),b=new AppVolume.Rule(0,"com.test.b",70);
            f.uids.put(a.key(),10001);f.uids.put(b.key(),10001);
            check(AppVolume.resolve(Arrays.asList(a,b),f,false).isEmpty(),"conflict must be skipped");
            try{AppVolume.resolve(Arrays.asList(a,b),f,true);throw new AssertionError("conflict accepted");}catch(IllegalArgumentException ok){}
            b=new AppVolume.Rule(0,"com.test.b",30);check(AppVolume.resolve(Arrays.asList(a,b),f,true).get(10001)==30,"same shared uid level");
        }else if(test.equals("validation")){
            for(final String bad:new String[]{"-1","101","01","+1","1.5","999999999999999999","1;id"})
                throwsBad(new Runnable(){public void run(){AppVolume.decimal(bad,100);}});
            throwsBad(new Runnable(){public void run(){new AppVolume.Rule(0,"com.test;id",30);}});
            for(int i=0;i<=100;i++)check(AppVolume.decimal(""+i,100)==i,"integer range");
        }else if(test.equals("storage")){
            File file=new File(temp,"rules.tsv");
            AppVolume.save(file,Arrays.asList(new AppVolume.Rule(0,"com.test.a",0),new AppVolume.Rule(10,"com.test.a",80)));
            check(AppVolume.readRules(file).size()==2,"multi-user persistence");
            Files.write(file.toPath(),"0\tcom.test.a\t30\n0\tcom.test.a\t40\n".getBytes("UTF-8"));
            try{AppVolume.readRules(file);throw new AssertionError("duplicate accepted");}catch(IOException ok){}
        }else if(test.equals("adapter")){
            PlayerProxy proxy=new PlayerProxy();AudioPlaybackConfiguration.items.add(new AudioPlaybackConfiguration(9,10001,1,1,2,proxy));
            AppVolume.AndroidBackend b=new AppVolume.AndroidBackend();AppVolume.Player p=b.players().get(0);
            check(p.id==9&&p.uid==10001&&p.supported(),"adapter identity");b.clear(p);
            check(proxy.configurations.get(0).id==AppVolume.SHAPER_ID&&proxy.operations.get(0).terminate,"terminate only owned id");
            for(int i=0;i<100;i++){
                b.apply(p,i,i!=0);VolumeShaper.Configuration c=proxy.configurations.get(i+1);VolumeShaper.Operation op=proxy.operations.get(i+1);
                check(c.id==AppVolume.SHAPER_ID&&Math.abs(c.volumes[0]-i/100f)<0.00001,"wrong gain/id");
                check(c.volumes[0]==c.volumes[1]&&c.duration==1&&op.offset==1,"not constant curve");
                check(op.replaceId==(i==0?-1:AppVolume.SHAPER_ID),"replace id");
            }
        }else if(test.equals("commands")){
            File module=new File(temp,"module"),data=new File(temp,"data");module.mkdirs();
            IPackageManager.Stub.uids.put("10:com.test.a",1010001);
            AppVolume.execute(new String[]{module.toString(),data.toString(),"set","com.test.a","30"});
            check(AppVolume.readRules(new File(data,"rules.tsv")).get("10:com.test.a").percent==30,"foreground user default");
            AppVolume.execute(new String[]{module.toString(),data.toString(),"disable"});check(new File(data,"disabled").exists(),"disable");
            AppVolume.execute(new String[]{module.toString(),data.toString(),"enable"});check(!new File(data,"disabled").exists(),"enable");
            AppVolume.execute(new String[]{module.toString(),data.toString(),"remove","com.test.a","10"});
            check(AppVolume.readRules(new File(data,"rules.tsv")).isEmpty(),"remove");
        }else if(test.equals("daemon-fast-update")||test.equals("daemon-panel-independent")){
            final File module=new File(temp,"module"),data=new File(temp,"data");module.mkdirs();data.mkdirs();
            IPackageManager.Stub.uids.put("0:com.spotify.music",10212);
            AudioPlaybackConfiguration.items.add(new AudioPlaybackConfiguration(309,10212,13,1,2,new PlayerProxy()));
            AppVolume.save(new File(data,"rules.tsv"),Arrays.asList(new AppVolume.Rule(0,"com.spotify.music",30)));
            final java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread daemon=new Thread(new Runnable(){public void run(){try{AppVolume.daemon(module,data);}catch(Throwable t){error.set(t);}}});
            daemon.start();
            try{
                File status=new File(module,"state/app-volume-status.txt");long deadline=System.nanoTime()+3000000000L;
                while(System.nanoTime()<deadline){if(status.exists()&&AppVolume.rulesText(status).contains("PARAMETER_OK:"))break;Thread.sleep(10);}
                check(status.exists()&&AppVolume.rulesText(status).contains("PARAMETER_OK:"),"daemon did not attach");
                Object handle=android.media.audiofx.DynamicsProcessing.last;
                if(test.equals("daemon-panel-independent")){
                    byte[] before=Files.readAllBytes(new File(data,"rules.tsv").toPath());
                    AppVolume.execute(new String[]{module.toString(),data.toString(),"panel","overlay_hidden"});
                    InputStream previous=System.in;
                    try{System.setIn(new ByteArrayInputStream(new byte[0]));AppVolume.execute(new String[]{module.toString(),data.toString(),"stream"});}
                    finally{System.setIn(previous);}
                    File trace=new File(module,"state/panel-lifecycle.log");long end=System.nanoTime()+2500000000L;
                    while(System.nanoTime()<end&&(!trace.exists()||!AppVolume.rulesText(trace).contains("AFTER_1S")))Thread.sleep(10);
                    check(AppVolume.rulesText(trace).contains("overlay_hidden")&&AppVolume.rulesText(trace).contains("AFTER_1S"),"no background playback evidence");
                    check(daemon.isAlive()&&android.media.audiofx.DynamicsProcessing.last==handle,"closing UI or writer killed audio owner");
                    check(Math.abs(android.media.audiofx.DynamicsProcessing.last.gain-AppVolume.gainDb(30))<0.001,"background changed gain");
                    check(Arrays.equals(before,Files.readAllBytes(new File(data,"rules.tsv").toPath())),"close wrote volume rules");
                }else{
                long start=System.nanoTime();
                AppVolume.setRule(data,new File(data,"rules.tsv"),new AppVolume.AndroidBackend(),new AppVolume.Rule(0,"com.spotify.music",5));
                String target="gain="+AppVolume.gainDb(5)+"dB";
                while(System.nanoTime()-start<1000000000L&&!AppVolume.rulesText(status).contains(target))Thread.sleep(10);
                check(AppVolume.rulesText(status).contains(target),"rule change waited for old 1.5-second refresh");
                check(android.media.audiofx.DynamicsProcessing.last==handle,"live change recreated effect");
                }
            }finally{new File(module,"disable").createNewFile();daemon.join(3000);}
            check(!daemon.isAlive()&&error.get()==null,"daemon shutdown/error: "+error.get());
        }else if(test.equals("stream-updates")){
            File module=new File(temp,"module"),data=new File(temp,"data");module.mkdirs();data.mkdirs();
            IPackageManager.Stub.uids.put("0:com.spotify.music",10212);
            String input="1\tcom.spotify.music\t30\t0\n2\tcom.spotify.music\tinvalid\t0\n3\tcom.spotify.music\t5\t0\n";
            InputStream oldIn=System.in;PrintStream oldOut=System.out;ByteArrayOutputStream captured=new ByteArrayOutputStream();
            try{
                System.setIn(new ByteArrayInputStream(input.getBytes("UTF-8")));System.setOut(new PrintStream(captured,true,"UTF-8"));
                AppVolume.execute(new String[]{module.toString(),data.toString(),"stream"});
            }finally{System.setIn(oldIn);System.setOut(oldOut);}
            String output=captured.toString("UTF-8");
            check(output.contains("OK\t1\tSAVED")&&output.contains("ERROR\t2\t")&&output.contains("OK\t3\tSAVED"),"stream acknowledgement or error recovery");
            check(AppVolume.readRules(new File(data,"rules.tsv")).get("0:com.spotify.music").percent==5,"last streamed value not persisted");
        }else if(test.equals("ui-contract")){
            File module=new File(temp,"module"),data=new File(temp,"data");module.mkdirs();data.mkdirs();
            IPackageManager.Stub.uids.put("0:com.spotify.music",10212);
            IPackageManager.Stub.uids.put("0:com.test.inactive",10207);
            AudioPlaybackConfiguration.items.add(new AudioPlaybackConfiguration(-100,10212,13,1,2,new PlayerProxy()));
            AudioPlaybackConfiguration.items.add(new AudioPlaybackConfiguration(309,10212,13,1,2,new PlayerProxy()));
            AppVolume.save(new File(data,"rules.tsv"),Arrays.asList(new AppVolume.Rule(0,"com.spotify.music",30),new AppVolume.Rule(0,"com.test.inactive",65)));
            File state=new File(module,"state");state.mkdirs();
            AppVolume.atomic(new File(state,"app-volume-status.txt"),"UID_10212=ERROR: test native effect unavailable\n");
            ByteArrayOutputStream captured=new ByteArrayOutputStream();PrintStream original=System.out;
            try{System.setOut(new PrintStream(captured,true,"UTF-8"));AppVolume.execute(new String[]{module.toString(),data.toString(),"ui"});}
            finally{System.setOut(original);}
            String output=captured.toString("UTF-8");
            check(output.contains("APP\tcom.spotify.music\t0\t10212\t30\t13\t409"),"AAudio UI row with saved percentage/session");
            check(output.contains("APP\tcom.test.inactive\t0\t10207\t65\t0\t0"),"inactive saved rule visible");
            check(output.contains("STATUS\tenabled")&&output.contains("RESULT\tUID_10212=ERROR:"),"enable state and backend error visible");
            for(String line:output.split("\n"))if(line.startsWith("APP\t"))check(line.split("\t",-1).length==7,"UI contract exactly seven columns");
        }else if(test.equals("missing-package")){
            File module=new File(temp,"module"),data=new File(temp,"data");module.mkdirs();
            try{AppVolume.execute(new String[]{module.toString(),data.toString(),"set","com.missing.app","30","0"});throw new AssertionError("missing accepted");}
            catch(IllegalArgumentException ok){}
            check(!new File(data,"rules.tsv").exists(),"invalid command persisted");
        }else throw new AssertionError("Unknown test "+test);
        System.out.println("PASS "+test);
    }
}
