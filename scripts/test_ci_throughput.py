import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from ci_validation import choose_route, validate_gate
from test_ci_validation import Repository, results, SHA

ROOT = Path(__file__).resolve().parent.parent


class DraftRoutingTest(unittest.TestCase):
    def test_code_draft_defers_but_ready_runs_full_on_same_revision(self):
        with Repository() as repo:
            repo.write('app/example.kt', 'code\n')
            head = repo.commit('code')
            self.assertEqual('draft', repo.select(draft=True).mode)
            self.assertEqual('full', repo.select(draft=False).mode)
            self.assertEqual(head, repo.select(draft=True).checkout_sha)

    def test_docs_still_validate_and_missing_evidence_still_fails(self):
        with Repository() as repo:
            repo.write('README.md', 'Docs\n')
            repo.commit('docs')
            self.assertEqual('docs', repo.select(draft=True).mode)
            with self.assertRaises(ValueError):
                choose_route(repo.root, 'pull_request', {}, None, None, draft=True)

    def test_only_literal_true_and_direct_pr_can_defer(self):
        with Repository() as repo:
            for event, inputs, draft in [('push', {}, True), ('workflow_dispatch', {}, True),
                                         ('pull_request', {'build_release': False}, True),
                                         ('pull_request', {}, 'true'), ('pull_request', {}, None)]:
                with self.subTest(event=event, inputs=inputs, draft=draft):
                    self.assertEqual('full', choose_route(repo.root, event, inputs,
                        repo.base, repo.base, draft=draft).mode)

    def test_draft_never_satisfies_required_gate(self):
        data = results('draft')
        for job in ['android_build', 'native', 'firebase', 'emulator']:
            data[job]['result'] = 'skipped'
        with self.assertRaisesRegex(ValueError, 'Draft.*incomplete'):
            validate_gate(data, 'pull_request', {}, SHA)


class PhaseTimingTest(unittest.TestCase):
    def run_phase(self, directory, code):
        target = Path(directory) / 'timing.jsonl'
        env = dict(os.environ, DISTILLPOD_PHASE_TIMINGS=str(target))
        run = subprocess.run([sys.executable, str(ROOT / 'scripts/ci_phase.py'),
                              'startup', '--', sys.executable, '-c', code],
                             env=env, capture_output=True, text=True)
        return run, [json.loads(line) for line in target.read_text().splitlines()]

    def test_records_success_and_failure_without_command_or_content(self):
        with tempfile.TemporaryDirectory() as directory:
            run, rows = self.run_phase(directory, "print('private-marker'); raise SystemExit(0)")
            self.assertEqual(0, run.returncode)
            self.assertIn('private-marker', run.stdout)
            run, rows = self.run_phase(directory, 'raise SystemExit(7)')
            self.assertEqual(7, run.returncode)
            self.assertEqual([0, 7], [r['exit_code'] for r in rows])
            self.assertEqual({'phase', 'elapsed_seconds', 'exit_code'}, set(rows[0]))
            self.assertGreaterEqual(rows[0]['elapsed_seconds'], 0)
            self.assertNotIn('private-marker', json.dumps(rows))

    def test_missing_executable_is_recorded_and_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'timing.jsonl'
            run = subprocess.run([sys.executable, str(ROOT / 'scripts/ci_phase.py'),
                                  'startup', '--', '/nonexistent/distillpod-command'],
                                 env=dict(os.environ, DISTILLPOD_PHASE_TIMINGS=str(target)),
                                 capture_output=True)
            self.assertEqual(127, run.returncode)
            self.assertEqual(127, json.loads(target.read_text())['exit_code'])

    def test_signal_exit_and_timing_write_error_preserve_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            run, rows = self.run_phase(directory,
                'import os, signal; os.kill(os.getpid(), signal.SIGTERM)')
            self.assertEqual(143, run.returncode)
            self.assertEqual(143, rows[0]['exit_code'])
            for code in [0, 7]:
                run = subprocess.run([sys.executable, str(ROOT / 'scripts/ci_phase.py'),
                                      'startup', '--', sys.executable, '-c',
                                      f'raise SystemExit({code})'], capture_output=True,
                                     env=dict(os.environ, DISTILLPOD_PHASE_TIMINGS=directory))
                self.assertEqual(code or 1, run.returncode)

    def test_failure_prevents_later_phase_in_shell(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / 'later'
            run = subprocess.run(['bash', '-ec',
                'python3 scripts/ci_phase.py startup -- false\ntouch "$LATER"'], cwd=ROOT,
                env=dict(os.environ, LATER=str(marker),
                         DISTILLPOD_PHASE_TIMINGS=str(Path(directory) / 'timing.jsonl')))
            self.assertNotEqual(0, run.returncode)
            self.assertFalse(marker.exists())
