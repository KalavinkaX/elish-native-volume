package elish.volume.nativepanel;

public final class KeyAccelerationHostTest {
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    static void event(KeyAcceleration k,int action,int code,int repeat,long down,long time){k.event(action,code,repeat,1,down,time,time);}
    public static void main(String[] args){
        KeyAcceleration k=new KeyAcceleration();
        if(args[0].equals("tiers")){
            event(k,0,24,0,100,100);check(k.claim(1,100)==1,"initial press accelerated");
            event(k,0,24,1,100,600);check(k.claim(1,600)==1,"early repeat accelerated");
            event(k,0,24,2,100,900);check(k.claim(1,900)==2,"800ms tier");
            event(k,0,24,3,100,1900);check(k.claim(1,1900)==3,"1800ms tier");
            check(k.claim(1,1900)==1,"repeat applied twice");
        }else if(args[0].equals("release")){
            event(k,0,24,0,0,0);event(k,0,24,5,0,2000);event(k,1,24,5,0,2001);
            check(k.claim(1,2001)==1,"release left queued acceleration");
            for(int i=0;i<20;i++){event(k,0,24,0,3000+i*100,3000+i*100);check(k.claim(1,3000+i*100)==1,"quick taps accelerated");event(k,1,24,0,3000+i*100,3001+i*100);}
            event(k,0,25,0,6000,6000);event(k,0,25,1,6000,8000);check(k.claim(-1,8000)==3,"down acceleration absent");
        }else if(args[0].equals("stale")){
            event(k,0,24,1,0,2000);check(k.claim(1,2000)==1,"missing initial press accepted");
            event(k,0,24,0,3000,3000);event(k,0,24,1,3000,5000);check(k.claim(1,5251)==1,"stale key accelerated");
            event(k,0,24,2,3000,5100);check(k.claim(-1,5100)==1,"wrong direction accelerated");
            event(k,0,24,3,3000,5200);check(k.claim(1,5199)==1,"future event accelerated");
            event(k,0,24,4,3000,5300);event(k,0,24,4,3000,5300);check(k.claim(1,5300)==3,"duplicate ingress erased event");
            event(k,0,24,3,3000,5200);check(k.claim(1,5300)==1,"out of order event accepted");
        }else if(args[0].equals("guards")){
            check(KeyAcceleration.eligible(3,1,4096,0,2,0,100,50),"speaker media rejected");
            int[][] blocked={{0,1,4096,0,2,0,100,50},{3,0,4096,0,2,0,100,50},{3,1,0,0,2,0,100,50},
                {3,1,4096,2,2,0,100,50},{3,1,4096,3,2,0,100,50},{3,1,4096,0,128,0,100,50},
                {3,1,4096,0,2,0,15,10},{3,1,4096,0,2,0,100,10},{3,1,4096,0,2,0,100,0}};
            for(int[] p:blocked)check(!KeyAcceleration.eligible(p[0],p[1],p[2],p[3],p[4],p[5],p[6],p[7]),"unsafe context accepted");
            check(KeyAcceleration.scaleStep(10,3,3,3)==30,"native step not scaled");
            check(KeyAcceleration.scaleStep(10,3,2,3)==10,"aliased stream accelerated");
            check(KeyAcceleration.scaleStep(10,2,3,3)==10,"other stream accelerated");
            check(KeyAcceleration.scaleStep(20,3,3,3)==20,"nonstandard OEM step accelerated");
        }else throw new AssertionError(args[0]);
        System.out.println("PASS "+args[0]);
    }
}
