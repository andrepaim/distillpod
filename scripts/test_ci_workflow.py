"""Android CI job-graph contract (#281): the gate, proof verifier and workflow must agree.

Text-level checks of .github/workflows/ci.yml; no third-party YAML parser is required.
"""
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from ci_validation import FULL_JOBS, GATE_NEEDS, RELEASE_JOB
from ci_validation_proof import BUILD_JOBS, REQUIRED, SKIPPED

ROOT = Path(__file__).resolve().parents[1]
TEXT = (ROOT / '.github/workflows/ci.yml').read_text()
JOBS = dict(re.findall(r'^  ([a-z0-9_]+):\n((?:(?:    .*)?\n)+)', TEXT.split('\njobs:\n', 1)[1], re.M))
# Gradle jobs that save their own Gradle User Home entry; other Gradle jobs restore one of these.
WRITERS = {'android_build', 'emulator'}
WRITER_EXPRESSION = ("${{ github.event_name != 'push' || github.ref != 'refs/heads/main' || "
                     "inputs.build_release == true }}")


def name(job):
    return re.search(r'^    name: (.+)$', JOBS[job], re.M).group(1)


def run_lines(text):
    """Yield (line, inside_run_script) for each workflow line."""
    indent = None
    for line in text.splitlines():
        stripped = line.lstrip(' ')
        depth = len(line) - len(stripped)
        if indent is not None and (not stripped or depth > indent):
            yield line, True
            continue
        indent = None
        match = re.match(r'(?:- )?run: ?(.*)$', stripped)
        if match:
            if match.group(1) in {'|', '>'}:
                indent = depth
            else:
                yield line, True
                continue
        yield line, False


class JobGraphTest(unittest.TestCase):
    def test_gate_needs_exactly_the_jobs_the_gate_verifies(self):
        needs = re.search(r'^    needs: \[(.+)\]$', JOBS['android'], re.M).group(1).split(', ')
        self.assertEqual(len(needs), len(set(needs)))
        self.assertEqual(GATE_NEEDS, set(needs))
        self.assertTrue(set(needs) <= set(JOBS))
        self.assertIn('    if: always()\n', JOBS['android'])

    def test_required_check_names_are_unchanged_and_new_names_are_distinct(self):
        self.assertEqual('Kotlin, lint and APK', name('android'))
        self.assertEqual('Native bridge validation', name('native'))
        self.assertEqual('Isolated Android acceptance', name('emulator'))
        names = [name(job) for job in JOBS if job != 'security']
        self.assertEqual(len(names), len(set(names)))

    def test_proof_verifier_names_match_the_workflow_jobs(self):
        self.assertEqual(BUILD_JOBS, {name(job) for job in FULL_JOBS})
        self.assertEqual(SKIPPED, {name(RELEASE_JOB)})
        self.assertEqual(REQUIRED, BUILD_JOBS | {name('plan'), name('android'),
                                                 'Repository security / Secrets and personal data guard'})

    def test_every_heavy_job_runs_only_in_full_mode_and_verifies_its_source(self):
        for job in FULL_JOBS | {RELEASE_JOB}:
            with self.subTest(job=job):
                self.assertIn('    needs: plan\n', JOBS[job])
                condition = re.search(r'^    if: (.+)$', JOBS[job], re.M).group(1)
                self.assertIn("needs.plan.outputs.mode == 'full'", condition)
                self.assertEqual(job == RELEASE_JOB, 'inputs.build_release == true' in condition)
                self.assertIn('      - name: Verify validation source\n'
                              '        run: python3 scripts/ci_validation_proof.py check-source\n', JOBS[job])
                self.assertRegex(JOBS[job], r'\n    timeout-minutes: \d+\n')

    def test_release_candidate_keeps_its_secret_handling_and_artifact_contract(self):
        release = JOBS[RELEASE_JOB]
        for line in ['FIREBASE_CONFIG_JSON: ${{ secrets.FIREBASE_CONFIG_JSON }}',
                     'SENTRY_DSN: ${{ secrets.SENTRY_DSN }}',
                     ': "${FIREBASE_CONFIG_JSON:?Missing Firebase client configuration for distribution}"',
                     ': "${SENTRY_DSN:?Missing Sentry DSN for cloud diagnostics distribution}"',
                     'trap \'rm -f "$config_file"\' EXIT', 'umask 077',
                     '--no-build-cache :app:lintRelease :app:assembleRelease -PdistillpodVoiceEnabled=true',
                     'name: distillpod-unsigned-release',
                     'path: app/build/outputs/apk/release/app-release-unsigned.apk']:
            self.assertIn(line, release)
        upload = release.split('name: distillpod-unsigned-release', 1)[1]
        self.assertIn('if-no-files-found: error', upload)
        self.assertIn('retention-days: 1\n', upload)
        self.assertLess(release.index('umask 077'), release.index('printf'))
        # Repository secrets are read by the release candidate step only.
        self.assertEqual(2, TEXT.count('${{ secrets.'))
        self.assertEqual(2, release.count('${{ secrets.'))

    def test_voice_build_runs_beside_the_default_build(self):
        self.assertNotIn('distillpodVoiceEnabled', JOBS['android_build'])
        self.assertIn(':app:testDebugUnitTest :app:lintDebug :app:assembleDebug -PdistillpodVoiceEnabled=true',
                      JOBS['voice_build'])

    def test_play_build_checks_both_distribution_artifacts(self):
        self.assertNotIn('distillpodDistribution', JOBS['android_build'])
        self.assertIn('verify_distribution_apk.py --distribution github app/build/outputs/apk/debug/app-debug.apk',
                      JOBS['android_build'])
        play = JOBS['play_build']
        self.assertIn(':app:testDebugUnitTest :app:lintDebug :app:assembleDebug -PdistillpodDistribution=play', play)
        self.assertLess(play.index(':app:assembleDebug -PdistillpodDistribution=play'),
                        play.index('verify_distribution_apk.py --distribution play app/build/outputs/apk/debug/app-debug.apk'))
        # Nothing YouTube is fetched for the Play build.
        self.assertNotIn('fetch_youtube_extractor', play)

    def test_emulator_jobs_split_ordinary_acceptance_and_keep_every_phase(self):
        part1, part2 = JOBS['emulator'], JOBS['emulator_part2']
        self.assertIn('ci_phase.py startup -- ', part1)
        self.assertIn('ci_phase.py ordinary -- env DISTILLPOD_SERIAL=emulator-5554 DISTILLPOD_ORDINARY_PART=1 '
                      'scripts/emulator-tests.sh', part1)
        self.assertIn('ci_phase.py ordinary-part2 -- env DISTILLPOD_SERIAL=emulator-5554 DISTILLPOD_ORDINARY_PART=2 '
                      'scripts/emulator-tests.sh', part2)
        # Cloud acceptance reuses the APK pair that ordinary part 2 installed in the same job.
        self.assertLess(part2.index('ci_phase.py ordinary-part2'), part2.index('ci_phase.py cloud'))
        self.assertIn('ci_phase.py voice -- ', part2)
        for phase in ['startup', 'ordinary ', 'cloud', 'voice']:
            self.assertEqual(1, TEXT.count(f'ci_phase.py {phase}'), phase)

    def test_artifact_names_are_unique(self):
        names = re.findall(r'^          name: (.+)$', TEXT, re.M)
        self.assertEqual(len(names), len(set(names)))
        self.assertIn('emulator-test-results', names)
        self.assertIn('emulator-test-results-part2', names)


class WorkflowSafetyTest(unittest.TestCase):
    def test_actions_are_pinned_to_full_commits_with_versions(self):
        for line in re.findall(r'uses: (.+)$', TEXT, re.M):
            with self.subTest(line=line):
                if line.startswith('./.github/workflows/'):
                    continue
                self.assertRegex(line, r'^[\w.-]+/[\w./-]+@[0-9a-f]{40} # v\d')

    def test_checkouts_never_persist_credentials(self):
        checkouts = TEXT.split('uses: actions/checkout@')[1:]
        self.assertEqual(len(checkouts), len(re.findall(r'^          persist-credentials: false$', TEXT, re.M)))
        for block in checkouts:
            self.assertIn('persist-credentials: false', block.split('- ', 1)[0])

    def test_no_expression_is_interpolated_into_a_run_script(self):
        for line, script in run_lines(TEXT):
            if script:
                self.assertNotIn('${{', line)

    def test_gradle_cache_writers_are_unchanged_and_other_jobs_restore_read_only(self):
        gradle = {job for job, body in JOBS.items() if 'gradle/actions/setup-gradle@' in body}
        self.assertEqual({'android_build', 'voice_build', 'play_build', RELEASE_JOB, 'emulator', 'emulator_part2'}, gradle)
        for job in gradle:
            body = JOBS[job]
            with self.subTest(job=job):
                self.assertIn('      GRADLE_OPTS: -Dorg.gradle.caching=true\n', body)
                read_only = re.search(r'^          cache-read-only: (.+)$', body, re.M).group(1)
                shared = re.search(r'^      GRADLE_BUILD_ACTION_CACHE_KEY_JOB: (\w+)$', body, re.M)
                if job in WRITERS:
                    self.assertEqual(WRITER_EXPRESSION, read_only)
                    self.assertIsNone(shared)
                elif job == RELEASE_JOB:
                    # Runs only for build_release, where this expression is always read-only.
                    self.assertEqual(WRITER_EXPRESSION, read_only)
                    self.assertEqual('android_build', shared.group(1))
                else:
                    self.assertEqual('true', read_only)
                    self.assertIn(shared.group(1), WRITERS)

    def test_fixture_cache_is_keyed_on_the_verified_checksums_and_saved_once(self):
        script = ROOT / 'scripts/prepare-fixtures.sh'
        checksums = re.findall(r'^DISTILLPOD_TINY(?:_EN)?_SHA=([0-9a-f]{64})$', script.read_text(), re.M)
        self.assertEqual(2, len(checksums))
        key = subprocess.run(['bash', str(script), '--cache-key'], capture_output=True, text=True, check=True)
        self.assertEqual('whisper-fixtures-' + '-'.join(checksums), key.stdout.strip())
        # A literal checksum key would look like a credential to the secret scanner.
        self.assertEqual(3, TEXT.count('key: ${{ steps.fixture_key.outputs.key }}'))
        self.assertEqual(2, TEXT.count('run: echo "key=$(scripts/prepare-fixtures.sh --cache-key)" >> "$GITHUB_OUTPUT"'))
        self.assertEqual(1, TEXT.count('uses: actions/cache/save@'))
        self.assertIn('uses: actions/cache/save@', JOBS['emulator'])
        for job in ['emulator', 'emulator_part2']:
            body = JOBS[job]
            self.assertLess(body.index('id: fixture_key'), body.index('uses: actions/cache/restore@'))
            self.assertLess(body.index('uses: actions/cache/restore@'), body.index('scripts/prepare-fixtures.sh /tmp'))

    def test_every_sdk_install_retries_and_the_system_image_precedes_the_emulator(self):
        self.assertNotRegex(TEXT, r'run: sdkmanager')
        for job in ['android_build', 'voice_build', 'play_build', RELEASE_JOB, 'emulator', 'emulator_part2']:
            self.assertIn("run: scripts/sdk-install.sh 'platforms;android-35' 'build-tools;35.0.0' "
                          "'ndk;27.2.12479018' 'cmake;3.22.1'", JOBS[job])
        for job in ['emulator', 'emulator_part2']:
            body = JOBS[job]
            install = body.index("'system-images;android-35;google_apis;x86_64'")
            self.assertLess(install, body.index('uses: ReactiveCircus/android-emulator-runner@'))
            self.assertIn("'build-tools;37.0.0' platform-tools emulator", body)


@unittest.skipUnless(os.name == 'posix' and shutil.which('bash'), 'Unix shell fixture')
class SdkInstallRetryTest(unittest.TestCase):
    def run_install(self, failures, *packages):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        (root / 'bin').mkdir()
        sdk = root / 'sdk'
        (sdk / 'keep').mkdir(parents=True)
        # Fails the first `failures` calls, leaving a partial download behind each time.
        for tool, body in [('sdkmanager', f'''#!/bin/sh
[ -e "$ANDROID_HOME/.temp" ] && echo partial >> "{root}/calls"
echo "$*" >> "{root}/calls"
[ "$(grep -vc partial "{root}/calls")" -gt {failures} ] && exit 0
mkdir -p "$ANDROID_HOME/.temp" "$ANDROID_HOME/.downloadIntermediates"
exit 1
'''), ('sleep', f'#!/bin/sh\necho "$1" >> "{root}/sleeps"\n')]:
            (root / 'bin' / tool).write_text(body)
            (root / 'bin' / tool).chmod(0o700)
        env = dict(os.environ, ANDROID_HOME=str(sdk), PATH=f"{root / 'bin'}:{os.environ['PATH']}")
        result = subprocess.run(['bash', str(ROOT / 'scripts/sdk-install.sh'), *packages],
                                env=env, capture_output=True, text=True, timeout=15)
        read = lambda name: (root / name).read_text().splitlines() if (root / name).exists() else []
        return result, read('calls'), read('sleeps'), sdk

    def test_corrupt_downloads_are_discarded_and_retried_a_bounded_number_of_times(self):
        result, calls, sleeps, sdk = self.run_install(2, 'ndk;27.2.12479018', 'cmake;3.22.1')
        self.assertEqual(0, result.returncode, result.stderr)
        # No retry sees the previous attempt's partial download.
        self.assertEqual(['--install ndk;27.2.12479018 cmake;3.22.1'] * 3, calls)
        self.assertEqual(['15', '30'], sleeps)
        self.assertTrue((sdk / 'keep').is_dir())

    def test_persistent_failure_stops_after_three_attempts(self):
        result, calls, sleeps, _ = self.run_install(5, 'emulator')
        self.assertEqual(1, result.returncode)
        self.assertEqual(['--install emulator'] * 3, calls)
        self.assertEqual(['15', '30'], sleeps)
        self.assertIn('failed after 3 attempts', result.stderr)

    def test_first_success_does_not_retry_and_packages_are_required(self):
        result, calls, sleeps, _ = self.run_install(0, 'platform-tools')
        self.assertEqual(0, result.returncode)
        self.assertEqual(['--install platform-tools'], calls)
        self.assertEqual([], sleeps)
        result, calls, _, _ = self.run_install(0)
        self.assertEqual(2, result.returncode)
        self.assertEqual([], calls)


if __name__ == '__main__':
    unittest.main()
