import unittest
from tools.smali_hooks import hook_void_returns


class HookTests(unittest.TestCase):
    signature = 'onComputeInsets(Landroid/inputmethodservice/InputMethodService$Insets;)V'
    call = 'invoke-static/range {p0 .. p1}, Ltest/Hook;->insets(Ljava/lang/Object;Ljava/lang/Object;)V'

    def fixture(self):
        return ('.class public Ltest/Ime;\n.method public final ' + self.signature +
                '\n    .locals 19\n    if-eqz p1, :end\n    return-void\n    :end\n    return-void\n.end method\n'
                '.method public other()V\n    .locals 0\n    return-void\n.end method\n')

    def test_every_exit_is_hooked_without_affecting_other_methods(self):
        result = hook_void_returns(self.fixture(), self.signature, self.call)
        self.assertEqual(result.count(self.call), 2)
        self.assertEqual(result.count(self.call + '\n    return-void'), 2)
        self.assertEqual(result.count('return-void\n.end method'), 2)
        self.assertTrue(result.endswith('.method public other()V\n    .locals 0\n    return-void\n.end method\n'))

    def test_repeated_build_is_identical(self):
        once = hook_void_returns(self.fixture(), self.signature, self.call)
        self.assertEqual(once, hook_void_returns(once, self.signature, self.call))

    def test_unknown_or_ambiguous_method_fails_closed(self):
        for source in ('', self.fixture() + self.fixture()):
            with self.assertRaises(ValueError):
                hook_void_returns(source, self.signature, self.call)

    def test_throwing_only_method_fails_closed(self):
        with self.assertRaises(ValueError):
            hook_void_returns(self.fixture().replace('return-void', 'throw p1'), self.signature, self.call)
