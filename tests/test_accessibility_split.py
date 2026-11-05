import unittest
from tools.accessibility_split import resource_ids


class AccessibilitySplitTests(unittest.TestCase):
    def test_reserves_next_unused_entry_without_changing_existing_type_ids(self):
        ids=resource_ids('''resource 0x7f171248 xml/obfuscated
            resource 0x7f17124a xml/splits0
            resource 0x7f150005 string/title
            resource 0x7f150002 string/other''')
        self.assertEqual(0x7f17124b,ids['xml/gboard_openai_messages_service'])
        self.assertEqual(0x7f150006,ids['string/gboard_openai_messages_description'])

    def test_rejects_missing_ambiguous_and_full_types(self):
        for data in ['', 'resource 0x7f17ffff xml/full\nresource 0x7f150001 string/name',
                     'resource 0x7f170001 xml/one\nresource 0x7f180002 xml/two\nresource 0x7f150001 string/name']:
            with self.assertRaises(ValueError):resource_ids(data)
