"""The Play bundle check refuses every identity, content, alignment and signing defect (#322).

Android and Java tools are replaced by small fakes, so these tests need neither an SDK nor a JDK.
"""
import contextlib
import hashlib
import io
import json
import os
import shutil
import struct
import tempfile
import unittest
import unittest.mock
import zipfile
from pathlib import Path

import verify_distribution_apk as distribution
import verify_play_bundle as check
from release_metadata import metadata
from test_verify_distribution_apk import apk

ROOT = Path(__file__).resolve().parents[1]
FINGERPRINT = 'ab' * 32
COLONS = ':'.join(['AB'] * 32)


def elf(align, bits=64, loads=2):
    """A minimal little-endian ELF shared object with `loads` PT_LOAD program headers."""
    if bits == 64:
        header = bytearray(64)
        header[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<QHH', header, 0x20, 64, 0, 0)
        struct.pack_into('<HH', header, 0x36, 56, loads + 1)
        table = b''.join(struct.pack('<IIQQQQQQ', 1, 5, 0, 0, 0, 0, 0, align) for _ in range(loads))
        table += struct.pack('<IIQQQQQQ', 6, 4, 0, 0, 0, 0, 0, 8)  # PT_PHDR: not a LOAD segment
    else:
        header = bytearray(52)
        header[:6] = b'\x7fELF\x01\x01'
        struct.pack_into('<I', header, 0x1C, 52)
        struct.pack_into('<HH', header, 0x2A, 32, loads)
        table = b''.join(struct.pack('<IIIIIIII', 1, 0, 0, 0, 0, 0, 5, align) for _ in range(loads))
    return bytes(header) + table


# jarsigner -strict output for a bundle signed by a self-signed upload key (JDK 17), exit code 4.
SELF_SIGNED = """
jar verified, with signer errors.

Error: 
This jar contains entries whose certificate chain is invalid. Reason: PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
This jar contains entries whose signer certificate is self-signed.

Warning: 
This jar contains signatures that do not include a timestamp. Without a timestamp, users may not be able to validate this jar after any of the signer certificates expire (as early as 2054-04-14).

Re-run with the -verbose and -certs options for more details.
"""
UNSIGNED_ENTRY = SELF_SIGNED.replace("Error: \n", "Error: \nThis jar contains unsigned entries which have not been integrity-checked. \n")
EXPIRED = SELF_SIGNED.replace("Error: \n", "Error: \nThis jar contains entries whose signer certificate has expired. \n")


def printcert(*fingerprints, bits=4096):
    return ''.join(f'Signer #{index}:\n\nSignature:\n\nOwner: CN=DistillPod upload\n'
                   f'Certificate fingerprints:\n\t SHA1: 00:11\n\t SHA256: {fingerprint}\n'
                   f'Signature algorithm name: SHA256withRSA\nSubject Public Key Algorithm: {bits}-bit RSA key\n\n'
                   for index, fingerprint in enumerate(fingerprints, 1))


def badging(target=36, code=None, debuggable=False):
    expected = metadata()
    text = (f"package: name='{expected['applicationId']}' versionCode='{code or expected['versionCode']}' "
            f"versionName='{expected['versionName']}'\nsdkVersion:'29'\ntargetSdkVersion:'{target}'\n")
    return text + ('application-debuggable\n' if debuggable else '')


def bundle(directory, signed=False, licenses=True):
    path = Path(directory) / 'app.aab'
    with zipfile.ZipFile(path, 'w') as archive:
        archive.writestr('base/manifest/AndroidManifest.xml', b'')
        archive.writestr('base/dex/classes.dex', b'dex')
        archive.writestr('BundleConfig.pb', b'')
        if licenses:
            archive.writestr('base/assets/licenses/THIRD_PARTY_NOTICES.md', '# Notices\n')
            archive.writestr('base/assets/licenses/source-manifest.json', '{"components": []}\n')
        if signed:
            archive.writestr('META-INF/MANIFEST.MF', b'Manifest-Version: 1.0\n')
            archive.writestr('META-INF/UPLOAD.SF', b'')
            archive.writestr('META-INF/UPLOAD.RSA', b'')
    return path


def universal(directory, features=(), libraries=None):
    """An .apks set holding a universal APK with the given switched features and native libraries."""
    directory = Path(directory)
    (directory / 'apk').mkdir(parents=True)
    source = apk(directory / 'apk', features)
    libraries = {'lib/arm64-v8a/libdistillpod_whisper.so': elf(16384),
                 'lib/x86_64/libdistillpod_whisper.so': elf(16384)} if libraries is None else libraries
    universal_apk = directory / 'universal.apk'
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(universal_apk, 'w') as archive:
        for item in original.infolist():
            if not item.filename.startswith('lib/'):
                archive.writestr(item, original.read(item))
        for name, data in libraries.items():
            archive.writestr(name, data)
    apks = directory / 'universal.apks'
    with zipfile.ZipFile(apks, 'w') as archive:
        archive.write(universal_apk, 'universal.apk')
        archive.writestr('toc.pb', b'')
    return apks


class Tools:
    """Fake java, jarsigner, keytool, aapt and zipalign that record their calls."""

    def __init__(self, directory):
        self.root = Path(directory) / 'tools'
        self.java_home = self.root / 'jdk'
        self.build_tools = self.root / 'build-tools'
        (self.java_home / 'bin').mkdir(parents=True)
        self.build_tools.mkdir()
        self.calls = self.root / 'calls'
        self.jar = self.root / 'bundletool.jar'
        self.jar.write_bytes(b'pinned bundletool')
        self.env = {'FAKE_CALLS': str(self.calls), 'FAKE_BADGING': badging(), 'FAKE_PRINTCERT': printcert(COLONS),
                    'FAKE_JARSIGNER_EXIT': '4', 'FAKE_JARSIGNER_OUT': SELF_SIGNED, 'FAKE_ZIPALIGN_EXIT': '0', 'FAKE_APKS': ''}
        record = 'echo "$(basename "$0") $*" >> "$FAKE_CALLS"\n'
        self.write(self.java_home / 'bin/java', record + 'for a in "$@"; do case "$a" in --output=*) cp "$FAKE_APKS" "${a#--output=}";; esac; done\n')
        self.write(self.java_home / 'bin/jarsigner', record + 'printf "%s" "$FAKE_JARSIGNER_OUT"\nexit "$FAKE_JARSIGNER_EXIT"\n')
        self.write(self.java_home / 'bin/keytool', record + 'printf "%s" "$FAKE_PRINTCERT"\n')
        self.write(self.build_tools / 'aapt', record + 'printf "%s" "$FAKE_BADGING"\n')
        self.write(self.build_tools / 'zipalign', record + 'exit "$FAKE_ZIPALIGN_EXIT"\n')

    @staticmethod
    def write(path, body):
        path.write_text('#!/bin/sh\n' + body)
        path.chmod(0o700)

    def log(self):
        return self.calls.read_text().splitlines() if self.calls.exists() else []


@unittest.skipUnless(os.name == 'posix' and shutil.which('sh'), 'Unix shell fakes')
class VerifyTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.dir = Path(directory.name)
        self.tools = Tools(self.dir)
        self.tools.env['FAKE_APKS'] = str(universal(self.dir / 'set'))
        patch = unittest.mock.patch.dict(os.environ, {**self.tools.env, 'JAVA_HOME': str(self.tools.java_home)})
        patch.start()
        self.addCleanup(patch.stop)
        pin = unittest.mock.patch.object(check, 'BUNDLETOOL_SHA256', hashlib.sha256(b'pinned bundletool').hexdigest())
        pin.start()
        self.addCleanup(pin.stop)

    def run_check(self, *args, signed=True, **bundle_options):
        path = bundle(self.dir, signed=signed, **bundle_options)
        arguments = [str(path), '--tag', metadata()['tag'], '--bundletool', str(self.tools.jar),
                     '--build-tools', str(self.tools.build_tools), *map(str, args)]
        if not any(a in args for a in ('--unsigned', '--certificate-sha256')):
            arguments += ['--certificate-sha256', FINGERPRINT]
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            status = check.main(arguments)
        return status, output.getvalue(), errors.getvalue()

    def test_a_correct_signed_bundle_passes_and_writes_its_record_and_licenses(self):
        out = self.dir / 'out'
        env = {'GITHUB_SHA': 'c' * 40, 'GITHUB_SERVER_URL': 'https://github.com',
               'GITHUB_REPOSITORY': 'owner/repo', 'GITHUB_RUN_ID': '42', 'GITHUB_RUN_ATTEMPT': '2'}
        with unittest.mock.patch.dict(os.environ, env):
            status, _, errors = self.run_check('--licenses-out', out, '--build-info', out / 'play-build-info.json')
        self.assertEqual(0, status, errors)
        record = json.loads((out / 'play-build-info.json').read_text())
        expected = metadata()
        for key in ['applicationId', 'versionName', 'versionCode', 'tag']:
            self.assertEqual(expected[key], record[key])
        self.assertEqual(36, record['targetSdk'])
        self.assertEqual('play', record['distribution'])
        self.assertEqual(distribution.PRESETS['play'], record['switches'])
        self.assertEqual(set(distribution.FEATURES), set(record['switches']))
        self.assertFalse(record['switches']['youtube'] or record['switches']['updateCheck'])
        self.assertEqual(check.BUILD_PROPERTIES, record['gradleProperties'])
        self.assertEqual(FINGERPRINT, record['uploadCertificateSha256'])
        self.assertNotIn('certificateSha256', record)  # the personal-alpha fingerprint does not sign this
        self.assertEqual('c' * 40, record['commit'])
        self.assertEqual('https://github.com/owner/repo/actions/runs/42/attempts/2', record['workflowRun'])
        self.assertEqual(check.BUNDLETOOL_VERSION, record['bundletoolVersion'])
        self.assertEqual('# Notices\n', (out / 'THIRD_PARTY_NOTICES.md').read_text())
        self.assertEqual('{"components": []}\n', (out / 'source-manifest.json').read_text())
        calls = self.tools.log()
        self.assertTrue(any(' validate --bundle=' in call for call in calls), calls)
        self.assertTrue(any(' build-apks ' in call and '--mode=universal' in call for call in calls), calls)
        english = '-J-Duser.language=en -J-Duser.country=US'
        self.assertTrue(any(call.startswith(f'jarsigner {english} -verify -strict ') for call in calls), calls)
        self.assertTrue(any(call.startswith(f'keytool {english} -printcert -jarfile ') for call in calls), calls)
        self.assertTrue(any(call.startswith('zipalign -c -P 16 4 ') for call in calls), calls)

    def test_identity_defects_fail(self):
        for name, value in [('stale version code', badging(code=1)), ('target 35', badging(target=35)),
                            ('debuggable', badging(debuggable=True)),
                            ('other app', badging().replace('dev.distillpod.android', 'dev.other'))]:
            with self.subTest(name), unittest.mock.patch.dict(os.environ, {'FAKE_BADGING': value}):
                self.assertEqual(1, self.run_check()[0])

    def test_youtube_or_update_content_fails(self):
        for feature in ['youtube', 'updateCheck']:
            with self.subTest(feature):
                (self.dir / 'set').rename(self.dir / f'old-{feature}')
                path = universal(self.dir / 'set', [feature])
                with unittest.mock.patch.dict(os.environ, {'FAKE_APKS': str(path)}):
                    status, _, errors = self.run_check()
                self.assertEqual(1, status)
                self.assertIn(f'{feature}: contains', errors)

    def test_native_libraries_must_be_16_kb_aligned(self):
        path = universal(self.dir / 'set4k', libraries={'lib/arm64-v8a/libdistillpod_whisper.so': elf(4096)})
        with unittest.mock.patch.dict(os.environ, {'FAKE_APKS': str(path)}):
            status, _, errors = self.run_check()
        self.assertEqual(1, status)
        self.assertIn('lib/arm64-v8a/libdistillpod_whisper.so', errors)
        with unittest.mock.patch.dict(os.environ, {'FAKE_ZIPALIGN_EXIT': '1'}):
            self.assertEqual(1, self.run_check()[0])

    def test_signature_must_verify_and_match_the_upload_certificate(self):
        with unittest.mock.patch.dict(os.environ, {'FAKE_JARSIGNER_EXIT': '0', 'FAKE_JARSIGNER_OUT': 'jar verified.\n'}):
            self.assertEqual(0, self.run_check()[0])
        digest_error = 'jarsigner: java.lang.SecurityException: SHA-256 digest error for base/dex/classes.dex\n'
        for code, output in [('1', digest_error), ('20', UNSIGNED_ENTRY), ('4', UNSIGNED_ENTRY), ('4', EXPIRED),
                             ('36', SELF_SIGNED), ('0', 'jar is unsigned.\n')]:
            with self.subTest(code=code, output=output[:60]), unittest.mock.patch.dict(
                    os.environ, {'FAKE_JARSIGNER_EXIT': code, 'FAKE_JARSIGNER_OUT': output}):
                self.assertEqual(1, self.run_check()[0])
        for text in [printcert('CD:' * 31 + 'CD'), printcert(COLONS, COLONS), '', printcert(COLONS, bits=1024)]:
            with self.subTest(text=text), unittest.mock.patch.dict(os.environ, {'FAKE_PRINTCERT': text}):
                self.assertEqual(1, self.run_check()[0])
        self.assertEqual(1, self.run_check(signed=False)[0])

    def test_unsigned_mode_refuses_a_signed_bundle_and_never_runs_jarsigner(self):
        self.assertEqual(0, self.run_check('--unsigned', signed=False)[0])
        self.assertFalse(any(call.startswith(('jarsigner', 'keytool')) for call in self.tools.log()))
        status, _, errors = self.run_check('--unsigned', signed=True)
        self.assertEqual(1, status)
        self.assertIn('META-INF/UPLOAD.RSA', errors)

    def test_signature_only_mode_skips_content_checks(self):
        status, _, errors = self.run_check('--signature-only')
        self.assertEqual(0, status, errors)
        self.assertFalse(any(call.startswith(('java', 'aapt', 'zipalign')) for call in self.tools.log()))

    def test_tag_must_match_the_source(self):
        path = bundle(self.dir, signed=True)
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, check.main([str(path), '--tag', 'v0.0.1', '--certificate-sha256', FINGERPRINT,
                                            '--signature-only']))

    def test_an_unpinned_bundletool_is_never_run(self):
        self.tools.jar.write_bytes(b'something else')
        status, _, errors = self.run_check()
        self.assertEqual(1, status)
        self.assertIn('SHA-256', errors)
        self.assertFalse(any(call.startswith('java') for call in self.tools.log()))

    def test_missing_licenses_fail(self):
        self.assertEqual(1, self.run_check('--licenses-out', self.dir / 'out', licenses=False)[0])


class PartsTest(unittest.TestCase):
    def test_fingerprints_are_normalized_and_validated(self):
        self.assertEqual(FINGERPRINT, check.normalize_fingerprint(COLONS))
        self.assertEqual(FINGERPRINT, check.normalize_fingerprint(' ' + FINGERPRINT.upper() + '\n'))
        for bad in ['', 'ab' * 31, 'zz' * 32, 'ab' * 33]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                check.normalize_fingerprint(bad)

    def test_elf_load_alignment_is_read_for_both_classes(self):
        self.assertEqual([16384, 16384], check.load_alignments(elf(16384)))
        self.assertEqual([4096], check.load_alignments(elf(4096, bits=32, loads=1)))
        for bad in [b'not an elf', elf(16384)[:60], b'\x7fELF\x02\x02' + bytes(58)]:
            with self.subTest(bad=bad[:8]), self.assertRaises(ValueError):
                check.load_alignments(bad)

    def test_an_apk_without_native_libraries_cannot_pass_vacuously(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'none.apk'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('classes.dex', b'')
            self.assertTrue(check.native_alignment_problems(path))

    def test_the_target_sdk_matches_the_gradle_build(self):
        build = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn(f'        targetSdk = {check.TARGET_SDK}\n', build)

    def test_the_recorded_build_properties_name_the_play_preset(self):
        self.assertEqual(['-PdistillpodDistribution=play', '-PdistillpodVoiceEnabled=true'], check.BUILD_PROPERTIES)


if __name__ == '__main__':
    unittest.main()
