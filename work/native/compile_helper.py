"""Compile the reflection-only helper to Java 8 bytecode, then official D8 DEX."""
import hashlib
import os
from pathlib import Path
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parent
DEFAULT_JAVA=os.environ.get("JAVA_HOME", "")
if not os.environ.get("ELISH_JAVA_HOME", DEFAULT_JAVA):
    raise RuntimeError("Set JAVA_HOME or ELISH_JAVA_HOME to a JDK (javac required)")
JAVA_HOME=Path(os.environ.get("ELISH_JAVA_HOME",os.environ.get("JAVA_HOME",DEFAULT_JAVA)))
JAVA=JAVA_HOME/"bin"/("java.exe" if os.name=="nt" else "java")
JAVAC=JAVA_HOME/"bin"/("javac.exe" if os.name=="nt" else "javac")
R8=Path(os.environ.get("ELISH_R8_JAR",ROOT.parent/"r8.jar"))
R8_URL="https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.13.25/r8-8.13.25.jar"

def run(args):
    result=subprocess.run([str(x) for x in args],capture_output=True,text=True,encoding="utf-8",errors="replace")
    if result.returncode or "AccessDeniedException" in result.stderr or "java.lang.InternalError" in result.stderr:
        raise RuntimeError(result.stdout+result.stderr)
    if result.stderr.strip(): print(result.stderr.strip())

def compile_helper():
    (ROOT/"evidence").mkdir(exist_ok=True)
    if not JAVAC.exists(): raise RuntimeError("Set ELISH_JAVA_HOME to a JDK with javac")
    if not R8.exists(): raise RuntimeError("Download official R8 to r8.jar, or set ELISH_R8_JAR: "+R8_URL)
    classes=ROOT/"helper-classes"
    classes.mkdir(exist_ok=True)
    sources=list((ROOT/"module/java").rglob("*.java"))
    run([JAVAC,"--release","8","-Xlint:-options","-encoding","UTF-8","-d",classes,*sources])
    jar=ROOT/"helper-java-classes.jar"
    with zipfile.ZipFile(jar,"w",compression=zipfile.ZIP_DEFLATED) as z:
        for p in classes.rglob("*.class"): z.write(p,p.relative_to(classes).as_posix())
    dex=ROOT/"helper-dex"
    dex.mkdir(exist_ok=True)
    run([JAVA,"-cp",R8,"com.android.tools.r8.D8","--min-api","26","--lib",JAVA_HOME,"--output",dex,jar])
    target=ROOT/"module/app-volume.jar"
    with zipfile.ZipFile(target,"w",compression=zipfile.ZIP_DEFLATED) as z:
        entry=zipfile.ZipInfo("classes.dex",(2026,11,9,0,0,0))
        entry.compress_type=zipfile.ZIP_DEFLATED
        z.writestr(entry,(dex/"classes.dex").read_bytes())
    (ROOT/"evidence/HELPER-BUILD.txt").write_text(
        "JDK: "+str(JAVA_HOME)+"\nSource bytecode: javac --release 8\nD8: "+R8_URL+
        "\nR8 SHA256: "+hashlib.sha256(R8.read_bytes()).hexdigest()+
        "\nHelper source SHA256: "+hashlib.sha256(b"".join(p.read_bytes() for p in sorted(sources))).hexdigest()+
        "\nDEX minimum API: 26; no Android stubs are bundled.\n",encoding="utf-8",newline="\n")
    print("Compiled app-volume.jar (DEX):",target.stat().st_size,"bytes")

if __name__=="__main__": compile_helper()
