import tempfile,unittest
from pathlib import Path
from install import install,IME

class InstallTests(unittest.TestCase):
    def test_updates_explicitly_restore_and_verify_selected_keyboard(self):
        calls=[]
        def run(command):
            calls.append(command)
            return IME+'\n' if command[-1]=='default_input_method' else 'Success'
        with tempfile.TemporaryDirectory() as directory:
            Path(directory,'base.apk').touch();Path(directory,'split_config.apk').touch()
            self.assertEqual(IME,install(directory,run))
        self.assertEqual('install-multiple',calls[0][1])
        self.assertEqual(['adb','shell','ime','set',IME],calls[-2])
        self.assertEqual('default_input_method',calls[-1][-1])
    def test_wrong_selected_keyboard_is_not_reported_as_success(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory,'base.apk').touch()
            with self.assertRaises(RuntimeError):install(directory,lambda command:'original.keyboard/Service')
