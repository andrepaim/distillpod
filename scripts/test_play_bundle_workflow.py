"""Play bundle workflow contract (#322): tagged source, full validation, isolated signing, no publication.

Text-level checks of .github/workflows/play-bundle.yml; no third-party YAML parser is required.
"""
import re
import unittest
from pathlib import Path

import verify_play_bundle
from test_ci_workflow import run_lines

ROOT = Path(__file__).resolve().parents[1]
TEXT = (ROOT / '.github/workflows/play-bundle.yml').read_text()
JOBS = dict(re.findall(r'^  ([a-z0-9_]+):\n((?:(?:    .*)?\n)+)', TEXT.split('\njobs:\n', 1)[1], re.M))
SIGNING_SECRETS = ['PLAY_UPLOAD_KEYSTORE_BASE64', 'PLAY_UPLOAD_KEYSTORE_PASSWORD',
                   'PLAY_UPLOAD_KEY_ALIAS', 'PLAY_UPLOAD_KEY_PASSWORD']
SIGNING_ENV = ['KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD']


def steps(job):
    """Each step of a job, keyed by its name (or its `uses:` action)."""
    found = {}
    for block in re.split(r'^      - ', JOBS[job], flags=re.M)[1:]:
        title = re.match(r'(?:name|uses): (.+)', block).group(1)
        found[title] = block
    return found


def step(job, prefix):
    matches = [body for title, body in steps(job).items() if title.startswith(prefix)]
    assert len(matches) == 1, (job, prefix, list(steps(job)))
    return matches[0]


class TriggerTest(unittest.TestCase):
    def test_runs_only_by_hand_with_a_required_tag(self):
        trigger = TEXT.split('\non:\n', 1)[1].split('\npermissions:', 1)[0]
        self.assertEqual(['workflow_dispatch'], re.findall(r'^  ([a-z_]+):', trigger, re.M))
        self.assertIn('      tag:\n', trigger)
        self.assertIn('        required: true\n', trigger)
        self.assertIn('        type: string\n', trigger)

    def test_never_publishes_or_writes_to_the_repository(self):
        self.assertNotIn('gh release', TEXT)
        self.assertNotRegex(TEXT, r': write\b')
        self.assertIn('permissions:\n  contents: read\n  actions: read\n', TEXT)
        self.assertNotIn('ALPHA_', TEXT)
        self.assertNotIn('alpha-release', TEXT)
        self.assertNotIn('apksigner', TEXT)

    def test_source_is_the_dispatched_tag_on_main_with_the_matching_version(self):
        source = step('source', 'Require the dispatched tag')
        self.assertIn('TAG: ${{ inputs.tag }}', source)
        self.assertIn('[ "$GITHUB_REF_TYPE" != tag ] || [ "$GITHUB_REF_NAME" != "$TAG" ]', source)
        self.assertIn('test "$(git rev-parse "refs/tags/$TAG^{commit}")" = "$GITHUB_SHA"', source)
        self.assertIn('git merge-base --is-ancestor "$GITHUB_SHA" origin/main', source)
        self.assertIn('python3 scripts/release_metadata.py --tag "$TAG"', source)
        self.assertIn('fetch-depth: 0', JOBS['source'])

    def test_every_job_has_a_timeout_and_the_graph_signs_only_after_validation(self):
        for job, body in JOBS.items():
            if job != 'validation':
                with self.subTest(job=job):
                    self.assertRegex(body, r'\n    timeout-minutes: \d+\n')
        self.assertIn('    needs: [source, upload_key]\n', JOBS['validation'])
        self.assertIn('    needs: [source, validation]\n', JOBS['bundle'])
        self.assertIn('    needs: [source, bundle]\n', JOBS['sign'])
        self.assertNotIn('needs', JOBS['source'] + JOBS['upload_key'])


class ValidationTest(unittest.TestCase):
    def test_reuses_full_ci_including_the_play_distribution_job(self):
        validation = JOBS['validation']
        self.assertIn('    uses: ./.github/workflows/ci.yml\n', validation)
        # The GitHub APK candidate is not built; play_build and every other full job run.
        self.assertIn('      build_release: false\n', validation)
        self.assertNotIn('secrets', validation)

    def test_bundle_build_uses_the_published_configuration_and_release_lint(self):
        build = step('bundle', 'Build unsigned Play bundle')
        command = ('./gradlew --no-daemon --stacktrace --no-build-cache :app:lintRelease :app:bundleRelease '
                   + ' '.join(verify_play_bundle.BUILD_PROPERTIES) + ' "-PdistillpodFirebaseConfig=$config_file"')
        self.assertIn(command, build)
        for line in ['FIREBASE_CONFIG_JSON: ${{ secrets.FIREBASE_CONFIG_JSON }}',
                     'SENTRY_DSN: ${{ secrets.SENTRY_DSN }}',
                     ': "${FIREBASE_CONFIG_JSON:?Missing Firebase client configuration for distribution}"',
                     ': "${SENTRY_DSN:?Missing Sentry DSN for cloud diagnostics distribution}"',
                     'trap \'rm -f "$config_file"\' EXIT', 'umask 077']:
            self.assertIn(line, build)
        self.assertLess(build.index('umask 077'), build.index('printf'))
        self.assertNotIn('fetch_youtube_extractor', JOBS['bundle'])
        self.assertIn("run: scripts/sdk-install.sh 'platforms;android-36' 'build-tools;35.0.0' "
                      "'ndk;27.2.12479018' 'cmake;3.22.1'", JOBS['bundle'])
        self.assertIn('      - name: Verify validation source\n'
                      '        run: python3 scripts/ci_validation_proof.py check-source\n', JOBS['bundle'])

    def test_unsigned_candidate_is_verified_before_it_reaches_the_signing_job(self):
        bundle = JOBS['bundle']
        self.assertLess(bundle.index('Build unsigned Play bundle'), bundle.index('--unsigned'))
        verify = step('bundle', 'Verify unsigned Play bundle')
        self.assertIn('python3 scripts/verify_play_bundle.py app/build/outputs/bundle/release/app-release.aab '
                      '--tag "$GITHUB_REF_NAME" --unsigned', verify)
        upload = bundle.split('name: distillpod-unsigned-play-bundle', 1)[1]
        self.assertLess(bundle.index('--unsigned'), bundle.index('name: distillpod-unsigned-play-bundle'))
        self.assertIn('path: app/build/outputs/bundle/release/app-release.aab', upload)
        self.assertIn('if-no-files-found: error', upload)
        self.assertIn('retention-days: 1\n', upload)

    def test_gradle_cache_is_restored_read_only(self):
        self.assertIn('      GRADLE_BUILD_ACTION_CACHE_KEY_JOB: android_build\n', JOBS['bundle'])
        self.assertIn('          cache-read-only: true\n', JOBS['bundle'])

    def test_bundletool_is_pinned_by_version_and_checksum(self):
        fetches = [body for job in JOBS for title, body in steps(job).items() if title == 'Fetch pinned bundletool']
        self.assertEqual(2, len(fetches))
        for fetch in fetches:
            self.assertIn(verify_play_bundle.BUNDLETOOL_URL, fetch)
            self.assertIn(f'echo "{verify_play_bundle.BUNDLETOOL_SHA256}  $RUNNER_TEMP/bundletool.jar" | sha256sum --check',
                          fetch)


class SigningTest(unittest.TestCase):
    def test_upload_key_secrets_live_in_the_play_upload_environment_only(self):
        self.assertEqual({'upload_key', 'sign'}, {job for job, body in JOBS.items() if 'environment:' in body})
        for job in ['upload_key', 'sign']:
            self.assertIn('    environment: play-upload\n', JOBS[job])
        # Secrets reach exactly three steps: the preflight, the build (Firebase, Sentry) and the signing step.
        users = {(job, title) for job in JOBS for title, body in steps(job).items() if '${{ secrets.' in body}
        self.assertEqual({('upload_key', 'Require the Play upload key'), ('bundle', 'Build unsigned Play bundle'),
                          ('sign', 'Sign with the upload key')}, users)
        self.assertEqual(10, TEXT.count('${{ secrets.'))
        for name in SIGNING_SECRETS:
            self.assertEqual(2, TEXT.count(f'${{{{ secrets.{name} }}}}'), name)
            self.assertNotIn(name, JOBS['bundle'])

    def test_missing_upload_key_fails_before_validation_or_building(self):
        preflight = step('upload_key', 'Require the Play upload key')
        for name in SIGNING_SECRETS + ['PLAY_UPLOAD_CERT_SHA256']:
            self.assertIn(f'missing+=({name}', preflight)
        self.assertIn('exit 1', preflight)
        self.assertIn('docs/releases.md#google-play-bundle', preflight)
        self.assertNotIn('uses: actions/checkout', JOBS['upload_key'])

    def test_keystore_is_decoded_privately_and_removed_by_the_signing_step(self):
        sign = step('sign', 'Sign with the upload key')
        for variable, secret in zip(SIGNING_ENV, SIGNING_SECRETS):
            self.assertIn(f'{variable}: ${{{{ secrets.{secret} }}}}', sign)
            self.assertIn(f': "${{{variable}:?', sign)
        decode = 'printf \'%s\' "$KEYSTORE_BASE64" | base64 --decode > "$signing_file"'
        self.assertIn('signing_file="$RUNNER_TEMP/distillpod-play-upload.keystore"', sign)
        self.assertLess(sign.index('trap \'rm -f "$signing_file"\' EXIT'), sign.index(decode))
        self.assertLess(sign.index('umask 077'), sign.index(decode))
        self.assertIn('jarsigner -keystore "$signing_file" -storepass:env KEYSTORE_PASSWORD -keypass:env KEY_PASSWORD '
                      '-sigalg SHA256withRSA -digestalg SHA-256 -signedjar "$bundle" unsigned/app-release.aab "$KEY_ALIAS"',
                      sign)
        self.assertEqual(1, TEXT.count('base64 --decode'))

    def test_no_secret_is_echoed_or_traced(self):
        self.assertNotRegex(TEXT, r'set -[a-z]*x')
        allowed = [r': "\$\{(\w+):\?[^"$]*\}"', r'\[ -n "\$\{(\w+):-\}" \] \|\| missing\+=\(\w+\)',
                   r'printf \'%s\' "\$(KEYSTORE_BASE64)" \| base64 --decode > "\$signing_file"',
                   r'-(?:store|key)pass:env (\w+)', r'-signedjar "\$bundle" unsigned/app-release\.aab "\$KEY_ALIAS"$',
                   r'printf \'%s\' "\$(FIREBASE_CONFIG_JSON)" > "\$config_file"']
        secret_names = SIGNING_ENV + ['FIREBASE_CONFIG_JSON', 'SENTRY_DSN']
        for line, script in run_lines(TEXT):
            if not script:
                continue
            remainder = line
            for pattern in allowed:
                remainder = re.sub(pattern, '', remainder)
            for name in secret_names:
                with self.subTest(line=line.strip(), name=name):
                    self.assertNotRegex(remainder, rf'\${{?{name}\b')

    def test_the_signed_bundle_is_fully_verified_before_it_is_offered(self):
        sign = JOBS['sign']
        verify = step('sign', 'Verify and package the signed bundle')
        self.assertNotIn('${{ secrets.', verify)
        self.assertIn('UPLOAD_CERT_SHA256: ${{ vars.PLAY_UPLOAD_CERT_SHA256 }}', verify)
        self.assertIn('--certificate-sha256 "$UPLOAD_CERT_SHA256"', verify)
        self.assertIn('--licenses-out dist --build-info dist/play-build-info.json', verify)
        self.assertNotIn('--signature-only', verify)
        self.assertIn('sha256sum "$name" play-build-info.json THIRD_PARTY_NOTICES.md source-manifest.json > SHA256SUMS',
                      verify)
        self.assertLess(sign.index('Sign with the upload key'), sign.index('Verify and package the signed bundle'))
        upload = sign.split('uses: actions/upload-artifact@', 1)[1]
        self.assertLess(sign.index('Verify and package the signed bundle'), sign.index('uses: actions/upload-artifact@'))
        self.assertEqual(1, sign.count('uses: actions/upload-artifact@'))
        self.assertIn('name: distillpod-play-bundle-${{ inputs.tag }}', upload)
        self.assertIn('path: dist/\n', upload)
        self.assertIn('if-no-files-found: error', upload)
        self.assertIn('retention-days: 7\n', upload)


class WorkflowSafetyTest(unittest.TestCase):
    def test_actions_are_pinned_to_full_commits_with_versions(self):
        for line in re.findall(r'uses: (.+)$', TEXT, re.M):
            with self.subTest(line=line):
                if line.startswith('./.github/workflows/'):
                    continue
                self.assertRegex(line, r'^[\w.-]+/[\w./-]+@[0-9a-f]{40} # v\d')

    def test_actions_match_the_versions_ci_already_uses(self):
        ci = (ROOT / '.github/workflows/ci.yml').read_text() + (ROOT / '.github/workflows/release.yml').read_text()
        for line in set(re.findall(r'uses: ([\w.-]+/[\w./-]+@.+)$', TEXT, re.M)):
            self.assertIn(line, ci)

    def test_checkouts_never_persist_credentials(self):
        checkouts = TEXT.split('uses: actions/checkout@')[1:]
        self.assertEqual(3, len(checkouts))
        self.assertEqual(len(checkouts), len(re.findall(r'^          persist-credentials: false$', TEXT, re.M)))
        for block in checkouts:
            self.assertIn('persist-credentials: false', block.split('- ', 1)[0])

    def test_no_expression_is_interpolated_into_a_run_script(self):
        for line, script in run_lines(TEXT):
            if script:
                self.assertNotIn('${{', line)

    def test_release_documentation_describes_this_workflow(self):
        releases = (ROOT / 'docs/releases.md').read_text()
        section = releases.split('\n## Google Play bundle\n', 1)[1].split('\n## ', 1)[0]
        for text in ['play-bundle.yml', 'play-upload', 'PLAY_UPLOAD_CERT_SHA256', *SIGNING_SECRETS,
                     'keytool -genkeypair', verify_play_bundle.BUNDLETOOL_SHA256, 'verify_play_bundle.py']:
            self.assertIn(text, section)
        listing = (ROOT / 'store/play/listing.md').read_text()
        self.assertIn('../../docs/releases.md#google-play-bundle', listing)


if __name__ == '__main__':
    unittest.main()
