package elish.volume.nativepanel;

/** Pure event policy. No timers, volume setters, shell or audio APIs. */
final class KeyAcceleration {
    int key,device,repeat;
    long down=-1,lastEvent=-1,pendingAt=-1;
    int pendingDirection,pendingScale=1;
    synchronized void event(int action,int code,int count,int deviceId,long downTime,long eventTime,long now){
        if(code!=24&&code!=25)return;
        if(action==1){clear();return;}
        if(action!=0||count<0||downTime<0||eventTime<downTime||now<eventTime||now-eventTime>250){clear();return;}
        if(count==0){
            key=code;device=deviceId;down=downTime;repeat=0;lastEvent=eventTime;
            pendingAt=-1;return;
        }
        if(code!=key||deviceId!=device||downTime!=down){clear();return;}
        // Some system paths observe the same event at both ingress methods.
        if(count==repeat&&eventTime==lastEvent)return;
        if(count<=repeat||eventTime<lastEvent){clear();return;}
        repeat=count;lastEvent=eventTime;
        long held=eventTime-downTime;
        pendingScale=held>=1800?3:held>=800?2:1;
        pendingDirection=code==24?1:-1;pendingAt=eventTime;
    }
    synchronized int claim(int direction,long now){
        int result=pendingAt>=0&&now>=pendingAt&&now-pendingAt<=250&&direction==pendingDirection?pendingScale:1;
        pendingAt=-1;return result;
    }
    synchronized void clear(){key=0;device=0;repeat=0;down=-1;lastEvent=-1;pendingAt=-1;pendingScale=1;}
    static boolean eligible(int stream,int direction,int flags,int mode,int device,int min,int max,int current){
        return stream==3&&(direction==1||direction==-1)&&(flags&4096)!=0
            &&mode==0&&device==2&&min==0&&max==100&&current>10;
    }
    static int scaleStep(int original,int from,int to,int scale){
        // Preserve any OEM nonstandard step/batching path, and all stream aliases.
        return from==3&&to==3&&original==10&&scale>=2&&scale<=3?original*scale:original;
    }
}
