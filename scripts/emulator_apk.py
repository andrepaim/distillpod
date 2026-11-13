"""Fail closed if a disposable x86_64 acceptance build contains other native ABIs."""
from pathlib import Path
import sys
import zipfile


def verify_abis(main, tests):
    for path, is_main in [(main, True), (tests, False)]:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
        native = [name for name in names if name.startswith('lib/') and name.endswith('.so')]
        if any(len(name.split('/')) != 3 or name.split('/')[1] != 'x86_64' for name in native):
            raise ValueError('Disposable emulator APK contains an unexpected native ABI')
        if is_main and 'lib/x86_64/libdistillpod_whisper.so' not in native:
            raise ValueError('Disposable emulator APK is missing its x86_64 native bridge')


if __name__ == '__main__':
    try:
        if len(sys.argv) != 3:
            raise ValueError('Pass the main and test APK paths')
        verify_abis(Path(sys.argv[1]), Path(sys.argv[2]))
        print('Disposable emulator APK native ABIs verified: x86_64')
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
