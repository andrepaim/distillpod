"""Repository-local link behavior, including unchanged inbound references."""
import tempfile
import unittest
from pathlib import Path
from docs_validation import check_links


class DocumentationTests(unittest.TestCase):
    def check(self, files, changed):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for name, content in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            markdown = [name for name in files if name.endswith('.md')]
            return check_links(root, markdown, set(changed))

    def test_inline_reference_image_unicode_and_duplicate_heading_targets(self):
        files = {'README.md': '[Guide](docs/a.md#hello-world)\n[Again][ref]\n'
                 '[ref]: docs/a.md#hello-world-1\n![image](<docs/picture name.png>)\n'
                 '[accent](docs/a.md#résumé)\n',
                 'docs/a.md': '# Hello world\n# Hello world\n# Résumé\n',
                 'docs/picture name.png': 'fixture'}
        self.assertEqual([], self.check(files, ['README.md']))

    def test_changed_source_missing_file_and_anchor_are_errors(self):
        for link in ['docs/missing.md', 'docs/a.md#missing']:
            with self.subTest(link=link):
                errors = self.check({'README.md': f'[Guide]({link})', 'docs/a.md': '# Present'}, ['README.md'])
                self.assertTrue(errors)
                self.assertTrue(any('link' in item.lower() for item in errors))

    def test_unchanged_inbound_links_fail_after_target_deletion_or_heading_change(self):
        self.assertTrue(self.check({'README.md': '[Old](docs/old.md)'}, ['docs/old.md']))
        self.assertTrue(self.check({'README.md': '[Old](docs/a.md#old)',
                                   'docs/a.md': '# New'}, ['docs/a.md']))

    def test_legacy_unrelated_failures_do_not_block_incremental_validation(self):
        self.assertEqual([], self.check({'README.md': '[Legacy](missing.md)',
                                        'docs/new.md': '# New'}, ['docs/new.md']))

    def test_fenced_and_inline_examples_and_external_links_are_not_local_targets(self):
        content = '```md\n[example](missing.md)\n```\n`[example](missing.md)`\n'
        content += '[web](https://example.test/a)\n[email](mailto:fixture@example.test)\n'
        self.assertEqual([], self.check({'README.md': content}, ['README.md']))

    def test_a_language_label_does_not_close_a_fenced_example(self):
        content = '```md\n```example\n[example](missing.md)\n```\n'
        self.assertEqual([], self.check({'README.md': content}, ['README.md']))

    def test_explicit_html_anchors_and_html_links_are_checked(self):
        self.assertEqual([], self.check({'README.md': '<a href="docs/a.md#custom">A</a>',
                                        'docs/a.md': '<a id="custom"></a>'}, ['README.md']))
        self.assertTrue(self.check({'README.md': '<a href="missing.md">A</a>'}, ['README.md']))

    def test_paths_cannot_escape_repository_or_follow_external_symlinks(self):
        self.assertTrue(self.check({'README.md': '[escape](../outside.md)'}, ['README.md']))
        with tempfile.TemporaryDirectory() as folder, tempfile.TemporaryDirectory() as outside:
            root = Path(folder)
            (root / 'README.md').write_text('[escape](leak.md)')
            (root / 'leak.md').symlink_to(Path(outside) / 'private.md')
            (Path(outside) / 'private.md').write_text('Private-content-marker')
            errors = check_links(root, ['README.md'], {'README.md'})
            self.assertTrue(errors)
            self.assertNotIn('Private-content-marker', '\n'.join(errors))
