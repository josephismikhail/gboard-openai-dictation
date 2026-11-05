"""Build the optional locally signed Android QA/setup harness after build.py."""
from pathlib import Path
import argparse,os,subprocess,zipfile
p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--sdk',type=Path,required=True);p.add_argument('--java',type=Path,required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[1];work=a.work.resolve();sdk=a.sdk.resolve();java=a.java.resolve()/'bin';tools=sdk/'build-tools/36.0.0';android=sdk/'platforms/android-36/android.jar'
if work.is_relative_to(root):p.error('Use a private work directory outside this repository')
def run(cmd):subprocess.run(list(map(str,cmd)),check=True)
classes=work/'qa-classes';classes.mkdir(exist_ok=True)
run([java/'javac.exe','--release','11','-encoding','UTF-8','-cp',os.pathsep.join(map(str,[android,work/'extension.jar'])),'-d',classes,*sorted((root/'tests/android').glob('*.java'))])
jar=work/'qa.jar'
with zipfile.ZipFile(jar,'w') as z:
    for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes).as_posix())
dex=work/'qa-dex';dex.mkdir(exist_ok=True);run([tools/'d8.bat','--release','--min-api','29','--lib',android,'--classpath',work/'extension.jar','--output',dex,jar])
manifest=work/'qa-manifest.xml';manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.gboardopenai.test"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36"/><uses-permission android:name="android.permission.INTERNET"/><application android:label="Local dictation QA"><activity android:name="org.gboardopenai.test.VoiceTestActivity" android:exported="true"/><activity android:name="org.gboardopenai.test.FullscreenVoicePreviewActivity" android:exported="true"/></application><instrumentation android:name="org.gboardopenai.test.SetupInstrumentation" android:targetPackage="org.gboardopenai.keyboard"/></manifest>')
unsigned=work/'qa-unsigned.apk';run([tools/'aapt2.exe','link','-I',android,'--manifest',manifest,'-o',unsigned])
with zipfile.ZipFile(unsigned,'a') as z:z.write(dex/'classes.dex','classes.dex')
aligned=work/'qa-aligned.apk';run([tools/'zipalign.exe','-f','4',unsigned,aligned])
os.environ['GBOARD_SIGNING_PASSWORD']=(work/'signing-password.txt').read_text().strip()
run([tools/'apksigner.bat','sign','--ks',work/'signing.p12','--ks-key-alias','gboard-openai','--ks-pass','env:GBOARD_SIGNING_PASSWORD','--out',work/'qa.apk',aligned])
