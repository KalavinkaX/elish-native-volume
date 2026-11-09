package elish.volume.nativepanel;

import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Accelerates native steps only. Original AudioService policy still applies. */
public final class VolumeKeysHook implements IXposedHookLoadPackage {
    static final String SERVICES_SHA="0a8bef13f4e649da84c64bb459dd7d1abb12c41d2a2b7cbb65f9be485376f6e6";
    final KeyAcceleration keys=new KeyAcceleration();
    final ThreadLocal<Integer> scale=new ThreadLocal<>();
    volatile boolean failed;
    boolean loggedStep;
    static void log(String s){XposedBridge.log("ElishKeys: "+s);}
    void observe(KeyEvent e){
        if(e!=null)keys.event(e.getAction(),e.getKeyCode(),e.getRepeatCount(),e.getDeviceId(),e.getDownTime(),e.getEventTime(),SystemClock.uptimeMillis());
    }
    void fail(Throwable t){if(!failed){failed=true;keys.clear();log("Acceleration disabled for this boot: "+t);}}
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p)throws Throwable{
        if(!"android".equals(p.packageName)||!"android".equals(p.processName))return;
        if(!"elish".equals(Build.DEVICE)||Build.VERSION.SDK_INT!=37)return;
        try{
            if(!SERVICES_SHA.equals(NativeHook.hash("/system/framework/services.jar"))){log("Skip services.jar hash mismatch");return;}
            Class<?> audio=Class.forName("com.android.server.audio.AudioService",false,p.classLoader);
            Class<?> session=Class.forName("com.android.server.media.MediaSessionService$SessionManagerImpl",false,p.classLoader);
            Method keyDispatch=session.getDeclaredMethod("dispatchVolumeKeyEventLocked",String.class,String.class,int.class,int.class,boolean.class,KeyEvent.class,int.class,boolean.class);
            Method handle=audio.getDeclaredMethod("handleVolumeKey",KeyEvent.class,boolean.class,String.class,String.class);
            Method adjust=audio.getDeclaredMethod("adjustStreamVolume",int.class,int.class,int.class,Class.forName("android.media.AudioDeviceAttributes"),String.class,String.class,int.class,int.class,String.class,boolean.class,int.class);
            Method step=audio.getDeclaredMethod("rescaleStep",int.class,int.class,int.class);
            final Method min=audio.getDeclaredMethod("getStreamMinVolume",int.class),max=audio.getDeclaredMethod("getStreamMaxVolume",int.class);
            final Method current=audio.getDeclaredMethod("getStreamVolume",int.class),device=audio.getDeclaredMethod("getDeviceForStream",int.class);
            final Field mode=audio.getDeclaredField("mMode");mode.setAccessible(true);
            for(Method m:new Method[]{keyDispatch,handle,adjust,step,min,max,current,device})m.setAccessible(true);
            XposedBridge.hookMethod(keyDispatch,new XC_MethodHook(){
                protected void beforeHookedMethod(MethodHookParam c){if(!failed)try{observe((KeyEvent)c.args[5]);}catch(Throwable t){fail(t);}}
            });
            XposedBridge.hookMethod(handle,new XC_MethodHook(){
                protected void beforeHookedMethod(MethodHookParam c){if(!failed)try{observe((KeyEvent)c.args[0]);}catch(Throwable t){fail(t);}}
            });
            XposedBridge.hookMethod(adjust,new XC_MethodHook(){
                protected void beforeHookedMethod(MethodHookParam c){
                    c.setObjectExtra("elish.previousScale",scale.get());scale.remove();
                    if(failed)return;
                    try{
                        int stream=(Integer)c.args[0],direction=(Integer)c.args[1],flags=(Integer)c.args[2];
                        if((flags&4096)==0)return;
                        int factor=keys.claim(direction,SystemClock.uptimeMillis());
                        if(factor<=1||stream!=3||c.args[3]!=null)return;
                        int audioMode=((AtomicInteger)mode.get(c.thisObject)).get();
                        if(KeyAcceleration.eligible(stream,direction,flags,audioMode,(Integer)device.invoke(c.thisObject,3),
                            (Integer)min.invoke(c.thisObject,3),(Integer)max.invoke(c.thisObject,3),(Integer)current.invoke(c.thisObject,3)))scale.set(factor);
                    }catch(Throwable t){fail(t);}
                }
                protected void afterHookedMethod(MethodHookParam c){
                    Integer previous=(Integer)c.getObjectExtra("elish.previousScale");
                    if(previous==null)scale.remove();else scale.set(previous);
                }
            });
            XposedBridge.hookMethod(step,new XC_MethodHook(){
                protected void afterHookedMethod(MethodHookParam c){
                    Integer factor=scale.get();if(failed||factor==null||c.hasThrowable())return;
                    try{
                        int original=(Integer)c.getResult();
                        int accelerated=KeyAcceleration.scaleStep(original,(Integer)c.args[1],(Integer)c.args[2],factor);
                        c.setResult(accelerated);
                        if(accelerated!=original&&!loggedStep){loggedStep=true;log("Applied native media repeat step: "+original+" -> "+accelerated);}
                    }
                    catch(Throwable t){fail(t);}
                }
            });
            log("Installed speaker media key acceleration: single=1; held>=800ms=2, >=1800ms=3; <=10 stays fine; native volume policy retained");
        }catch(Throwable t){fail(t);}
    }
}
