"""The distribution artifact check finds every switched feature and cannot pass vacuously (#319)."""
import contextlib
import io
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

import verify_distribution_apk as check

ROOT = Path(__file__).resolve().parents[1]


def dex(strings):
    """A minimal dex file holding only a string table, which is all the check reads."""
    header = bytearray(0x70)
    header[:8] = b'dex\n035\0'
    data = bytearray()
    offsets = []
    base = 0x70 + 4 * len(strings)
    for text in strings:
        offsets.append(base + len(data))
        encoded = text.encode('utf-8')
        size = len(text)
        while True:
            byte = size & 0x7F
            size >>= 7
            data.append(byte | (0x80 if size else 0))
            if not size:
                break
        data += encoded + b'\0'
    struct.pack_into('<II', header, 0x38, len(strings), 0x70)
    return bytes(header) + b''.join(struct.pack('<I', o) for o in offsets) + bytes(data)


def apk(directory, features, extra_strings=(), manifest='{"components": []}', extra_entries=()):
    """An APK carrying exactly the markers of the enabled features."""
    path = Path(directory) / 'app.apk'
    strings = ['Ldev/distillpod/android/MainActivity;', *extra_strings]
    notices = ['- **OkHttp**: upstream notices.']
    with zipfile.ZipFile(path, 'w') as archive:
        archive.writestr('AndroidManifest.xml', b'')
        archive.writestr('lib/arm64-v8a/libdistillpod_whisper.so', b'')
        for entry in extra_entries:
            archive.writestr(entry, b'')
        for feature in features:
            for entry in check.FEATURES[feature]['entries']:
                archive.writestr(entry.replace('*', 'arm64-v8a'), b'')
            strings += check.FEATURES[feature]['strings']
            notices += [f'- **{word}**: notice' for word in check.FEATURES[feature]['notices']]
        half = len(strings) // 2
        archive.writestr('classes.dex', dex(strings[:half]))
        archive.writestr('classes2.dex', dex(strings[half:]))
        archive.writestr('assets/licenses/THIRD_PARTY_NOTICES.md', '\n'.join(notices))
        archive.writestr('assets/licenses/source-manifest.json', manifest)
    return path


def run(*args):
    errors = io.StringIO()
    with contextlib.redirect_stderr(errors), contextlib.redirect_stdout(io.StringIO()):
        status = check.main([str(a) for a in args])
    return status, errors.getvalue()


class DistributionApkTest(unittest.TestCase):
    def test_reads_every_string_of_a_dex_table(self):
        strings = ['Lcom/example/A;', 'Check for updates', 'é unicode', 'x' * 300]
        self.assertEqual(strings, check.dex_strings(dex(strings)))

    def test_each_distribution_passes_with_its_own_features(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(0, run(apk(directory, check.FEATURES), '--distribution', 'github')[0])
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(0, run(apk(directory, []), '--distribution', 'play')[0])

    def test_play_fails_on_any_single_youtube_or_update_marker(self):
        for feature, markers in check.FEATURES.items():
            for marker in markers['strings']:
                with self.subTest(marker=marker), tempfile.TemporaryDirectory() as directory:
                    status, errors = run(apk(directory, [], [marker]), '--distribution', 'play')
                    self.assertEqual(1, status)
                    self.assertIn(marker, errors)
        with tempfile.TemporaryDirectory() as directory:
            status, errors = run(apk(directory, [], extra_entries=['res/raw/ytdlp.bin']), '--distribution', 'play')
            self.assertEqual(1, status)
            self.assertIn('res/raw/ytdlp.bin', errors)

    def test_play_fails_on_any_class_of_the_extractor_library(self):
        with tempfile.TemporaryDirectory() as directory:
            status, errors = run(apk(directory, [], ['Lcom/yausername/ffmpeg/FFmpeg;']), '--distribution', 'play')
            self.assertEqual(1, status)
            self.assertIn('Lcom/yausername/', errors)

    def test_play_fails_when_the_notices_name_a_youtube_component(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = '{"components": [{"repository": "yt-dlp/yt-dlp"}]}'
            status, errors = run(apk(directory, [], manifest=manifest), '--distribution', 'play')
            self.assertEqual(1, status)
            self.assertIn('licenses name yt-dlp', errors)

    def test_github_fails_when_a_feature_marker_disappears(self):
        with tempfile.TemporaryDirectory() as directory:
            status, errors = run(apk(directory, []), '--distribution', 'github')
            self.assertEqual(1, status)
            self.assertIn('youtube: missing', errors)

    def test_a_single_switch_can_be_overridden(self):
        with tempfile.TemporaryDirectory() as directory:
            path = apk(directory, ['youtube'])
            self.assertEqual(0, run(path, '--distribution', 'play', '--switch', 'youtube=true')[0])
            self.assertEqual(1, run(path, '--distribution', 'play', '--switch', 'nonsense=true')[0])

    def test_presets_match_the_gradle_switches(self):
        build = (ROOT / 'app/build.gradle.kts').read_text()
        names = build.split('val distributionSwitchNames = listOf(', 1)[1].split(')', 1)[0]
        self.assertEqual(set(check.FEATURES), {name.strip().strip('"') for name in names.split(',')})
        play = build.split('"play" to mapOf(', 1)[1].split(')', 1)[0]
        self.assertEqual({f'"{name}" to false' for name in check.FEATURES}, {p.strip() for p in play.split(',')})


if __name__ == '__main__':
    unittest.main()
