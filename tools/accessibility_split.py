"""Build one resource-only split without rewriting any upstream resource IDs."""
from pathlib import Path
import re
import subprocess

from tools.binary_xml import NEW

DESCRIPTION = ('Optional controls for Google Messages on this phone. Finds the message composer '
               'and opens your dictation keyboard. Audio and message context go directly to OpenAI '
               'only when you start dictation. No messages are sent automatically.')


def resource_ids(dump):
    result = {}
    for kind, name in [('xml', 'gboard_openai_messages_service'),
                       ('string', 'gboard_openai_messages_description')]:
        ids = [int(value, 16) for value in re.findall(
            rf'^\s*resource (0x[0-9a-fA-F]{{8}}) {kind}/', dump, re.M)]
        if not ids or len({value >> 16 for value in ids}) != 1:
            raise ValueError('Expected one existing resource type: ' + kind)
        highest = max(ids)
        if highest >> 24 != 0x7f or highest & 0xffff == 0xffff:
            raise ValueError('No safe appended resource ID for ' + kind)
        result[kind + '/' + name] = highest + 1
    return result


def build_split(aapt2, android, stock, work):
    def run(args):
        return subprocess.check_output([str(v) for v in args], text=True)
    badging = run([aapt2, 'dump', 'badging', stock])
    version = re.search(r"versionCode='(\d+)'", badging)
    if version is None:
        raise ValueError('Missing base version code')
    ids = resource_ids(run([aapt2, 'dump', 'resources', stock]))
    root = Path(work) / 'messages-resources'
    (root / 'res/xml').mkdir(parents=True, exist_ok=True)
    (root / 'res/values').mkdir(parents=True, exist_ok=True)
    (root / 'res/xml/gboard_openai_messages_service.xml').write_text('''<accessibility-service
        xmlns:android="http://schemas.android.com/apk/res/android"
        android:description="@string/gboard_openai_messages_description"
        android:packageNames="com.google.android.apps.messaging"
        android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewFocused|typeViewScrolled"
        android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
        android:accessibilityFeedbackType="feedbackGeneric"
        android:notificationTimeout="100"
        android:canRetrieveWindowContent="true" />''', encoding='utf-8')
    (root / 'res/values/strings.xml').write_text(
        '<resources><string name="gboard_openai_messages_description">' + DESCRIPTION + '</string></resources>', encoding='utf-8')
    manifest = root / 'AndroidManifest.xml'
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="{NEW}" split="openai_messages_resources"
        android:versionCode="{version.group(1)}" android:isFeatureSplit="true">
        <uses-sdk android:minSdkVersion="32" android:targetSdkVersion="36" />
        <application android:hasCode="false" />
    </manifest>''', encoding='utf-8')
    stable = root / 'stable-ids.txt'
    stable.write_text(''.join(f'{NEW}:{name} = 0x{value:08x}\n' for name, value in ids.items()), encoding='utf-8')
    compiled = root / 'compiled.zip'
    unsigned = root / 'unsigned.apk'
    run([aapt2, 'compile', '--dir', root / 'res', '-o', compiled])
    run([aapt2, 'link', '-I', android, '--manifest', manifest, '--stable-ids', stable, '-o', unsigned, compiled])
    actual = run([aapt2, 'dump', 'resources', unsigned])
    for name, value in ids.items():
        if not re.search(rf'\bresource 0x{value:08x} {name}\b', actual):
            raise ValueError('aapt2 changed the reserved resource ID for ' + name)
    return unsigned, ids['xml/gboard_openai_messages_service']
