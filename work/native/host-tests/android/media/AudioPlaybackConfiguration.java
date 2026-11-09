package android.media;
import java.util.*;
public final class AudioPlaybackConfiguration {
    public static List<AudioPlaybackConfiguration> items=new ArrayList<AudioPlaybackConfiguration>();
    final int id,uid,type,usage,state;
    final PlayerProxy proxy;
    public AudioPlaybackConfiguration(int id,int uid,int type,int usage,int state,PlayerProxy proxy) {
        this.id=id;this.uid=uid;this.type=type;this.usage=usage;this.state=state;this.proxy=proxy;
    }
    public int getPlayerInterfaceId(){return id;}
    public int getClientUid(){return uid;}
    public int getPlayerType(){return type;}
    public int getPlayerState(){return state;}
    public int getSessionId(){return id+100;}
    public PlayerProxy getPlayerProxy(){return proxy;}
    public AudioAttributes getAudioAttributes(){return new AudioAttributes(usage);}
}
