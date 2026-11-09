package elish.volume;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import android.content.pm.IPackageManager;
import android.media.AudioPlaybackConfiguration;

public final class NativeBridgeHostTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    interface Action {void run()throws Exception;}
    static void rejects(Action action)throws Exception{
        try{action.run();}catch(Exception expected){return;}
        throw new AssertionError("Expected rejection");
    }
    static InputStream bytes(String s){return new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));}
    public static void main(String[] args)throws Exception{
        String scenario=args[0];File module=new File(args[1],"module"),data=new File(args[1],"data");module.mkdirs();data.mkdirs();
        if(scenario.equals("credentials")){
            for(int expected:new int[]{1000,10140,10567}){
                check(NativeBridge.allowedPeer(expected,expected,"com.android.systemui"),"resolved systemui rejected");
                for(int uid:new int[]{0,2000,10000,101000})check(!NativeBridge.allowedPeer(expected,uid,"com.android.systemui"),"unauthorized UID accepted");
                for(String name:new String[]{"com.android.settings","com.android.systemui:other","com.android.systemui.fake","",null})check(!NativeBridge.allowedPeer(expected,expected,name),"wrong process accepted");
            }
            for(int unknown:new int[]{-1,0})check(!NativeBridge.allowedPeer(unknown,unknown,"com.android.systemui"),"unresolved identity accepted");
        }else if(scenario.equals("resolve-identity")){
            IPackageManager.Stub.uids.clear();
            NativeBridge bridge=new NativeBridge(module,data,new AppVolume.AndroidBackend());
            rejects(()->bridge.resolveSystemUiUid());
            for(int expected:new int[]{10140,10567,1000}){
                IPackageManager.Stub.uids.put("0:com.android.systemui",expected);
                check(bridge.resolveSystemUiUid()==expected,"SystemUI UID not resolved from package manager");
            }
            IPackageManager.Stub.uids.put("0:com.android.systemui",-1);
            rejects(()->bridge.resolveSystemUiUid());
        }else if(scenario.equals("bounded")){
            check(NativeBridge.readLine(bytes("HELLO\n"),5).equals("HELLO"),"framing");
            check(NativeBridge.readLine(bytes(""),5)==null,"EOF");
            rejects(()->NativeBridge.readLine(bytes("HELLOO\n"),5));
            rejects(()->NativeBridge.readLine(bytes("HELLO"),5));
            rejects(()->NativeBridge.readLine(bytes("UI\r\n"),5));
            rejects(()->NativeBridge.readLine(bytes("UI\t\n"),5));
        }else{
            IPackageManager.Stub.uids.clear();
            IPackageManager.Stub.uids.put("10:com.spotify.music",1010212);
            IPackageManager.Stub.uids.put("10:tv.danmaku.bilibilihd",1010207);
            IPackageManager.Stub.uids.put("0:com.spotify.music",10212);
            NativeBridge bridge=new NativeBridge(module,data,new AppVolume.AndroidBackend());
            File rules=new File(data,"rules.tsv");
            if(scenario.equals("protocol")){
                check(bridge.request("HELLO").equals("OK\tELISH_NATIVE_1\n"),"handshake");
                rejects(()->bridge.request("RESET"));rejects(()->bridge.request("su id"));
                rejects(()->bridge.request("SET com.spotify.music 10 101"));
                rejects(()->bridge.request("SET com.spotify.music 10 -1"));
                rejects(()->bridge.request("SET com.spotify.music 0 20"));
                rejects(()->bridge.request("SET com.spotify.music 10 01"));
                rejects(()->bridge.request("SET com.foo;id 10 20"));
                rejects(()->bridge.request("SET com.missing.player 10 20"));
                check(!rules.exists(),"rejected input wrote rules");
                bridge.request("SET com.spotify.music 10 23");bridge.request("SET tv.danmaku.bilibilihd 10 71");
                Map<String,AppVolume.Rule> saved=AppVolume.readRules(rules);
                check(saved.get("10:com.spotify.music").percent==23,"first app lost");
                check(saved.get("10:tv.danmaku.bilibilihd").percent==71,"second app lost");
                bridge.close();check(AppVolume.readRules(rules).size()==2,"closing IPC erased rules");
            }else if(scenario.equals("ui-users")){
                AppVolume.save(rules,Arrays.asList(new AppVolume.Rule(0,"com.spotify.music",12),new AppVolume.Rule(10,"tv.danmaku.bilibilihd",43)));
                AudioPlaybackConfiguration.items.clear();
                check(bridge.request("UI").equals("DATA\tELISH_NATIVE_1\nEND\n"),"saved rules alone displayed apps after boot");
                AudioPlaybackConfiguration.items.add(new AudioPlaybackConfiguration(375,1010212,13,1,2,null));
                AudioPlaybackConfiguration.items.add(new AudioPlaybackConfiguration(376,10212,13,1,2,null));
                String ui=bridge.request("UI");
                check(ui.contains("APP\tcom.spotify.music\t10\t1010212\t100\tsingle"),"active AAudio missing");
                check(!ui.contains("APP\ttv.danmaku.bilibilihd\t"),"inactive saved app leaked into UI");
                check(AppVolume.readRules(rules).get("10:tv.danmaku.bilibilihd").percent==43,"hidden app rule deleted");
                AudioPlaybackConfiguration.items.add(new AudioPlaybackConfiguration(377,1010207,1,1,3,null));
                check(!bridge.request("UI").contains("APP\ttv.danmaku.bilibilihd\t"),"paused app displayed");
                AudioPlaybackConfiguration.items.add(new AudioPlaybackConfiguration(378,1010207,1,1,2,null));
                check(bridge.request("UI").contains("APP\ttv.danmaku.bilibilihd\t10\t1010207\t43\tsingle"),"playing app did not regain saved volume");
                check(!ui.contains("\t0\t10212\t"),"other Android user leaked into UI");
                Files.write(new File(data,"disabled").toPath(),new byte[0]);
                rejects(()->bridge.request("SET com.spotify.music 10 50"));
                check(bridge.request("UI").startsWith("ERROR\t"),"disabled backend editable");
            }else if(scenario.equals("shared-uid")){
                IPackageManager.Stub.uids.put("10:com.shared.other",1010212);
                AudioPlaybackConfiguration.items.clear();
                AudioPlaybackConfiguration.items.add(new AudioPlaybackConfiguration(375,1010212,13,1,2,null));
                String ui=bridge.request("UI");
                check(ui.contains("\tshared\n"),"shared identity not disclosed");
                check(ui.split("APP\\t",-1).length==2,"shared UID generated duplicate competing sliders");
                AppVolume.save(rules,Arrays.asList(new AppVolume.Rule(10,"com.spotify.music",30),new AppVolume.Rule(10,"com.shared.other",30),new AppVolume.Rule(10,"tv.danmaku.bilibilihd",71)));
                bridge.request("SET com.spotify.music 10 50");
                Map<String,AppVolume.Rule> saved=AppVolume.readRules(rules);
                check(saved.get("10:com.spotify.music").percent==50&&saved.get("10:com.shared.other").percent==50,"shared UID old rules diverged");
                check(saved.get("10:tv.danmaku.bilibilihd").percent==71,"unrelated app changed");
            }else throw new AssertionError("Unknown scenario");
        }
        System.out.println("PASS "+scenario);
    }
}
