#!/usr/bin/env python3
"""Fail closed unless an app bundle is the tagged Play build, ready to upload by hand (#322).

The bundle itself is checked for signature files, its JAR signature and its upload certificate.
Everything else is checked on the universal APK that the pinned bundletool builds from it, with the
same guards as the GitHub APK: package and version from gradle.properties, not debuggable, the target
SDK, the Play distribution switches (scripts/verify_distribution_apk.py) and 16 KB alignment of every
native library (ELF LOAD segments, and ZIP alignment with zipalign).

    python3 scripts/verify_play_bundle.py distillpod.aab --tag v1.2.3 --bundletool bundletool-all.jar \\
        --build-tools "$ANDROID_HOME/build-tools/35.0.0" --certificate-sha256 "$PLAY_UPLOAD_CERT_SHA256"

Java tools (java, jarsigner, keytool) come from JAVA_HOME, or from PATH without it.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

import verify_distribution_apk as distribution
from release_metadata import metadata, validate_badging

BUNDLETOOL_VERSION = '1.18.3'
BUNDLETOOL_SHA256 = 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29'
BUNDLETOOL_URL = (f'https://github.com/google/bundletool/releases/download/{BUNDLETOOL_VERSION}/'
                  f'bundletool-all-{BUNDLETOOL_VERSION}.jar')
TARGET_SDK = 36
PAGE_SIZE = 16384
# What the Play bundle is built with, besides the Firebase and Sentry client configuration.
BUILD_PROPERTIES = ['-PdistillpodDistribution=play', '-PdistillpodVoiceEnabled=true']
MINIMUM_RSA_BITS = 2048
LICENSES = {'base/assets/licenses/THIRD_PARTY_NOTICES.md': 'THIRD_PARTY_NOTICES.md',
            'base/assets/licenses/source-manifest.json': 'source-manifest.json'}
SIGNATURE_FILE = re.compile(r'META-INF/[^/]+\.(?:SF|RSA|DSA|EC)', re.I)
# jarsigner -strict findings that every self-signed upload certificate produces.
SELF_SIGNED = ('This jar contains entries whose certificate chain is invalid. Reason: PKIX path building failed',
               'This jar contains entries whose signer certificate is self-signed.')
ENGLISH = ['-J-Duser.language=en', '-J-Duser.country=US']


def normalize_fingerprint(text):
    value = text.strip().replace(':', '').lower()
    if not re.fullmatch(r'[0-9a-f]{64}', value):
        raise ValueError('An upload certificate SHA-256 fingerprint is 64 hexadecimal digits')
    return value


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as source:
        for block in iter(lambda: source.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def load_alignments(data):
    """The p_align of every PT_LOAD program header of a little-endian ELF file."""
    if data[:4] != b'\x7fELF' or len(data) < 52 or data[4] not in (1, 2) or data[5] != 1:
        raise ValueError('not a little-endian ELF file')
    try:
        if data[4] == 2:
            (offset,) = struct.unpack_from('<Q', data, 0x20)
            size, count = struct.unpack_from('<HH', data, 0x36)
            headers = [struct.unpack_from('<IIQQQQQQ', data, offset + i * size) for i in range(count)]
            return [header[7] for header in headers if header[0] == 1]
        (offset,) = struct.unpack_from('<I', data, 0x1C)
        size, count = struct.unpack_from('<HH', data, 0x2A)
        headers = [struct.unpack_from('<IIIIIIII', data, offset + i * size) for i in range(count)]
        return [header[7] for header in headers if header[0] == 1]
    except struct.error as error:
        raise ValueError('truncated ELF program headers') from error


def native_alignment_problems(apk):
    with zipfile.ZipFile(apk) as archive:
        libraries = [name for name in archive.namelist() if re.fullmatch(r'lib/[^/]+/[^/]+\.so', name)]
        if not any(name.startswith('lib/arm64-v8a/') for name in libraries):
            return ['no arm64-v8a native library: the check would pass vacuously']
        found = []
        for name in sorted(libraries):
            try:
                alignments = load_alignments(archive.read(name))
            except ValueError as error:
                found.append(f'{name}: {error}')
                continue
            if not alignments or any(a < PAGE_SIZE or a & (a - 1) for a in alignments):
                found.append(f'{name}: LOAD segments aligned to {sorted(set(alignments))}, need {PAGE_SIZE}')
    return found


def signature_files(bundle):
    with zipfile.ZipFile(bundle) as archive:
        return sorted(name for name in archive.namelist() if SIGNATURE_FILE.fullmatch(name))


def validate_certificate(text, expected):
    fingerprints = [normalize_fingerprint(f) for f in re.findall(r'^\s*SHA256: ([0-9A-Fa-f:]+)\s*$', text, re.M)]
    if fingerprints != [expected]:
        raise ValueError(f'The bundle must have exactly one signer with the upload certificate {expected}; '
                         f'found {fingerprints or "none"}')
    bits = [int(b) for b in re.findall(r'^Subject Public Key Algorithm: (\d+)-bit RSA key', text, re.M)]
    if len(bits) != 1 or bits[0] < MINIMUM_RSA_BITS:
        raise ValueError(f'The upload key must be an RSA key of at least {MINIMUM_RSA_BITS} bits')


def java_tool(name):
    home = os.environ.get('JAVA_HOME')
    tool = Path(home) / 'bin' / name if home else shutil.which(name)
    if not tool or not Path(tool).is_file():
        raise ValueError(f'Cannot find {name}; set JAVA_HOME to a JDK')
    return str(tool)


def run(*command):
    result = subprocess.run([str(part) for part in command], capture_output=True, text=True)
    if result.returncode:
        step = ' '.join(str(part) for part in command[1:] if not str(part).startswith(('-', '/')))
        raise ValueError(f'{Path(command[0]).name} {step} failed:\n{result.stdout}{result.stderr}'.rstrip())
    return result.stdout


def jarsigner_errors(text):
    """The lines of jarsigner's Error: block."""
    errors, block = [], None
    for line in text.splitlines():
        if line.strip() in ('Error:', 'Warning:'):
            block = line.strip()
        elif not line.strip():
            block = None
        elif block == 'Error:':
            errors.append(line.strip())
    return errors


def validate_jarsigner(returncode, text):
    """Accept a verified JAR signature whose only strict-mode findings come from a self-signed signer.

    Upload keys are self-signed, so -strict always reports exit bit 4 with the two SELF_SIGNED errors. Any other
    bit (unsigned entries 16, key usage 8, alias 32) or any other error (expired, not yet valid or disabled
    algorithm, which also set bit 4) fails, as does a digest mismatch (exit 1).
    """
    unexpected = [line for line in jarsigner_errors(text) if not line.startswith(SELF_SIGNED)]
    if returncode not in (0, 4) or not re.search(r'^jar verified', text, re.M) or unexpected:
        detail = '\n  '.join(unexpected or text.strip().splitlines()[:3])
        raise ValueError(f'jarsigner does not verify the bundle (exit {returncode}):\n  {detail}')


def verify_signature(bundle, expected):
    if not signature_files(bundle):
        raise ValueError('The bundle is not signed')
    result = subprocess.run([java_tool('jarsigner'), *ENGLISH, '-verify', '-strict', str(bundle)],
                            capture_output=True, text=True)
    validate_jarsigner(result.returncode, result.stdout + result.stderr)
    validate_certificate(run(java_tool('keytool'), *ENGLISH, '-printcert', '-jarfile', bundle), expected)


def verify_content(bundle, bundletool, build_tools, work):
    if sha256(bundletool) != BUNDLETOOL_SHA256:
        raise ValueError(f'{bundletool} is not bundletool {BUNDLETOOL_VERSION} (SHA-256 {BUNDLETOOL_SHA256})')
    java = java_tool('java')
    run(java, '-jar', bundletool, 'validate', f'--bundle={bundle}')
    apks = work / 'universal.apks'
    run(java, '-jar', bundletool, 'build-apks', f'--bundle={bundle}', f'--output={apks}', '--mode=universal')
    with zipfile.ZipFile(apks) as archive:
        archive.extract('universal.apk', work)
    apk = work / 'universal.apk'
    badging = run(build_tools / 'aapt', 'dump', 'badging', apk)
    validate_badging(badging, metadata())
    if re.findall(r"^targetSdkVersion:'(\d+)'", badging, re.M) != [str(TARGET_SDK)]:
        raise ValueError(f'The bundle must target SDK {TARGET_SDK}')
    found = distribution.problems(apk, distribution.PRESETS['play'])
    found += native_alignment_problems(apk)
    if found:
        raise ValueError('The bundle does not match the Play distribution:\n  ' + '\n  '.join(found))
    run(build_tools / 'zipalign', '-c', '-P', '16', '4', apk)


def extract_licenses(bundle, directory):
    directory.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(bundle) as archive:
        names = set(archive.namelist())
        for entry, name in LICENSES.items():
            if entry not in names:
                raise ValueError(f'The bundle has no {entry}')
            (directory / name).write_bytes(archive.read(entry))


def build_info(expected, certificate):
    record = {key: expected[key] for key in ['applicationId', 'versionName', 'versionCode', 'tag']}
    record.update({'targetSdk': TARGET_SDK, 'distribution': 'play', 'switches': distribution.PRESETS['play'],
                   'gradleProperties': BUILD_PROPERTIES, 'uploadCertificateSha256': certificate,
                   'bundletoolVersion': BUNDLETOOL_VERSION})
    env = os.environ
    if env.get('GITHUB_SHA'):
        record['commit'] = env['GITHUB_SHA']
    if all(env.get(key) for key in ['GITHUB_SERVER_URL', 'GITHUB_REPOSITORY', 'GITHUB_RUN_ID']):
        run_url = f"{env['GITHUB_SERVER_URL']}/{env['GITHUB_REPOSITORY']}/actions/runs/{env['GITHUB_RUN_ID']}"
        record['workflowRun'] = run_url + (f"/attempts/{env['GITHUB_RUN_ATTEMPT']}" if env.get('GITHUB_RUN_ATTEMPT') else '')
    return record


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--tag', required=True)
    signing = parser.add_mutually_exclusive_group(required=True)
    signing.add_argument('--unsigned', action='store_true', help='require a bundle without any signature')
    signing.add_argument('--certificate-sha256', help='the upload certificate that must sign the bundle')
    parser.add_argument('--signature-only', action='store_true', help='check only the tag and the signature')
    parser.add_argument('--bundletool', type=Path)
    parser.add_argument('--build-tools', type=Path)
    parser.add_argument('--licenses-out', type=Path, help='write the packaged notices and source manifest here')
    parser.add_argument('--build-info', type=Path, help='write the build record here')
    args = parser.parse_args(argv)
    try:
        expected = metadata()
        if args.tag != expected['tag']:
            raise ValueError('Release tag does not match gradle.properties')
        certificate = normalize_fingerprint(args.certificate_sha256) if args.certificate_sha256 else None
        if args.unsigned and signature_files(args.bundle):
            raise ValueError('The candidate must be unsigned; it holds ' + ', '.join(signature_files(args.bundle)))
        if certificate:
            verify_signature(args.bundle, certificate)
        if not args.signature_only:
            if not args.bundletool or not args.build_tools:
                parser.error('--bundletool and --build-tools are required unless --signature-only')
            with tempfile.TemporaryDirectory() as work:
                verify_content(args.bundle, args.bundletool, args.build_tools, Path(work))
        if args.licenses_out:
            extract_licenses(args.bundle, args.licenses_out)
        record = build_info(expected, certificate)
        if args.build_info:
            args.build_info.parent.mkdir(parents=True, exist_ok=True)
            args.build_info.write_text(json.dumps(record, indent=2) + '\n')
    except (ValueError, OSError, zipfile.BadZipFile, KeyError) as error:
        print(f'{args.bundle}: {error}', file=sys.stderr)
        return 1
    print(f"{args.bundle.name}: verified {'signature' if args.signature_only else 'Play bundle'} for "
          f"{expected['tag']} (version code {expected['versionCode']})")
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
