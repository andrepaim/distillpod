"""Functional Gradle-model checks; run deliberately with the shared build resource."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


class EmulatorAbiTest(unittest.TestCase):
    def probe(self, env=None, task=':app:probe', initial="['arm64-v8a','x86_64']"):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            (target / 'settings.gradle').write_text("rootProject.name='abi-fixture'\ninclude ':app'\n")
            (target / 'app').mkdir()
            (target / 'app/build.gradle').write_text("""
                ext.android = [defaultConfig: [ndk: [abiFilters: new LinkedHashSet(INITIAL)]]]
                tasks.register('probe') { doLast { println 'ABI_RESULT=' + android.defaultConfig.ndk.abiFilters.join(',') } }
                tasks.register('assembleRelease')
                tasks.register('validationAlias') { dependsOn 'assembleRelease' }
            """.replace('INITIAL', initial))
            options = dict(os.environ)
            for name in ['DISTILLPOD_EMULATOR_ABI', 'GITHUB_ACTIONS', 'RUNNER_ENVIRONMENT', 'DISTILLPOD_SERIAL']:
                options.pop(name, None)
            options.update(env or {})
            return subprocess.run([str(ROOT / 'gradlew'), '--console=plain', '-q', '-p', str(target),
                '-I', str(ROOT / 'scripts/emulator-abi.init.gradle'), task], env=options,
                text=True, capture_output=True)

    def configured(self):
        return dict(DISTILLPOD_EMULATOR_ABI='x86_64', GITHUB_ACTIONS='true',
                    RUNNER_ENVIRONMENT='github-hosted', DISTILLPOD_SERIAL='emulator-5554')

    def test_default_and_selected_disposable_emulator(self):
        default = self.probe()
        self.assertEqual(0, default.returncode, default.stderr)
        self.assertIn('ABI_RESULT=arm64-v8a,x86_64', default.stdout)
        selected = self.probe(self.configured())
        self.assertEqual(0, selected.returncode, selected.stderr)
        self.assertIn('ABI_RESULT=x86_64', selected.stdout)

    def test_rejects_unknown_abi_nonhosted_and_physical_targets(self):
        for key, value in [('DISTILLPOD_EMULATOR_ABI','arm64-v8a'), ('GITHUB_ACTIONS','false'),
                           ('RUNNER_ENVIRONMENT','self-hosted'), ('DISTILLPOD_SERIAL','physical')]:
            env = self.configured(); env[key] = value
            with self.subTest(key=key):
                result = self.probe(env)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Emulator ABI selection', result.stderr)

    def test_rejects_release_and_unexpected_default_configuration(self):
        result = self.probe(self.configured(), ':app:assembleRelease')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Emulator ABI selection', result.stderr)
        result = self.probe(self.configured(), ':app:validationAlias')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Emulator ABI selection', result.stderr)
        result = self.probe(self.configured(), initial="['arm64-v8a']")
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Emulator ABI selection', result.stderr)


if __name__ == '__main__':
    unittest.main()
