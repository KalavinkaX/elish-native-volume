"""Build the native LSPosed APK and matching Magisk module; never ships signing secrets."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import sys
import zipfile
from compile_helper import ROOT, JAVA, JAVAC, JAVA_HOME, R8, run, compile_helper
from dexcheck import verify_rom, verify_services, classes as dexclasses

VERSION='3.1.0-key-test1'
OUT=ROOT.parents[1]/'outputs'
SDK=Path(os.environ.get('ELISH_ANDROID_SDK',ROOT.parent/'android-sdk'))
PLATFORM=Path(os.environ.get('ELISH_ANDROID_JAR',SDK/'android-36/android.jar'))
TOOLS=Path(os.environ.get('ELISH_BUILD_TOOLS',SDK/'android-16'))
API=ROOT/'api/xposed-api-82.jar'
TARGET=ROOT/'hook-build'

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def archive(path,files):
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for source,name in sorted(files,key=lambda x:x[1]):
            data=source.read_bytes()
            if source.suffix in {'.java','.py','.sh','.md','.prop','.txt','.rule','.awk','.xml'}:
                assert b'\r' not in data,(source,'CRLF')
                assert not data.startswith(b'\xef\xbb\xbf'),source
            info=zipfile.ZipInfo(name,(2026,11,9,0,0,0));info.create_system=3
            info.external_attr=(0o100755 if name.endswith('.sh') or name.endswith('update-binary') else 0o100644)<<16
            info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,data)
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None
        assert all(not n.startswith('/') and '..' not in Path(n).parts for n in z.namelist())

def build_apk():
    TARGET.mkdir(exist_ok=True)
    resources=TARGET/'resources.zip'
    run([TOOLS/'aapt2.exe','compile','--dir',ROOT/'hook/res','-o',resources])
    unsigned=TARGET/'unsigned.apk'
    run([TOOLS/'aapt2.exe','link','-I',PLATFORM,'--manifest',ROOT/'hook/AndroidManifest.xml','-A',ROOT/'hook/assets','-o',unsigned,resources])
    compiled=TARGET/'classes';compiled.mkdir(exist_ok=True)
    run([JAVAC,'--release','8','-Xlint:-options','-encoding','UTF-8','-cp',str(PLATFORM)+os.pathsep+str(API),'-d',compiled,*sorted((ROOT/'hook/java').rglob('*.java'))])
    jar=TARGET/'classes.jar'
    with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
        for p in compiled.rglob('*.class'):z.write(p,p.relative_to(compiled).as_posix())
    dex=TARGET/'dex';dex.mkdir(exist_ok=True)
    run([JAVA,'-cp',R8,'com.android.tools.r8.D8','--min-api','28','--lib',PLATFORM,'--classpath',API,'--output',dex,jar])
    with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:z.write(dex/'classes.dex','classes.dex')
    aligned=TARGET/'aligned.apk';run([TOOLS/'zipalign.exe','-f','-p','4',unsigned,aligned])
    apk=ROOT/'module/Elish-Volume-Native.apk'
    run([JAVA,'-jar',TOOLS/'lib/apksigner.jar','sign','--ks',Path(os.environ.get('ELISH_KEYSTORE',ROOT.parent/'panel-signing.p12')),'--ks-pass','file:'+str(Path(os.environ.get('ELISH_KEYSTORE_PASSWORD_FILE',ROOT.parent/'panel-signing-password.txt'))),'--ks-key-alias',os.environ.get('ELISH_KEY_ALIAS','elish-panel'),'--out',apk,aligned])
    run([JAVA,'-jar',TOOLS/'lib/apksigner.jar','verify','--verbose',apk])
    run([TOOLS/'zipalign.exe','-c','-p','4',apk])
    with zipfile.ZipFile(apk) as z:
        assert z.read('assets/xposed_init').splitlines()==[b'elish.volume.nativepanel.NativeHook',b'elish.volume.nativepanel.VolumeKeysHook']
        assert not any('accessibility' in n.lower() for n in z.namelist())
    definitions=dexclasses(apk)
    assert 'Lelish/volume/nativepanel/NativeHook;' in definitions
    assert not any(n.startswith(('Landroid/','Lde/robv/','Lkotlin/','Lkotlinx/','Lcom/android/')) for n in definitions),'Do not bundle API/framework stubs'
    evidence=[]
    for flags in [('badging',),('xmltree','--file','AndroidManifest.xml')]:
        cmd=[str(TOOLS/'aapt2.exe'),'dump',flags[0],str(apk),*flags[1:]]
        r=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8',errors='replace',check=True)
        evidence.append(r.stdout)
    xml=evidence[1]
    assert 'xposedmodule' in xml and 'xposedscope' in xml
    assert 'BIND_ACCESSIBILITY_SERVICE' not in xml and 'INTERNET' not in xml
    (ROOT/'APK-BUILD.txt').write_text('\n'.join(evidence)+'\nAPK SHA256='+sha(apk)+'\n',encoding='utf-8',newline='\n')
    return apk

def main():
    OUT.mkdir(exist_ok=True)
    rom=Path(os.environ.get('ELISH_TARGET_SYSTEMUI',ROOT.parent/'rom-inspect/extracted/system_ext/priv-app/SystemUI/SystemUI.apk'))
    if rom.exists():
        assert sha(rom)=='7b2774f3a6e7047e7048cc2bfa8b77bd61f7efc0d80751d9bb47d85a656da6cf'
        (ROOT/'ROM-ABI.txt').write_text(verify_rom(rom),encoding='utf-8',newline='\n')
    else:raise RuntimeError('Set ELISH_TARGET_SYSTEMUI to the exact SystemUI APK from the supplied ROM')
    services=Path(os.environ.get('ELISH_TARGET_SERVICES',ROOT.parent/'rom-inspect/extracted/system/system/framework/services.jar'))
    assert sha(services)=='0a8bef13f4e649da84c64bb459dd7d1abb12c41d2a2b7cbb65f9be485376f6e6'
    (ROOT/'SERVICES-ABI.txt').write_text(verify_services(services),encoding='utf-8',newline='\n')
    compile_helper();apk=build_apk()
    tests=subprocess.run([sys.executable,'-m','unittest','discover','-s',str(ROOT),'-p','test_*.py','-v'],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace')
    report=('Elish Native Volume '+VERSION+'\nHost tests only. Android API36 compile, target35, real target ROM API37.\n'
        'Passed ROM DEX member ABI check against the exact SystemUI SHA256.\n'
        'APK zipalign and signature verification completed; Xposed API is compile-only.\n'
        'User reports 3.0.2 fixes tested successfully. New 3.1.0 key acceleration is not device-tested: system_server scope loading, key delivery/async correlation, actual native steps and release behavior require device validation. Host policy tests and exact services.jar ABI checks do not simulate system_server routing or acoustics.\n\n'+tests.stdout+tests.stderr)
    testreport=OUT/('测试报告-v'+VERSION+'.txt');testreport.write_text(report,encoding='utf-8',newline='\n')
    if tests.returncode:print(report);raise SystemExit(tests.returncode)
    module=ROOT/'module'
    files=[(p,p.relative_to(module).as_posix()) for p in module.rglob('*') if p.is_file() and p.suffix!='.idsig']
    package=OUT/('Elish-Volume-Steps-Magisk-v'+VERSION+'.zip');archive(package,files)
    with zipfile.ZipFile(package) as z:
        assert 'sepolicy.rule' in z.namelist()
        assert not any(n.startswith('system/') or 'signing' in n for n in z.namelist())
        assert 'Elish-Volume-Panel.apk' not in z.namelist()
    companion=OUT/('Elish-Volume-Native-LSPosed-v'+VERSION+'.apk');shutil.copyfile(apk,companion)
    instructions=OUT/('安装与原生音量说明-v'+VERSION+'.md');shutil.copyfile(module/'README.zh-CN.md',instructions)
    sourcefiles=[(p,'module/'+name) for p,name in files]
    sourcefiles += [(p,p.relative_to(ROOT).as_posix()) for folder in ('hook','host-tests','client-tests','panel','evidence') for p in (ROOT/folder).rglob('*') if p.is_file()]
    sourcefiles += [(p,p.name) for p in ROOT.glob('*.py')]
    sourcefiles += [(ROOT/n,n) for n in ('ROM-ABI.txt','SERVICES-ABI.txt','APK-BUILD.txt','DEPENDENCIES.md','seapp-evidence.txt')]
    sourcefiles += [(testreport,'HOST-TEST-REPORT.txt')]
    source=OUT/('Elish-Volume-Native-v'+VERSION+'-source.zip');archive(source,sourcefiles)
    with zipfile.ZipFile(source) as z:assert not any(n.endswith(('.p12','.jks')) or 'signing-password' in n for n in z.namelist())
    deliverables=[package,companion,instructions,testreport,source]
    (OUT/('SHA256SUMS-v'+VERSION+'.txt')).write_text(''.join(sha(p)+'  '+p.name+'\n' for p in deliverables),encoding='utf-8',newline='\n')
    print(tests.stderr[-200:])
    for p in deliverables:print(p.name,p.stat().st_size,sha(p))

if __name__=='__main__':main()
