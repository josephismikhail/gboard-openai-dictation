#!/usr/bin/env python3
"""Build an independently signed stock-Gboard copy with an OpenAI microphone."""
from pathlib import Path
import argparse,hashlib,json,os,re,secrets,shutil,subprocess,sys,urllib.request,zipfile
from tools.binary_xml import patch,NEW
from tools.smali_hooks import hook_void_returns
from tools.accessibility_split import build_split

ROOT=Path(__file__).resolve().parent

def reset_compiler_output(work):
    """Replace only the private compiler output after resolving every delete target."""
    root=Path(work).resolve(strict=True)
    classes=root/'classes'
    if classes.is_symlink() or classes.resolve()!=classes or classes.parent!=root:
        raise ValueError('Compiler output must be a plain classes directory inside the work directory')
    if classes.exists():
        if not classes.is_dir():
            raise ValueError('Compiler output is not a directory')
        # Compiler output never needs links. Validate the complete tree before
        # deleting anything, including junctions that resolve outside this path.
        pending=[classes]
        while pending:
            directory=pending.pop()
            for child in directory.iterdir():
                resolved=child.resolve()
                if child.is_symlink() or resolved!=child or not resolved.is_relative_to(classes):
                    raise ValueError('Compiler output contains a redirected path')
                if child.is_dir():pending.append(child)
        shutil.rmtree(classes)
    classes.mkdir()
    return classes


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--stock',type=Path,required=True,help='Directory containing base.apk and its installed splits')
    p.add_argument('--work',type=Path,required=True,help='Private build directory OUTSIDE this repository')
    p.add_argument('--sdk',type=Path,default=os.environ.get('ANDROID_HOME'))
    p.add_argument('--java',type=Path,default=os.environ.get('JAVA_HOME'))
    args=p.parse_args()
    if not args.sdk or not args.java:p.error('Set ANDROID_HOME/JAVA_HOME or pass --sdk/--java')
    work=args.work.resolve();stock=args.stock.resolve();sdk=args.sdk.resolve();java=args.java.resolve()/'bin'
    if work.is_relative_to(ROOT) or stock.is_relative_to(ROOT):p.error('Keep private build files and Google APKs outside the repository')
    work.mkdir(parents=True,exist_ok=True);(work/'tools').mkdir(exist_ok=True)
    build=sdk/'build-tools'/'36.0.0';android=sdk/'platforms'/'android-36'/'android.jar'
    if not android.exists():raise RuntimeError('Install Android SDK platform 36 and build-tools 36.0.0')
    exe=lambda name:java/(name+('.exe' if os.name=='nt' else ''))
    tool=lambda name:build/(name+('.bat' if os.name=='nt' and name in ('d8','apksigner') else '.exe' if os.name=='nt' else ''))
    def run(cmd):subprocess.run([str(v) for v in cmd],check=True)
    lock=json.loads((ROOT/'dependencies.json').read_text())
    def get(name):
        meta=lock[name];dest=work/'tools'/meta['file']
        if not dest.exists():
            cached=None
            if 'maven' in meta:
                g,a,v=meta['maven'];paths=list((Path.home()/'.gradle/caches/modules-2/files-2.1'/g/a/v).glob('*/'+meta['file']))
                if paths:cached=paths[0]
            if cached:shutil.copyfile(cached,dest)
            else:urllib.request.urlretrieve(meta['url'],dest)
        if hashlib.sha256(dest.read_bytes()).hexdigest()!=meta['sha256']:raise RuntimeError('Checksum mismatch: '+name)
        return dest
    deps={n:get(n) for n in lock};apktool=deps['apktool']
    package=subprocess.check_output([str(tool('aapt2')),'dump','badging',str(stock/'base.apk')],text=True)
    if "name='com.google.android.inputmethod.latin'" not in package or "versionName='18.5.6.993608717-release-arm64-v8a'" not in package:
        raise RuntimeError('Unsupported base APK. This patch is verified for Gboard 18.5.6.993608717 arm64 only.')
    decoded=work/'decoded'
    if not decoded.exists():run([exe('java'),'-jar',apktool,'d','-f','-r','-o',decoded,stock/'base.apk'])
    classes=reset_compiler_output(work)
    compilercp=os.pathsep.join(str(deps[n]) for n in ['compiler','build-api','script-runtime','reflect','daemon','coroutines','kotlin','annotations'])
    librarycp=os.pathsep.join(str(v) for v in [android,deps['okhttp'],deps['okio'],deps['kotlin'],deps['annotations']])
    run([exe('java'),'-cp',compilercp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','11','-classpath',librarycp,'-d',classes,*sorted((ROOT/'src/vendor').glob('*.kt'))])
    run([exe('javac'),'-encoding','UTF-8','--release','11','-cp',librarycp+os.pathsep+str(classes),'-d',classes,*sorted((ROOT/'src/main/java').rglob('*.java'))])
    helpers=work/'helpers.jar'
    with zipfile.ZipFile(helpers,'w') as z:
        for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes).as_posix())
    relocator=work/'relocator';relocator.mkdir(exist_ok=True)
    asmcp=os.pathsep.join(str(deps[n]) for n in ['asm','asm-commons'])
    run([exe('javac'),'--release','11','-cp',asmcp,'-d',relocator,ROOT/'Relocate.java'])
    shaded=work/'extension.jar';run([exe('java'),'-cp',str(relocator)+os.pathsep+asmcp,'Relocate',shaded,helpers,deps['okhttp'],deps['okio'],deps['kotlin']])
    dex=work/'dex';dex.mkdir(exist_ok=True);run([tool('d8'),'--release','--min-api','29','--lib',android,'--output',dex,shaded])
    # Resolve the actual InputMethodService implementation from the installed version.
    name='com/android/inputmethod/latin/LatinIME';chain=[]
    while name!='android/inputmethodservice/InputMethodService':
        files=list(decoded.glob('smali*/'+name+'.smali'))
        if len(files)!=1:raise RuntimeError('Unexpected input-method hierarchy: '+name)
        f=files[0];s=f.read_text();chain.append((f,s));name=re.search(r'^\.super L([^;]+);',s,re.M).group(1)
    methods={'onStartInputView(Landroid/view/inputmethod/EditorInfo;Z)V':'attach','onFinishInputView(Z)V':'detach','onFinishInput()V':'detach','onWindowHidden()V':'detach','onDestroy()V':'detach'}
    changed_dex=set()
    for signature,target in methods.items():
        candidates=[f for f,s in chain if re.search(r'^\.method[^\n]* '+re.escape(signature)+r'$',s,re.M)]
        if len(candidates)!=1:raise RuntimeError('Expected one lifecycle method: '+signature)
        f=candidates[0];s=f.read_text();pattern=r'(^\.method[^\n]* '+re.escape(signature)+r'\n)(.*?)(^\.end method)'
        def hook(m):
            body=m.group(2);call='    invoke-static/range {p0 .. p0}, Lorg/gboardopenai/VoiceRuntime;->'+target+'(Landroid/inputmethodservice/InputMethodService;)V\n'
            if call.strip() not in body:body=re.sub(r'(^    \.(?:locals|registers) \d+\n)',r'\1\n'+call,body,count=1,flags=re.M)
            return m.group(1)+body+m.group(3)
        updated=re.sub(pattern,hook,s,flags=re.M|re.S)
        if updated!=s:f.write_text(updated)
        changed_dex.add(f.relative_to(decoded).parts[0])
    signature='onComputeInsets(Landroid/inputmethodservice/InputMethodService$Insets;)V'
    candidates=[f for f,s in chain if re.search(r'^\.method[^\n]* '+re.escape(signature)+r'$',s,re.M)]
    if len(candidates)!=1:raise RuntimeError('Expected one input-method insets implementation')
    f=candidates[0]
    call='invoke-static/range {p0 .. p1}, Lorg/gboardopenai/VoiceRuntime;->adjustInsets(Landroid/inputmethodservice/InputMethodService;Landroid/inputmethodservice/InputMethodService$Insets;)V'
    s=f.read_text();updated=hook_void_returns(s,signature,call)
    if updated!=s:f.write_text(updated)
    changed_dex.add(f.relative_to(decoded).parts[0])
    signature='onUpdateSelection(IIIIII)V'
    candidates=[f for f,s in chain if re.search(r'^\.method[^\n]* '+re.escape(signature)+r'$',s,re.M)]
    if len(candidates)!=1:raise RuntimeError('Expected one input-method selection implementation')
    f=candidates[0]
    call='invoke-static/range {p0 .. p4}, Lorg/gboardopenai/VoiceRuntime;->selectionChanged(Landroid/inputmethodservice/InputMethodService;IIII)V'
    s=f.read_text();updated=hook_void_returns(s,signature,call)
    if updated!=s:f.write_text(updated)
    changed_dex.add(f.relative_to(decoded).parts[0])
    # Permit only this independently signed package through Gboard's self-signature gate.
    signatures=list(decoded.glob('smali*/sfr.smali'))
    if len(signatures)!=1:raise RuntimeError('Unsupported self-signature gate')
    f=signatures[0];s=f.read_text();pattern=r'(^\.method public static a\(Landroid/content/Context;Ljava/lang/String;\)Z\n)(.*?)(^\.end method)'
    m=re.search(pattern,s,re.M|re.S)
    if not m or 'Ljava/util/Arrays;->equals([B[B)Z' not in m.group(2):raise RuntimeError('Unexpected self-signature implementation')
    if ':openai_original_signature_check' not in s:
        addition='''
    invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
    move-result-object v0
    invoke-virtual {v0, p1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :openai_original_signature_check
    const/4 v0, 0x1
    return v0
    :openai_original_signature_check
'''
        body=re.sub(r'(^    \.locals \d+\n)',r'\1'+addition,m.group(2),count=1,flags=re.M)
        s=s[:m.start()]+m.group(1)+body+m.group(3)+s[m.end():];f.write_text(s)
    changed_dex.add(f.relative_to(decoded).parts[0])
    basebuilt=work/'reassembled.apk';run([exe('java'),'-jar',apktool,'b',decoded,'-o',basebuilt])
    messages_split,messages_resource=build_split(tool('aapt2'),android,stock/'base.apk',work)
    keystore=work/'signing.p12';passwordfile=work/'signing-password.txt'
    if not keystore.exists():passwordfile.write_text(secrets.token_urlsafe(40))
    os.environ['GBOARD_SIGNING_PASSWORD']=passwordfile.read_text().strip()
    if not keystore.exists():run([exe('keytool'),'-genkeypair','-storetype','PKCS12','-keystore',keystore,'-alias','gboard-openai','-keyalg','RSA','-keysize','3072','-validity','3650','-storepass:env','GBOARD_SIGNING_PASSWORD','-dname','CN=Local Gboard OpenAI Build'])
    output=work/'output';output.mkdir(exist_ok=True)
    with zipfile.ZipFile(basebuilt) as assembled:
        mapping={('classes.dex' if name=='smali' else name.replace('smali_','')+'.dex') for name in changed_dex}
        for source in sorted(stock.glob('*.apk')):
            unsigned=work/(source.stem+'-unsigned.apk')
            with zipfile.ZipFile(source) as original,zipfile.ZipFile(unsigned,'w') as target:
                for info in original.infolist():
                    if info.filename.startswith('META-INF/') and info.filename.upper().endswith(('.SF','.RSA','.DSA','.EC','MANIFEST.MF')):continue
                    data=original.read(info.filename)
                    if source.name=='base.apk' and info.filename in mapping:data=assembled.read(info.filename)
                    elif info.filename=='AndroidManifest.xml':data=patch(data,source.name=='base.apk',messages_resource if source.name=='base.apk' else None)
                    elif info.filename.startswith('res/') and info.filename.endswith('.xml'):data=patch(data)
                    target.writestr(info,data)
                if source.name=='base.apk':
                    maximum=max([1]+[int(m.group(1) or 1) for n in original.namelist() if (m:=re.fullmatch(r'classes(\d*)\.dex',n))])
                    target.write(dex/'classes.dex',f'classes{maximum+1}.dex')
            aligned=work/(source.stem+'-aligned.apk');run([tool('zipalign'),'-P','16','-f','4',unsigned,aligned])
            run([tool('apksigner'),'sign','--ks',keystore,'--ks-key-alias','gboard-openai','--ks-pass','env:GBOARD_SIGNING_PASSWORD','--out',output/source.name,aligned])
            run([tool('apksigner'),'verify',output/source.name])
    messages_aligned=work/'messages-resources'/'aligned.apk'
    run([tool('zipalign'),'-P','16','-f','4',messages_split,messages_aligned])
    run([tool('apksigner'),'sign','--ks',keystore,'--ks-key-alias','gboard-openai','--ks-pass','env:GBOARD_SIGNING_PASSWORD','--out',output/'split_openai_messages.apk',messages_aligned])
    run([tool('apksigner'),'verify',output/'split_openai_messages.apk'])
    print('Built signed APK set in',output)

if __name__=='__main__':main()
