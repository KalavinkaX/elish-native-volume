package elish.volume.nativepanel;

import android.app.Application;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.widget.Toast;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

/** Injects data models into the ROM's actual native Compose sliders. */
public final class NativeHook implements IXposedHookLoadPackage {
    static final String EXPECTED_APK="7b2774f3a6e7047e7048cc2bfa8b77bd61f7efc0d80751d9bb47d85a656da6cf";
    static final String VM="com.android.systemui.volume.panel.component.volume.ui.viewmodel.AudioVolumeComponentViewModel";
    static final String SLIDER="com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.";
    static final String COMPONENT="com.android.systemui.volume.panel.component.volume.ui.composable.VolumeSlidersComponent";
    static final String VISIBILITY="com.android.systemui.volume.panel.domain.interactor.VolumePanelGlobalStateInteractor";
    static void log(String message){XposedBridge.log("ElishNative: "+message);}
    static String hash(String path)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(path)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}
        StringBuilder text=new StringBuilder();for(byte b:digest.digest())text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();
    }
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p)throws Throwable{
        if(!p.packageName.equals("com.android.systemui")||!p.processName.equals("com.android.systemui"))return;
        try{
            if(!Build.DEVICE.equals("elish")||Build.VERSION.SDK_INT!=37){log("Skip unsupported device/API: "+Build.DEVICE+"/"+Build.VERSION.SDK_INT);return;}
            String actual=hash(p.appInfo.sourceDir);
            if(!EXPECTED_APK.equals(actual)){log("Skip unsupported SystemUI SHA256="+actual);return;}
            final RuntimeApi api=new RuntimeApi(p.classLoader);
            final Controller controller=new Controller(api);
            XposedBridge.hookMethod(api.iconRender,new XC_MethodHook(){
                protected void beforeHookedMethod(MethodHookParam call)throws Throwable{
                    if(!api.appIcons.containsKey(call.args[0]))return;
                    // Native slider icons inherit a monochrome content tint. An
                    // opaque adaptive app icon then becomes a solid circle.
                    // Use the ROM's untinted overload only for our own icon models.
                    api.fullColorIcon.invoke(null,call.args[0],call.args[1],api.unspecifiedColor,
                        call.args[2],((Integer)call.args[3]&~896)|384,0);
                    call.setResult(null);
                }
            });
            XposedBridge.hookAllConstructors(api.vm,new XC_MethodHook(){
                protected void afterHookedMethod(MethodHookParam call){
                    try{controller.attach(call.thisObject);}catch(Throwable e){controller.fail(e);}
                }
            });
            XposedBridge.hookAllMethods(api.component,"Content",new XC_MethodHook(){
                protected void beforeHookedMethod(MethodHookParam call){
                    // The original Compose function still renders everything.
                    try{controller.render(api.componentVM.get(call.thisObject));}catch(Throwable e){controller.fail(e);}
                }
            });
            XposedHelpers.findAndHookMethod(api.visibility,"setVisible",boolean.class,new XC_MethodHook(){
                protected void afterHookedMethod(MethodHookParam call){controller.visible((Boolean)call.args[0]);}
            });
            log("Installed native slider hooks for verified SystemUI; no overlay/key hook/su");
        }catch(Throwable e){log("Hooks not installed: "+e);}
    }

    static final class RuntimeApi {
        final ClassLoader loader;
        final Class<?> vm,component,visibility,sliderVM,sliderState;
        final Field listField,componentVM;
        final Method makeFlow,getValue,setValue,iconRender,fullColorIcon;
        final Map<Object,Boolean> appIcons=Collections.synchronizedMap(new WeakHashMap<Object,Boolean>());
        final long unspecifiedColor;
        final Constructor<?> readOnly,iconCtor,filterCtor;
        final Object range,filter;
        RuntimeApi(ClassLoader loader)throws Exception{
            this.loader=loader;
            vm=type(VM);component=type(COMPONENT);visibility=type(VISIBILITY);
            sliderVM=type(SLIDER+"SliderViewModel");sliderState=type(SLIDER+"SliderState");
            if(!sliderVM.isInterface()||!sliderState.isInterface())throw new IllegalStateException("Native slider interfaces changed");
            listField=vm.getDeclaredField("sliderViewModels");listField.setAccessible(true);
            componentVM=component.getDeclaredField("viewModel");componentVM.setAccessible(true);
            if(!listField.getType().getName().equals("kotlinx.coroutines.flow.ReadonlyStateFlow"))throw new IllegalStateException("Native list type changed");
            Class<?> mutable=type("kotlinx.coroutines.flow.MutableStateFlow");
            makeFlow=type("kotlinx.coroutines.flow.StateFlowKt").getMethod("MutableStateFlow",Object.class);
            getValue=type("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
            setValue=mutable.getMethod("setValue",Object.class);
            readOnly=type("kotlinx.coroutines.flow.ReadonlyStateFlow").getConstructor(mutable);
            // R8 removed ClosedFloatRange's constructor. Use its surviving native factory.
            range=type("kotlin.ranges.RangesKt").getMethod("rangeTo",float.class,float.class).invoke(null,0f,100f);
            iconCtor=type("com.android.systemui.common.shared.model.Icon$Loaded").getConstructor(Drawable.class,type("com.android.systemui.common.shared.model.ContentDescription"),Integer.class,String.class);
            Class<?> iconType=type("com.android.systemui.common.shared.model.Icon");
            Class<?> modifier=type("androidx.compose.ui.Modifier"),composer=type("androidx.compose.runtime.Composer");
            Class<?> iconKt=type("com.android.systemui.common.ui.compose.IconKt");
            iconRender=iconKt.getMethod("Icon",iconType,modifier,composer,int.class);
            fullColorIcon=iconKt.getMethod("Icon-FNF3uiM",iconType,modifier,long.class,composer,int.class,int.class);
            unspecifiedColor=type("androidx.compose.ui.graphics.Color").getField("Unspecified").getLong(null);
            filterCtor=type("com.android.systemui.haptics.slider.SliderHapticFeedbackFilter").getConstructor(int.class,boolean.class);
            filter=filterCtor.newInstance(3,false);
            sliderVM.getMethod("getSlider");sliderVM.getMethod("onValueChanged",sliderState,float.class);
            visibility.getMethod("setVisible",boolean.class);
        }
        Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,false,loader);}
        Object flow(Object value)throws Exception{return makeFlow.invoke(null,value);}
        Object readonly(Object mutable)throws Exception{return readOnly.newInstance(mutable);}
        Object value(Object flow)throws Exception{return getValue.invoke(flow);}
        void value(Object flow,Object value)throws Exception{setValue.invoke(flow,value);}
        Object proxy(Class<?> iface,InvocationHandler handler){return Proxy.newProxyInstance(loader,new Class<?>[]{iface},handler);}
    }

    static final class Binding {
        final Object original,mutable,readonly;
        Binding(RuntimeApi api,Object original)throws Exception{
            this.original=original;mutable=api.flow(api.value(original));readonly=api.readonly(mutable);
        }
    }
    static final class Controller implements NativeClient.Listener {
        final RuntimeApi api;
        final Handler main=new Handler(Looper.getMainLooper());
        final WeakHashMap<Object,Binding> bindings=new WeakHashMap<>();
        final LinkedHashMap<String,AppModel> models=new LinkedHashMap<>();
        final NativeClient client;
        volatile boolean visible,failed,connected;
        long toastAt;
        Controller(RuntimeApi api){this.api=api;client=new NativeClient(main,this);}
        synchronized void attach(Object vm)throws Exception{
            if(failed||bindings.containsKey(vm))return;
            Binding b=new Binding(api,api.listField.get(vm));
            // Preserve the original eagerly updated flow, and expose our merged flow.
            api.listField.set(vm,b.readonly);bindings.put(vm,b);
            merge(b);log("Attached native slider list");
        }
        synchronized void render(Object vm)throws Exception{
            if(failed)return;
            attach(vm);Binding b=bindings.get(vm);if(b!=null)merge(b);
        }
        synchronized void merge(Binding b)throws Exception{
            List<?> system=(List<?>)api.value(b.original);
            ArrayList<Object> list=new ArrayList<>(system);
            for(AppModel model:models.values())list.add(model.proxy);
            // StateFlow uses list equality to avoid unnecessary recomposition.
            api.value(b.mutable,Collections.unmodifiableList(list));
        }
        final Runnable pulse=new Runnable(){public void run(){
            if(!visible||failed)return;
            try{synchronized(Controller.this){for(Binding b:new ArrayList<>(bindings.values()))merge(b);}}catch(Throwable e){fail(e);return;}
            main.postDelayed(this,200);
        }};
        void visible(boolean value){main.post(()->{
            if(failed)return;visible=value;client.visible(value);main.removeCallbacks(pulse);
            if(value)main.post(pulse);
            else try{
                synchronized(Controller.this){
                    models.clear();
                    for(Binding b:new ArrayList<>(bindings.values()))merge(b);
                }
            }catch(Throwable e){fail(e);}
            log(value?"Native panel opened":"Native panel closed; audio backend/rules unchanged");
        });}
        public synchronized void rows(List<NativeClient.Row> rows){
            if(!visible||failed)return;
            try{
                connected=true;Set<String> live=new HashSet<>();
                for(NativeClient.Row row:rows){
                    live.add(row.key());AppModel model=models.get(row.key());
                    if(model==null){model=new AppModel(this,row);models.put(row.key(),model);}
                    model.refresh(row,true);
                }
                models.keySet().retainAll(live);
                for(Binding b:new ArrayList<>(bindings.values()))merge(b);
            }catch(Throwable e){fail(e);}
        }
        public synchronized void failure(String message){
            log(message);connected=false;
            try{for(AppModel model:models.values())model.publish(false);}catch(Throwable e){fail(e);}
            long now=SystemClock.elapsedRealtime();
            Application app=AndroidAppHelper.currentApplication();
            if(visible&&app!=null&&now>=toastAt){toastAt=now+15000;Toast.makeText(app,"Elish：应用音量后台连接或保存失败，请查看模块日志",Toast.LENGTH_LONG).show();}
        }
        synchronized void fail(Throwable problem){
            if(failed)return;failed=true;visible=false;client.visible(false);main.removeCallbacks(pulse);
            // Fail open: restore original flows; never remove or change audio rules.
            for(Map.Entry<Object,Binding> e:new ArrayList<>(bindings.entrySet()))try{
                api.value(e.getValue().mutable,api.value(e.getValue().original));
                api.listField.set(e.getKey(),e.getValue().original);
            }catch(Throwable ignored){}
            bindings.clear();log("Native UI disabled for this process; original UI restored: "+problem);
        }
    }

    static final class AppModel implements InvocationHandler {
        final Controller owner;final RuntimeApi api;
        final String pkg,key,label;final int user;
        final Object mutable,readonly,proxy,icon;
        int percent,lastAudible=100;long editedAt;
        boolean enabled;
        int publishedPercent;
        boolean publishedEnabled;
        AppModel(Controller owner,NativeClient.Row row)throws Exception{
            this.owner=owner;api=owner.api;pkg=row.pkg;user=row.user;key=row.key();percent=row.percent;
            Application context=AndroidAppHelper.currentApplication();
            String name=pkg;Drawable drawable=null;
            if(context!=null){
                try{
                    Object handle=android.os.UserHandle.class.getMethod("of",int.class).invoke(null,user);
                    Context uc=(Context)Context.class.getMethod("createContextAsUser",android.os.UserHandle.class,int.class).invoke(context,handle,0);
                    ApplicationInfo ai=uc.getPackageManager().getApplicationInfo(pkg,0);
                    name=uc.getPackageManager().getApplicationLabel(ai).toString();drawable=uc.getPackageManager().getApplicationIcon(ai);
                }catch(Exception error){log("App icon lookup failed for "+pkg+": "+error);drawable=context.getDrawable(android.R.drawable.ic_media_play);}
            }
            label=name+(row.shared?" · 共享应用音量":" · 应用音量");
            icon=api.iconCtor.newInstance(drawable,null,null,null);
            api.appIcons.put(icon,Boolean.TRUE);
            enabled=owner.connected;
            mutable=api.flow(state());publishedPercent=percent;publishedEnabled=enabled;
            readonly=api.readonly(mutable);proxy=api.proxy(api.sliderVM,this);
        }
        void refresh(NativeClient.Row row,boolean connected)throws Exception{
            if(!owner.client.pending(key)&&SystemClock.elapsedRealtime()-editedAt>1500)percent=row.percent;
            publish(connected);
        }
        void publish(boolean available)throws Exception{
            enabled=available;
            if(publishedPercent!=percent||publishedEnabled!=enabled){api.value(mutable,state());publishedPercent=percent;publishedEnabled=enabled;}
        }
        Object state(){
            final int value=percent;final boolean available=enabled;
            return api.proxy(api.sliderState,(p,m,args)->{
                switch(m.getName()){
                    case "getValue":return (float)value;
                    case "getValueRange":return api.range;
                    case "getStep":return 0f;
                    case "getLabel":return label;
                    case "getIcon":return icon;
                    case "getHapticFilter":return api.filter;
                    case "getA11yContentDescription":return label;
                    case "getA11yStateDescription":return value+"%";
                    case "getDisabledMessage":return available?null:"应用音量后台暂不可用";
                    case "isEnabled":return available;
                    case "equals":return p==args[0];
                    case "hashCode":return System.identityHashCode(p);
                    case "toString":return "ElishAppState("+key+","+value+")";
                    default:throw new UnsupportedOperationException(m.toString());
                }
            });
        }
        void change(int value)throws Exception{
            if(!enabled||owner.failed)return;
            percent=Math.max(0,Math.min(100,value));if(percent>0)lastAudible=percent;
            editedAt=SystemClock.elapsedRealtime();publish(true);owner.client.set(pkg,user,percent);
        }
        public Object invoke(Object p,Method method,Object[] args)throws Throwable{
            // Native callbacks are contained: exceptions must not escape into SystemUI.
            try{
                switch(method.getName()){
                    case "getSlider":return readonly;
                    case "getSliderHapticsViewModelFactory":return null;
                    case "onValueChanged":change(Math.round(((Number)args[1]).floatValue()));return null;
                    case "onValueChangeFinished":return null; // Every edit is already queued; untouched sliders send nothing.
                    case "toggleMuted":change(percent==0?lastAudible:0);return null;
                    case "equals":return p==args[0];
                    case "hashCode":return System.identityHashCode(p);
                    case "toString":return "ElishAppSlider("+key+")";
                    default:throw new UnsupportedOperationException(method.toString());
                }
            }catch(Throwable e){owner.failure("Slider callback: "+e);return null;}
        }
    }
}
