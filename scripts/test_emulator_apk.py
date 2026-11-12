import tempfile
import unittest
import zipfile
from pathlib import Path

from emulator_apk import verify_abis


class EmulatorApkTest(unittest.TestCase):
    def apk(self, directory, name, files):
        target = Path(directory) / name
        with zipfile.ZipFile(target, 'w') as archive:
            for file in files:
                archive.writestr(file, b'fixture')
        return target

    def test_selected_native_pair_and_library_free_test_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            app = self.apk(directory, 'main.apk', ['lib/x86_64/libdistillpod_whisper.so',
                                                    'lib/x86_64/libpython.zip.so'])
            test = self.apk(directory, 'test.apk', ['classes.dex'])
            verify_abis(app, test)

    def test_rejects_missing_native_bridge_and_unexpected_abi_in_either_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            test = self.apk(directory, 'test.apk', ['classes.dex'])
            for files in [['classes.dex'], ['lib/arm64-v8a/libdistillpod_whisper.so'],
                          ['lib/x86_64/libdistillpod_whisper.so', 'lib/arm64-v8a/libforeign.so']]:
                app = self.apk(directory, 'main.apk', files)
                with self.subTest(files=files), self.assertRaises(ValueError):
                    verify_abis(app, test)
            app = self.apk(directory, 'main.apk', ['lib/x86_64/libdistillpod_whisper.so'])
            test = self.apk(directory, 'test.apk', ['lib/arm64-v8a/libforeign.so'])
            with self.assertRaises(ValueError):
                verify_abis(app, test)

    def test_malformed_archives_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory) / 'main.apk'; app.write_bytes(b'broken')
            with self.assertRaises((ValueError, zipfile.BadZipFile)):
                verify_abis(app, app)
