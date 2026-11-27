"""Fail closed unless a built APK contains exactly the features its distribution allows (#319, #320, #323).

A switch that is off must leave no trace a store review would find: no packaged binary or native
library, no class, no endpoint or setting text in any dex file, and no third-party notice. A switch
that is on must still contain every marker, so a renamed class cannot turn the check into a no-op.
The presets mirror distributionPresets in app/build.gradle.kts.

    python3 scripts/verify_distribution_apk.py --distribution play app/build/outputs/apk/debug/app-debug.apk
"""
import argparse
import fnmatch
import re
import struct
import sys
import zipfile
from pathlib import Path

# Every marker of each switchable feature. Entries are APK paths (glob patterns); strings are dex string
# constants, which include every referenced class descriptor; notices are words in the packaged licenses.
FEATURES = {
    'youtube': {
        'entries': ['res/raw/ytdlp.bin', 'lib/*/libpython.so', 'lib/*/libpython.zip.so', 'lib/*/libqjs.so'],
        'strings': [
            'Lcom/yausername/youtubedl_android/YoutubeDL;',
            'Ldev/distillpod/android/video/YouTubeSource;',
            'Ldev/distillpod/android/video/AndroidYouTubeExtractor;',
            'Ldev/distillpod/android/video/HttpYouTubeMetadataFetcher;',
            'Ldev/distillpod/android/video/ExtractionCall;',
            'Ldev/distillpod/android/video/RefreshingYouTubeDataSource;',
            'https://www.youtube.com/results',
        ],
        'forbidden_prefixes': ['Lcom/yausername/'],
        'notices': ['youtubedl-android', 'yt-dlp'],
    },
    'updateCheck': {
        'entries': [],
        'strings': [
            'Ldev/distillpod/android/data/ReleaseFeed;',
            'Ldev/distillpod/android/work/UpdateCheckWorker;',
            'https://api.github.com/repos/andrepaim/distillpod/releases?per_page=10',
            'Check for updates',
        ],
        'forbidden_prefixes': ['https://api.github.com/'],
        'notices': [],
    },
    'sync': {
        'entries': [],
        'strings': [
            'Ldev/distillpod/android/sync/SubscriptionSyncWorker;',
            'Ldev/distillpod/android/sync/FirebaseSubscriptionCloud;',
            'Ldev/distillpod/android/sync/FirebaseContentObjects;',
            'Ldev/distillpod/android/features/PublicFeedReviewKt;',
            'Lcom/google/firebase/auth/FirebaseAuth;',
            'Lcom/google/firebase/firestore/FirebaseFirestore;',
            'Landroidx/credentials/CredentialManager;',
            'Lcom/google/android/libraries/identity/googleid/GoogleIdTokenCredential;',
            'Connect Google & sync library',
        ],
        'forbidden_prefixes': ['Lcom/google/firebase/', 'Landroidx/credentials/', 'Lcom/google/android/libraries/identity/'],
        'notices': ['firebase', 'credential manager'],
    },
}
PRESETS = {
    'github': {name: True for name in FEATURES},
    'play': {name: False for name in FEATURES},
}
LICENSES = ['assets/licenses/THIRD_PARTY_NOTICES.md', 'assets/licenses/source-manifest.json']
DEX = re.compile(r'classes\d*\.dex')


def _uleb128(data, offset):
    result = shift = 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, offset
        shift += 7


def dex_strings(data):
    """Every entry of a dex file's string table (MUTF-8 decoded leniently; markers are ASCII)."""
    if not data.startswith(b'dex\n') or len(data) < 0x70:
        raise ValueError('Not a dex file')
    count, table = struct.unpack_from('<II', data, 0x38)
    if table + count * 4 > len(data):
        raise ValueError('Truncated dex string table')
    strings = []
    for index in range(count):
        (start,) = struct.unpack_from('<I', data, table + index * 4)
        _, start = _uleb128(data, start)
        end = data.index(b'\0', start)
        strings.append(data[start:end].decode('utf-8', 'replace'))
    return strings


def inspect(apk):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        strings = set()
        for name in names:
            if DEX.fullmatch(name):
                strings.update(dex_strings(archive.read(name)))
        if not any(DEX.fullmatch(name) for name in names):
            raise ValueError('The APK contains no dex file')
        notices = '\n'.join(archive.read(name).decode('utf-8', 'replace') for name in LICENSES if name in names).lower()
    return names, strings, notices


def problems(apk, switches):
    names, strings, notices = inspect(apk)
    found = []
    for feature, enabled in switches.items():
        markers = FEATURES[feature]
        for pattern in markers['entries']:
            present = any(fnmatch.fnmatchcase(name, pattern) for name in names)
            if present != enabled:
                found.append(f"{feature}: {'missing' if enabled else 'contains'} {pattern}")
        for text in markers['strings']:
            if (text in strings) != enabled:
                found.append(f"{feature}: {'missing' if enabled else 'contains'} {text}")
        if not enabled:
            for prefix in markers['forbidden_prefixes']:
                leaked = sorted(s for s in strings if s.startswith(prefix))
                if leaked:
                    found.append(f"{feature}: contains {len(leaked)} strings starting {prefix}, e.g. {leaked[0]}")
        for word in markers['notices']:
            if (word in notices) != enabled:
                found.append(f"{feature}: licenses {'omit' if enabled else 'name'} {word}")
    return found


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('apk', type=Path)
    parser.add_argument('--distribution', choices=sorted(PRESETS), required=True)
    parser.add_argument('--switch', action='append', default=[], metavar='NAME=true|false',
                        help='override one switch, as -Pdistillpod<Name>Enabled does')
    args = parser.parse_args(argv)
    switches = dict(PRESETS[args.distribution])
    try:
        for override in args.switch:
            name, _, value = override.partition('=')
            if name not in FEATURES or value not in ('true', 'false'):
                raise ValueError(f'Unknown switch override {override}')
            switches[name] = value == 'true'
        found = problems(args.apk, switches)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        print(str(error), file=sys.stderr)
        return 1
    if found:
        print(f'{args.apk} does not match its distribution switches:', file=sys.stderr)
        for line in found:
            print('  ' + line, file=sys.stderr)
        return 1
    summary = ', '.join(f"{name} {'on' if on else 'off'}" for name, on in switches.items())
    print(f'{args.apk.name}: distribution switches verified ({summary})')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
