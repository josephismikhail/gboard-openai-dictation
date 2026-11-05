"""Run public tests without a Google APK, API key, or API calls."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,urllib.request

ROOT=Path(__file__).resolve().parent
p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--sdk',type=Path,default=os.environ.get('ANDROID_HOME'));p.add_argument('--java',type=Path,default=os.environ.get('JAVA_HOME'));args=p.parse_args()
if not args.sdk or not args.java:p.error('Supply Android SDK and Java paths')
if args.work.resolve().is_relative_to(ROOT):p.error('Use a work directory outside the source tree')
args.work.mkdir(parents=True,exist_ok=True);tools=args.work/'tools';tools.mkdir(exist_ok=True)
lock=json.loads((ROOT/'dependencies.json').read_text());deps={}
for name,meta in lock.items():
    if name=='apktool':continue
    dest=tools/meta['file']
    if not dest.exists():
        g,a,v=meta['maven'];cached=list((Path.home()/'.gradle/caches/modules-2/files-2.1'/g/a/v).glob('*/'+meta['file']))
        if cached:shutil.copyfile(cached[0],dest)
        else:urllib.request.urlretrieve(meta['url'],dest)
    if hashlib.sha256(dest.read_bytes()).hexdigest()!=meta['sha256']:raise RuntimeError('Dependency checksum mismatch')
    deps[name]=dest
java=args.java/'bin'/('java.exe' if os.name=='nt' else 'java')
compiler=os.pathsep.join(str(deps[n]) for n in ['compiler','build-api','script-runtime','reflect','daemon','coroutines','kotlin','annotations'])
runtime=os.pathsep.join(str(deps[n]) for n in ['json','junit','hamcrest','mockwebserver','okhttp','okio','kotlin'])
classes=args.work/'test-classes';classes.mkdir(exist_ok=True)
def run(cmd):subprocess.run([str(x) for x in cmd],check=True,cwd=ROOT)
run([os.sys.executable,'-m','unittest','discover','-s','tests','-v'])
run([java,'-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','11','-cp',runtime,'-d',classes,*sorted((ROOT/'src/vendor').glob('*.kt')),*sorted((ROOT/'tests/kotlin').glob('*.kt'))])
run([java,'-cp',str(classes)+os.pathsep+runtime,'org.junit.runner.JUnitCore','com.trevornk.ramblr.OpenAiCloudLiveTranscriptionClientTest','com.trevornk.ramblr.CloudCleanupFidelityGuardTest','com.trevornk.ramblr.VocabularyAliasesTest'])
javac=args.java/'bin'/('javac.exe' if os.name=='nt' else 'javac')
run([javac,'--release','11','-cp',runtime,'-d',classes,*[ROOT/'src/main/java/org/gboardopenai'/name for name in ['AudioLevel.java','VoiceUiState.java','VoiceSurfaceBounds.java','DictationSpacing.java','DictationContext.java','MessagesHandoff.java','MessagesControlPolicy.java','MessagesTargetSnapshot.java']],*sorted((ROOT/'tests/java').rglob('*.java'))])
run([java,'-cp',str(classes)+os.pathsep+runtime,'org.junit.runner.JUnitCore','org.gboardopenai.VoiceUiTest','org.gboardopenai.DictationSpacingTest','org.gboardopenai.DictationContextTest','org.gboardopenai.MessagesHandoffTest','org.gboardopenai.MessagesControlPolicyTest','org.gboardopenai.MessagesTargetSnapshotTest'])
