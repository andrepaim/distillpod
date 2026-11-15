import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

import ci_logcat

ENV = {'DISTILLPOD_FAILURE_DIAGNOSTICS': 'true', 'GITHUB_ACTIONS': 'true',
       'RUNNER_ENVIRONMENT': 'github-hosted', 'DISTILLPOD_SERIAL': 'emulator-5554'}


def fake_spawn(lines):
    """Stands in for adb: writes the given stream and stays alive until stopped."""
    def spawn(serial, output):
        assert serial == 'emulator-5554'
        script = 'import sys, time\nsys.stdout.write(sys.argv[1]); sys.stdout.flush()\ntime.sleep(60)'
        return subprocess.Popen([sys.executable, '-c', script, lines], stdout=output)
    return spawn


class LogcatCaptureTest(unittest.TestCase):
    def capture(self, directory, lines):
        env = dict(ENV, DISTILLPOD_LOGCAT_DIR=directory)
        capture = ci_logcat.start('ordinary', env, spawn=fake_spawn(lines))
        for _ in range(100):
            if capture.path.stat().st_size >= len(lines):
                break
            time.sleep(0.05)
        return env, capture

    def test_disabled_outside_explicit_hosted_emulator_context(self):
        def forbidden(*_):
            raise AssertionError('must not start adb')
        self.assertIsNone(ci_logcat.start('ordinary', {}, spawn=forbidden))
        for key in ENV:
            env = dict(ENV); env.pop(key)
            self.assertIsNone(ci_logcat.start('ordinary', env, spawn=forbidden), key)
        self.assertIsNone(ci_logcat.start('ordinary', dict(ENV, DISTILLPOD_SERIAL='owner-phone'), spawn=forbidden))
        self.assertIsNone(ci_logcat.finish(None, 'ordinary', failed=True, env=ENV))

    def test_failed_phase_keeps_the_stream_and_stops_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            env, capture = self.capture(directory, 'first\nsecond\n')
            target = ci_logcat.finish(capture, 'ordinary', failed=True, env=env)
            self.assertEqual(Path(directory) / 'distillpod-logcat-ordinary.txt', target)
            self.assertEqual('first\nsecond\n', target.read_text())
            self.assertIsNotNone(capture.process.poll())
            self.assertFalse(capture.path.exists())

    def test_successful_phase_discards_the_stream(self):
        with tempfile.TemporaryDirectory() as directory:
            env, capture = self.capture(directory, 'private\n')
            self.assertIsNone(ci_logcat.finish(capture, 'ordinary', failed=False, env=env))
            self.assertEqual([], os.listdir(directory))
            self.assertFalse(capture.path.exists())
            self.assertIsNotNone(capture.process.poll())

    def test_large_stream_keeps_newest_whole_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            env, capture = self.capture(directory, 'old line\n' * 20 + 'newest\n')
            target = ci_logcat.finish(capture, 'ordinary', failed=True, env=env, limit=30)
            text = target.read_text()
            self.assertTrue(text.endswith('newest\n'))
            self.assertLessEqual(len(text), 30)
            self.assertTrue(all(line in {'old line', 'newest'} for line in text.splitlines()))


if __name__ == '__main__':
    unittest.main()
