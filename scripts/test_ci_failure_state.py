import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from ci_failure_state import collect_failure_state, diagnostics_enabled, probe_adb

ROOT = Path(__file__).resolve().parent.parent
ENV = {'DISTILLPOD_FAILURE_DIAGNOSTICS': 'true', 'GITHUB_ACTIONS': 'true',
       'RUNNER_ENVIRONMENT': 'github-hosted', 'DISTILLPOD_SERIAL': 'emulator-5554'}


class FailureStateTest(unittest.TestCase):
    def test_requires_explicit_hosted_emulator_context(self):
        self.assertTrue(diagnostics_enabled(ENV))
        for key in ENV:
            env = dict(ENV); env.pop(key)
            self.assertFalse(diagnostics_enabled(env), key)
        for serial in ['owner-phone-private-id', '', 'emulator-5554; private']:
            self.assertFalse(diagnostics_enabled(dict(ENV, DISTILLPOD_SERIAL=serial)))

    def test_snapshot_contains_only_allowlisted_fields_and_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            (proc / 'meminfo').write_text('MemTotal: 100 kB\nMemAvailable: 25 kB\nPrivate: secret\n')
            (proc / 'vmstat').write_text('oom_kill 2\nprivate secret\n')
            for name, comm, state in [('1', 'qemu-system-x86', 'S'), ('2','adb','Z'),
                                      ('3','personal-secret','R')]:
                d = proc / name; d.mkdir(); (d / 'comm').write_text(comm+'\n')
                (d / 'status').write_text(f'Name:\t{comm}\nState:\t{state} (private)\n')
            data = collect_failure_state('startup', ENV, proc=proc,
                                        transport=lambda serial: 'offline')
            self.assertEqual('offline', data['adb_state'])
            self.assertEqual(1, data['process_counts']['emulator'])
            self.assertEqual(1, data['process_counts']['adb_zombie'])
            self.assertEqual({'MemTotal':100,'MemAvailable':25},data['memory_kib'])
            self.assertEqual(2,data['host_oom_kills_total'])
            self.assertNotIn('secret',json.dumps(data)); self.assertNotIn('5554',json.dumps(data))

    def test_process_scan_stops_at_its_entry_bound(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory); entry = proc / '1'; entry.mkdir()
            (entry / 'comm').write_text('adb')
            with patch.object(Path, 'iterdir', return_value=iter([entry] * 4097)):
                data = collect_failure_state('startup', ENV, proc=proc,
                                             transport=lambda serial: 'offline')
            self.assertTrue(data['process_scan_truncated'])
            self.assertEqual(4096, data['process_counts']['adb'])

    def test_process_loss_and_unavailable_host_fields_remain_explicit(self):
        with tempfile.TemporaryDirectory() as directory:
            data = collect_failure_state('startup', ENV, proc=Path(directory),
                                         transport=lambda serial: 'unknown')
            self.assertEqual(0, data['process_counts']['emulator'])
            self.assertEqual({}, data['memory_kib'])
            self.assertNotIn('host_oom_kills_total', data)
            data = collect_failure_state('startup', ENV, proc=Path(directory)/'absent',
                                         transport=lambda serial: 'timeout')
            self.assertTrue(data['process_scan_unavailable'])
            self.assertEqual('timeout',data['adb_state'])

    def test_disabled_context_never_invokes_transport(self):
        def forbidden(serial): raise AssertionError('phone probe')
        self.assertIsNone(collect_failure_state('startup', {}, transport=forbidden))

    def fake_adb(self, directory, script):
        p = Path(directory) / 'adb'
        p.write_text('#!/usr/bin/env python3\n'+script+'\n'); p.chmod(0o755)
        return dict(os.environ, PATH=directory+os.pathsep+os.environ['PATH'])

    def test_adb_classifies_without_retaining_raw_output_and_bounds_timeout(self):
        from unittest.mock import patch
        for script, expected in [("print('device')", 'device'),
                                 ("import sys; print('error: device offline',file=sys.stderr); sys.exit(1)", 'offline'),
                                 ("print('personal-secret')", 'unknown'),
                                 ("import time; time.sleep(4)", 'timeout')]:
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as directory:
                with patch.dict(os.environ, self.fake_adb(directory,script), clear=True):
                    self.assertEqual(expected,probe_adb('emulator-5554'))

    def test_wrapper_keeps_failure_and_records_no_command_or_content(self):
        with tempfile.TemporaryDirectory() as directory:
            env = self.fake_adb(directory,"print('private-secret')")
            state = Path(directory)/'failure.jsonl'
            env.update(ENV, DISTILLPOD_FAILURE_STATE=str(state),
                       DISTILLPOD_PHASE_TIMINGS=str(Path(directory)/'timing.jsonl'))
            run = subprocess.run([sys.executable,str(ROOT/'scripts/ci_phase.py'),'startup','--',
                                  sys.executable,'-c','raise SystemExit(7)'],env=env,capture_output=True)
            self.assertEqual(7,run.returncode)
            data=json.loads(state.read_text())
            self.assertEqual('unknown',data['adb_state'])
            self.assertNotIn('private-secret',state.read_text())
            env['DISTILLPOD_FAILURE_STATE']=directory
            run = subprocess.run([sys.executable,str(ROOT/'scripts/ci_phase.py'),'startup','--',
                                  sys.executable,'-c','raise SystemExit(9)'],env=env,capture_output=True)
            self.assertEqual(9,run.returncode)

    def test_unexpected_diagnostic_error_cannot_mask_command_failure(self):
        import contextlib
        import io
        from unittest.mock import patch
        import ci_phase
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(sys, 'argv', ['ci_phase.py', 'startup', '--', 'false']), \
                 patch.dict(os.environ, DISTILLPOD_PHASE_TIMINGS=str(Path(directory)/'timing.jsonl')), \
                 patch('ci_phase.record_failure_state', side_effect=RuntimeError('private-secret')), \
                 contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(1, ci_phase.main())
                self.assertNotIn('private-secret', output.getvalue())

    def test_success_does_not_probe_or_write_failure_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            env=dict(os.environ,**ENV,DISTILLPOD_FAILURE_STATE=str(Path(directory)/'failure.jsonl'),
                     DISTILLPOD_PHASE_TIMINGS=str(Path(directory)/'timing.jsonl'))
            run=subprocess.run([sys.executable,str(ROOT/'scripts/ci_phase.py'),'startup','--','true'],env=env)
            self.assertEqual(0,run.returncode)
            self.assertFalse((Path(directory)/'failure.jsonl').exists())
