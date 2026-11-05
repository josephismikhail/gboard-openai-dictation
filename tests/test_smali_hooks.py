import unittest
from tools.smali_hooks import hook_void_entry,hook_void_returns


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

    def test_selection_hook_runs_before_stock_overwrites_parameter_registers(self):
        signature = 'onUpdateSelection(IIIIII)V'
        call = 'invoke-static/range {p0 .. p4}, Lorg/gboardopenai/VoiceRuntime;->selectionChanged(Landroid/inputmethodservice/InputMethodService;IIII)V'
        source = self.fixture().replace(self.signature, signature).replace('    if-eqz p1, :end', '    iget-object p0, p0, Ltest/Ime;->helper:Ltest/Helper;\n    if-eqz p1, :end')
        result = hook_void_entry(source, signature, call)
        self.assertEqual(result.count(call), 1)
        self.assertLess(result.index(call), result.index('iget-object p0'))
        self.assertIn('.locals 19', result)
        self.assertEqual(result, hook_void_entry(result, signature, call))
        legacy = hook_void_returns(source, signature, call)
        self.assertEqual(result, hook_void_entry(legacy, signature, call))

    def test_entry_hook_rejects_missing_register_declaration(self):
        with self.assertRaises(ValueError):
            hook_void_entry(self.fixture().replace('    .locals 19\n', ''), self.signature, self.call)

    def test_unknown_or_ambiguous_method_fails_closed(self):
        for source in ('', self.fixture() + self.fixture()):
            with self.assertRaises(ValueError):
                hook_void_returns(source, self.signature, self.call)

    def test_throwing_only_method_fails_closed(self):
        with self.assertRaises(ValueError):
            hook_void_returns(self.fixture().replace('return-void', 'throw p1'), self.signature, self.call)
