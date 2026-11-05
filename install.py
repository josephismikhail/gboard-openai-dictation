"""Install the locally built APK set and explicitly select its keyboard."""
from pathlib import Path
import argparse,subprocess

PACKAGE='org.gboardopenai.keyboard'
IME=PACKAGE+'/com.android.inputmethod.latin.LatinIME'

def install(output,run=None):
    if run is None:run=lambda cmd:subprocess.check_output(cmd,text=True,stderr=subprocess.STDOUT)
    output=Path(output)
    if not (output/'base.apk').is_file():raise ValueError('Build the APK set first; base.apk is missing')
    apks=sorted(output.glob('*.apk'))
    run(['adb','install-multiple','-r',*map(str,apks)])
    run(['adb','shell','pm','grant',PACKAGE,'android.permission.RECORD_AUDIO'])
    run(['adb','shell','ime','enable',IME])
    run(['adb','shell','ime','set',IME])
    selected=run(['adb','shell','settings','get','secure','default_input_method']).strip()
    if selected!=IME:raise RuntimeError('Android did not select Gboard + OpenAI')
    return selected

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--work',type=Path,required=True);args=p.parse_args()
    print('Selected:',install(args.work/'output'))
