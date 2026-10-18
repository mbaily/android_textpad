"""Byte/format preservation and compatibility with the server's merge rules."""
import random
import unittest

from textpad_sync.merge import merge_contents
from textpad_server.codec import decode, merge_3way


class MergeTests(unittest.TestCase):
    def test_disjoint_lines_and_words_merge(self):
        for base, remote, local, expected in (
            (b'one\ntwo\nthree\n', b'ONE\ntwo\nthree\n', b'one\ntwo\nTHREE\n', b'ONE\ntwo\nTHREE\n'),
            (b'The quick fox likes milk\n', b'The FAST fox likes milk\n',
             b'The quick fox likes tea\n', b'The FAST fox likes tea\n'),
            (b'one\ntwo\n', b'ONE\ntwo\n', b'one\nTWO\n', b'ONE\nTWO\n'),
        ):
            with self.subTest(base=base):
                self.assertEqual(expected, merge_contents(base, remote, local))

    def test_overlapping_replacements_and_insertions_are_refused(self):
        for base, remote, local in (
            (b'one\n', b'remote\n', b'local\n'),
            (b'one\ntwo\n', b'one\nremote\ntwo\n', b'one\nlocal\ntwo\n'),
            (b'The quick fox\n', b'The FAST fox\n', b'The SLOW fox\n'),
        ):
            with self.subTest(base=base):
                self.assertIsNone(merge_contents(base, remote, local))

    def test_unchanged_and_identical_sides_remain_byte_exact(self):
        base = b'\xef\xbb\xbfone\r\ntwo\rthree\nlast'
        changed = base.replace(b'two', b'TWO')
        self.assertEqual(changed, merge_contents(base, base, changed))
        self.assertEqual(changed, merge_contents(base, changed, base))
        self.assertEqual(changed, merge_contents(base, changed, changed))

    def test_encodings_boms_mixed_endings_and_no_final_newline_survive_merging(self):
        for encoding, prefix in (('utf-8', b''), ('utf-8', b'\xef\xbb\xbf'),
                                 ('utf-16-le', b'\xff\xfe'), ('utf-16-be', b'\xfe\xff')):
            with self.subTest(encoding=encoding, prefix=prefix):
                def raw(value):
                    return prefix + value.encode(encoding)
                base = raw('one café 😀\r\ntwo\rthree\nlast')
                remote = raw('ONE café 😀\r\ntwo\rthree\nlast')
                local = raw('one café 😀\r\ntwo\rTHREE\nlast')
                self.assertEqual(raw('ONE café 😀\r\ntwo\rTHREE\nlast'), merge_contents(base, remote, local))

    def test_format_only_remote_change_combines_with_local_text_edit(self):
        base = b'one\r\ntwo\r\n'
        remote = b'\xff\xfe' + 'one\ntwo\n'.encode('utf-16-le')
        self.assertEqual(b'\xff\xfe' + 'ONE\ntwo\n'.encode('utf-16-le'),
                         merge_contents(base, remote, b'ONE\r\ntwo\r\n'))

    def test_conflicting_encoding_and_line_ending_changes_are_refused(self):
        base = b'one\ntwo\n'
        self.assertIsNone(merge_contents(base, b'\xff\xfe' + 'ONE\ntwo\n'.encode('utf-16-le'),
                                        b'\xfe\xff' + 'one\nTWO\n'.encode('utf-16-be')))
        self.assertIsNone(merge_contents(base, b'ONE\r\ntwo\r\n', b'one\rTWO\r'))

    def test_random_edits_match_server_merge_rules(self):
        randomizer = random.Random(308)
        base = [f'Line {i} has several words' for i in range(12)]
        for case in range(150):
            remote, local = base.copy(), base.copy()
            for target in (remote, local):
                for index in randomizer.sample(range(len(base)), randomizer.randint(1, 4)):
                    target[index] += ' changed ' + str(randomizer.randrange(4))
            raw_base, raw_remote, raw_local = (('\n'.join(lines) + '\n').encode()
                                               for lines in (base, remote, local))
            if raw_remote == raw_local:
                continue
            with self.subTest(case=case):
                expected = merge_3way(*map(decode, (raw_base, raw_remote, raw_local)))
                self.assertEqual(expected, merge_contents(raw_base, raw_remote, raw_local))
