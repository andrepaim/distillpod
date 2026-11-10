"""Behavioral routing/gate regressions using complete synthetic Git histories."""
import contextlib
import subprocess
import tempfile
import unittest
from pathlib import Path

from ci_validation import choose_route, validate_gate


class Repository(contextlib.AbstractContextManager):
    def __init__(self, initial=None):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        for name, content in (initial or {'ROOT.txt': 'base\n'}).items():
            self.write(name, content)
        self.base = self.commit('base')

    def git(self, *args, input=None):
        return subprocess.check_output(
            ['git', '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.test',
             '-c', 'commit.gpgsign=false', *args], cwd=self.root, input=input, text=True,
            stderr=subprocess.DEVNULL).strip()

    def write(self, name, content):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)

    def commit(self, message):
        self.git('add', '-A')
        self.git('commit', '-qm', message)
        return self.git('rev-parse', 'HEAD')

    def select(self, **kwargs):
        return choose_route(self.root, event='pull_request', inputs={},
                            base=self.base, head=self.git('rev-parse', 'HEAD'), **kwargs)

    def __exit__(self, *args):
        self.temp.cleanup()


class RoutingTests(unittest.TestCase):
    def test_documentation_and_reviewed_media_select_docs(self):
        with Repository() as repo:
            for path in ['README.md', 'docs/guide.md', 'docs/image.png',
                         'docs/demo.mp4', 'docs/android/evidence/report.json',
                         'firebase/README.md', 'design/icon-proposal/assets/Manrope-OFL.txt']:
                repo.write(path, 'Documentation\n')
            repo.commit('documentation')
            result = repo.select()
            self.assertEqual('docs', result.mode)
            self.assertEqual(7, len(result.paths))

    def test_code_config_resources_and_unknown_paths_always_select_full(self):
        for path in ['app/src/main/res/raw/prompt.md', 'app/src/main/K.kt',
                     'docs/example.py', 'docs/config.yml', '.github/workflows/ci.yml',
                     'gradle.properties', 'scripts/tool.py', 'firebase/firestore.rules',
                     'integrations/chatgpt/README.md', 'unknown/new.file']:
            with self.subTest(path=path), Repository() as repo:
                repo.write('README.md', 'Docs\n')
                repo.write(path, 'changed\n')
                repo.commit('mixed')
                self.assertEqual('full', repo.select().mode)

    def test_full_diff_includes_code_after_more_than_300_documentation_paths(self):
        with Repository() as repo:
            for i in range(301):
                repo.write(f'docs/page-{i:04}.md', 'Docs\n')
            repo.write('zzz/source.kt', 'code\n')
            repo.commit('large mixed diff')
            result = repo.select()
            self.assertEqual('full', result.mode)
            self.assertEqual(302, len(result.paths))

    def test_both_rename_sides_are_inspected(self):
        with Repository({'app/source.kt': 'code\n'}) as repo:
            (repo.root / 'docs').mkdir()
            repo.git('mv', 'app/source.kt', 'docs/renamed.md')
            repo.commit('disguised code deletion')
            self.assertEqual('full', repo.select().mode)
        with Repository({'docs/old.md': 'Docs\n'}) as repo:
            repo.git('mv', 'docs/old.md', 'docs/new.md')
            repo.commit('rename documentation')
            result = repo.select()
            self.assertEqual('docs', result.mode)
            self.assertEqual({'docs/old.md', 'docs/new.md'}, set(result.paths))

    def test_deletions_and_empty_diff_are_conservative(self):
        with Repository({'docs/old.md': 'Docs\n'}) as repo:
            (repo.root / 'docs/old.md').unlink()
            repo.commit('delete doc')
            self.assertEqual('docs', repo.select().mode)
        with Repository() as repo:
            self.assertEqual('full', repo.select().mode)

    def test_executable_symlink_and_submodule_modes_cannot_select_docs(self):
        with Repository({'README.md': 'Docs\n'}) as repo:
            (repo.root / 'README.md').chmod(0o755)
            repo.commit('executable')
            self.assertEqual('full', repo.select().mode)
        with Repository() as repo:
            (repo.root / 'docs').mkdir()
            (repo.root / 'docs/link.md').symlink_to('../ROOT.txt')
            repo.commit('symlink')
            self.assertEqual('full', repo.select().mode)
        with Repository() as repo:
            repo.git('update-index', '--add', '--cacheinfo', f'160000,{repo.base},docs/module.md')
            repo.git('commit', '-qm', 'gitlink')
            self.assertEqual('full', repo.select().mode)

    def test_missing_shallow_and_ambiguous_git_evidence_fail_visibly(self):
        with Repository() as repo:
            repo.write('README.md', 'Docs\n')
            head = repo.commit('doc')
            with self.assertRaises(ValueError):
                choose_route(repo.root, 'pull_request', {}, 'f' * 40, head)
            with tempfile.TemporaryDirectory() as target:
                clone = Path(target) / 'clone'
                subprocess.run(['git', 'clone', '-q', '--depth=1', repo.root.as_uri(), str(clone)], check=True)
                with self.assertRaisesRegex(ValueError, 'shallow|complete'):
                    choose_route(clone, 'pull_request', {}, repo.base, head)
            tree = repo.git('rev-parse', 'HEAD^{tree}')
            a = repo.git('commit-tree', tree, '-p', repo.base, input='a\n')
            b = repo.git('commit-tree', tree, '-p', repo.base, input='b\n')
            left = repo.git('commit-tree', tree, '-p', a, '-p', b, input='left\n')
            right = repo.git('commit-tree', tree, '-p', b, '-p', a, input='right\n')
            with self.assertRaisesRegex(ValueError, 'merge base'):
                choose_route(repo.root, 'pull_request', {}, left, right)

    def test_manual_release_and_reusable_false_inputs_are_full(self):
        for event, inputs in [('push', {}), ('workflow_dispatch', {}), ('workflow_call', {}),
                              ('pull_request', {'build_release': False}),
                              ('pull_request', {'build_release': True}),
                              ('pull_request', None), ('pull_request', [])]:
            with self.subTest(event=event, inputs=inputs), Repository() as repo:
                self.assertEqual('full', choose_route(repo.root, event, inputs, None, None).mode)

    def test_checkout_mismatch_and_new_broken_links_or_whitespace_fail(self):
        with Repository() as repo:
            repo.write('README.md', 'Docs\n')
            repo.commit('doc')
            with self.assertRaisesRegex(ValueError, 'checkout'):
                repo.select(checkout_sha='f' * 40)
        with Repository() as repo:
            repo.write('README.md', '[missing](docs/missing.md)\n')
            repo.commit('broken link')
            with self.assertRaisesRegex(ValueError, 'link'):
                repo.select()
        with Repository() as repo:
            repo.write('README.md', 'Private-content-marker   \n')
            repo.commit('whitespace')
            with self.assertRaises(ValueError) as caught:
                repo.select()
            self.assertNotIn('Private-content-marker', str(caught.exception))


SHA = 'a' * 40
JOBS = ['android_build', 'firebase', 'native', 'emulator']


def results(mode='full'):
    return {'security': {'result': 'success'}, 'plan': {'result': 'success', 'outputs': {
        'mode': mode, 'docs_validated': 'true' if mode == 'docs' else 'not_selected',
        'checkout_sha': SHA, 'changed_count': '1'}},
        **{job: {'result': 'skipped' if mode == 'docs' else 'success'} for job in JOBS}}


class GateTests(unittest.TestCase):
    def test_full_success_and_intentional_documentation_omissions_pass(self):
        self.assertIsNone(validate_gate(results(), 'pull_request', {}, SHA))
        self.assertIsNone(validate_gate(results('docs'), 'pull_request', {}, SHA))
        self.assertIsNone(validate_gate(results(), 'workflow_dispatch', {}, SHA))
        self.assertIsNone(validate_gate(results(), 'push', {'build_release': False}, SHA))
        self.assertIsNone(validate_gate(results(), 'pull_request', {'build_release': False}, SHA))

    def test_security_must_succeed_even_when_all_expensive_jobs_are_omitted(self):
        for mode in ['docs', 'full']:
            for status in ['failure', 'cancelled', 'skipped']:
                data = results(mode)
                data['security']['result'] = status
                with self.subTest(mode=mode, status=status), self.assertRaisesRegex(ValueError, 'security'):
                    validate_gate(data, 'pull_request', {}, SHA)

    def test_direct_push_uses_existing_independent_security_without_a_duplicate(self):
        data = results()
        data['security']['result'] = 'skipped'
        self.assertIsNone(validate_gate(data, 'push', {}, SHA))
        data['security']['result'] = 'failure'
        with self.assertRaises(ValueError):
            validate_gate(data, 'push', {}, SHA)

    def test_selected_failure_cancellation_skip_unknown_or_missing_never_pass(self):
        for job in JOBS:
            for status in ['failure', 'cancelled', 'skipped', 'unknown', None]:
                data = results()
                data[job]['result'] = status
                with self.subTest(job=job, status=status), self.assertRaises(ValueError):
                    validate_gate(data, 'pull_request', {}, SHA)
        data = results()
        del data['emulator']
        with self.assertRaises(ValueError):
            validate_gate(data, 'pull_request', {}, SHA)

    def test_failed_classifier_unvalidated_docs_wrong_revision_and_unknown_route_fail(self):
        for field, value in [('docs_validated', 'false'), ('mode', 'unknown'),
                             ('checkout_sha', 'b' * 40), ('changed_count', '0')]:
            data = results('docs')
            data['plan']['outputs'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_gate(data, 'pull_request', {}, SHA)
        data = results('docs')
        data['plan']['result'] = 'failure'
        with self.assertRaises(ValueError):
            validate_gate(data, 'pull_request', {}, SHA)

    def test_manual_or_reusable_cannot_claim_documentation_success(self):
        for event, inputs in [('workflow_dispatch', {}), ('push', {}),
                              ('pull_request', {'build_release': False})]:
            with self.subTest(event=event), self.assertRaises(ValueError):
                validate_gate(results('docs'), event, inputs, SHA)
