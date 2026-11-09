"""Small read-only DEX signature inventory, used to check the target ROM ABI."""
import struct
import zipfile

def uint(b, p): return struct.unpack_from('<I', b, p)[0]
def uleb(b, p):
    n = shift = 0
    while True:
        c=b[p];p+=1;n|=(c&127)<<shift
        if c<128:return n,p
        shift+=7

def classes(apk):
    result={}
    with zipfile.ZipFile(apk) as z:
        for filename in z.namelist():
            if not (filename.startswith('classes') and filename.endswith('.dex')):continue
            b=z.read(filename)
            assert b.startswith(b'dex\n')
            strings=[]
            for i in range(uint(b,56)):
                p=uint(b,uint(b,60)+4*i);_,p=uleb(b,p)
                strings.append(b[p:b.index(0,p)].decode('utf-8',errors='replace'))
            types=[strings[uint(b,uint(b,68)+4*i)] for i in range(uint(b,64))]
            protos=[]
            for i in range(uint(b,72)):
                p=uint(b,76)+12*i;ret=types[uint(b,p+4)];args=uint(b,p+8)
                params=[] if not args else [types[struct.unpack_from('<H',b,args+4+2*j)[0]] for j in range(uint(b,args))]
                protos.append('('+''.join(params)+')'+ret)
            methods=[]
            for i in range(uint(b,88)):
                p=uint(b,92)+8*i;owner,proto,name=struct.unpack_from('<HHI',b,p)
                methods.append((types[owner],strings[name],protos[proto]))
            fieldids=[]
            for i in range(uint(b,80)):
                owner,typ,name=struct.unpack_from('<HHI',b,uint(b,84)+8*i)
                fieldids.append((strings[name],types[typ]))
            for i in range(uint(b,96)):
                p=uint(b,100)+32*i;name=types[uint(b,p)];data=uint(b,p+24)
                item=dict(methods={},fields={},access=uint(b,p+4))
                result[name]=item
                if not data:continue
                sizes=[]
                for _ in range(4):n,data=uleb(b,data);sizes.append(n)
                for size in sizes[:2]:
                    index=0
                    for _ in range(size):
                        delta,data=uleb(b,data);index+=delta;flags,data=uleb(b,data)
                        fname,ftype=fieldids[index];item['fields'][fname]=ftype
                for size in sizes[2:]:
                    index=0
                    for _ in range(size):
                        delta,data=uleb(b,data);index+=delta;flags,data=uleb(b,data);code,data=uleb(b,data)
                        owner,mname,proto=methods[index];item['methods'][mname+proto]=flags
    return result

def verify_rom(apk):
    c=classes(apk)
    checks=[]
    def method(cls, signature):
        assert signature in c[cls]['methods'],(cls,signature)
        checks.append(cls+'->'+signature)
    def field(cls,name,typ):
        assert c[cls]['fields'].get(name)==typ,(cls,name,typ)
        checks.append(cls+'->'+name+':'+typ)
    root='Lcom/android/systemui/volume/'
    vm=root+'panel/component/volume/ui/viewmodel/AudioVolumeComponentViewModel;'
    field(vm,'sliderViewModels','Lkotlinx/coroutines/flow/ReadonlyStateFlow;')
    field(root+'panel/component/volume/ui/composable/VolumeSlidersComponent;','viewModel',vm)
    method(root+'panel/domain/interactor/VolumePanelGlobalStateInteractor;','setVisible(Z)V')
    method('Lkotlinx/coroutines/flow/StateFlowKt;','MutableStateFlow(Ljava/lang/Object;)Lkotlinx/coroutines/flow/StateFlowImpl;')
    method('Lkotlinx/coroutines/flow/StateFlow;','getValue()Ljava/lang/Object;')
    method('Lkotlinx/coroutines/flow/MutableStateFlow;','setValue(Ljava/lang/Object;)V')
    method('Lkotlinx/coroutines/flow/ReadonlyStateFlow;','<init>(Lkotlinx/coroutines/flow/MutableStateFlow;)V')
    method('Lkotlin/ranges/RangesKt;','rangeTo(FF)Lkotlin/ranges/ClosedFloatRange;')
    method('Lcom/android/systemui/common/shared/model/Icon$Loaded;','<init>(Landroid/graphics/drawable/Drawable;Lcom/android/systemui/common/shared/model/ContentDescription;Ljava/lang/Integer;Ljava/lang/String;)V')
    method('Lcom/android/systemui/common/ui/compose/IconKt;','Icon(Lcom/android/systemui/common/shared/model/Icon;Landroidx/compose/ui/Modifier;Landroidx/compose/runtime/Composer;I)V')
    method('Lcom/android/systemui/common/ui/compose/IconKt;','Icon-FNF3uiM(Lcom/android/systemui/common/shared/model/Icon;Landroidx/compose/ui/Modifier;JLandroidx/compose/runtime/Composer;II)V')
    field('Landroidx/compose/ui/graphics/Color;','Unspecified','J')
    method('Lcom/android/systemui/haptics/slider/SliderHapticFeedbackFilter;','<init>(IZ)V')
    svm=root+'panel/component/volume/slider/ui/viewmodel/SliderViewModel;'
    state=root+'panel/component/volume/slider/ui/viewmodel/SliderState;'
    method(svm,'getSlider()Lkotlinx/coroutines/flow/ReadonlyStateFlow;')
    method(svm,'onValueChanged('+state+'F)V')
    method(svm,'onValueChangeFinished()V')
    method(svm,'toggleMuted('+state+')V')
    # Interfaces must remain public interfaces so java.lang.reflect.Proxy can implement them.
    assert c[svm]['access']&0x201==0x201
    assert c[state]['access']&0x201==0x201
    return '\n'.join(checks)+'\n'

def verify_services(jar):
    c=classes(jar)
    audio='Lcom/android/server/audio/AudioService;'
    session='Lcom/android/server/media/MediaSessionService$SessionManagerImpl;'
    signatures=[(session,'dispatchVolumeKeyEventLocked(Ljava/lang/String;Ljava/lang/String;IIZLandroid/view/KeyEvent;IZ)V'),
        (audio,'handleVolumeKey(Landroid/view/KeyEvent;ZLjava/lang/String;Ljava/lang/String;)V'),
        (audio,'adjustStreamVolume(IIILandroid/media/AudioDeviceAttributes;Ljava/lang/String;Ljava/lang/String;IILjava/lang/String;ZI)V'),
        (audio,'rescaleStep(III)I')]
    signatures += [(audio,name+'(I)I') for name in ('getStreamMinVolume','getStreamMaxVolume','getStreamVolume','getDeviceForStream')]
    for owner,signature in signatures:assert signature in c[owner]['methods'],(owner,signature)
    assert c[audio]['fields']['mMode']=='Ljava/util/concurrent/atomic/AtomicInteger;'
    return '\n'.join(owner+'->'+signature for owner,signature in signatures)+'\n'+audio+'->mMode:AtomicInteger\n'
