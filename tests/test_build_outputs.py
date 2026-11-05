from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from build import clean_apk_output, reset_compiler_output


class CompilerOutputTests(unittest.TestCase):
    def test_removes_deleted_classes_but_preserves_other_private_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            work=Path(temporary)
            classes=work/'classes'/'org'/'example'
            classes.mkdir(parents=True)
            (classes/'Deleted.class').write_bytes(b'old compiler output')
            preserved={
                'signing.p12':b'signing material',
                'signing-password.txt':b'private configuration',
                'stock/base.apk':b'upstream archive',
                'tools/dependency.jar':b'cached dependency',
            }
            for name,content in preserved.items():
                path=work/name
                path.parent.mkdir(parents=True,exist_ok=True)
                path.write_bytes(content)
            result=reset_compiler_output(work)
            self.assertEqual(work.resolve()/'classes',result)
            self.assertEqual([],list(result.iterdir()))
            for name,content in preserved.items():
                self.assertEqual(content,(work/name).read_bytes())

    def test_creates_missing_output_and_can_reset_it_again(self):
        with tempfile.TemporaryDirectory() as temporary:
            result=reset_compiler_output(temporary)
            self.assertTrue(result.is_dir())
            self.assertEqual(result,reset_compiler_output(temporary))

    def test_qa_build_does_not_retain_deleted_test_classes(self):
        with tempfile.TemporaryDirectory() as temporary:
            classes=Path(temporary)/'qa-classes';classes.mkdir()
            (classes/'RetiredCheck.class').write_bytes(b'old')
            self.assertEqual([],list(reset_compiler_output(temporary,'qa-classes').iterdir()))

    def test_rejects_file_at_output_path_without_deleting_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            output=Path(temporary)/'classes'
            output.write_text('preserve me')
            with self.assertRaises(ValueError):reset_compiler_output(temporary)
            self.assertEqual('preserve me',output.read_text())

    def test_rejects_redirected_output_and_descendants(self):
        for nested in (False,True):
            with self.subTest(nested=nested),tempfile.TemporaryDirectory() as temporary:
                parent=Path(temporary)
                work=parent/'work';work.mkdir()
                outside=parent/'outside';outside.mkdir()
                sentinel=outside/'preserve.txt';sentinel.write_text('outside')
                link=work/'classes'
                if nested:
                    link.mkdir();link=link/'redirect'
                try:
                    link.symlink_to(outside,target_is_directory=True)
                except (OSError,NotImplementedError) as error:
                    self.skipTest('Directory symlinks unavailable: '+str(error))
                with self.assertRaises(ValueError):reset_compiler_output(work)
                self.assertEqual('outside',sentinel.read_text())
                self.assertTrue(link.is_symlink())

    def test_resolved_escapes_are_rejected_before_any_deletion(self):
        # Exercise junction/redirect resolution even on Windows installations
        # where the test account cannot create a real symbolic link.
        for nested in (False,True):
            with self.subTest(nested=nested),tempfile.TemporaryDirectory() as temporary:
                root=Path(temporary).resolve()
                work=root/'work';work.mkdir()
                classes=work/'classes';classes.mkdir()
                sentinel=classes/'Keep.class';sentinel.write_bytes(b'preserve')
                redirected=classes/'redirect' if nested else classes
                if nested:redirected.mkdir()
                outside=root/'outside';outside.mkdir()
                original_resolve=Path.resolve
                def resolve(path,*args,**kwargs):
                    if path==redirected:return outside
                    return original_resolve(path,*args,**kwargs)
                with patch.object(Path,'resolve',resolve):
                    with self.assertRaises(ValueError):reset_compiler_output(work)
                self.assertEqual(b'preserve',sentinel.read_bytes())
                self.assertTrue(outside.is_dir())


class ApkOutputTests(unittest.TestCase):
    def test_removes_obsolete_apks_without_touching_current_or_private_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            output=Path(temporary)/'output';output.mkdir()
            for name in ('base.apk','base.apk.idsig','split_config.arm64.apk','retired.apk','retired.apk.idsig','notes.txt'):
                (output/name).write_text(name)
            self.assertEqual(output.resolve(),clean_apk_output(temporary,{'base.apk','split_config.arm64.apk'}))
            self.assertEqual({'base.apk','base.apk.idsig','split_config.arm64.apk','notes.txt'},{item.name for item in output.iterdir()})

    def test_rejects_redirected_output_before_deleting(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary).resolve();output=root/'output';output.mkdir()
            stale=output/'retired.apk';stale.write_bytes(b'preserve')
            original=Path.resolve
            def resolve(path,*args,**kwargs):return root/'outside' if path==output else original(path,*args,**kwargs)
            with patch.object(Path,'resolve',resolve):
                with self.assertRaises(ValueError):clean_apk_output(root,{'base.apk'})
            self.assertEqual(b'preserve',stale.read_bytes())

if __name__=='__main__':unittest.main()
