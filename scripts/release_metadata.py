#!/usr/bin/env python3
"""Validate release identity before an APK is signed or published."""
import argparse
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APPLICATION_ID = "dev.distillpod.android"
# Public certificate fingerprint of the existing personal-alpha installation.
ALPHA_CERTIFICATE = "5f384697c782acfaba9db54bca0149b1a7f59bfb9b21fcef70360b3c392fc5c7"


def metadata(root=ROOT):
    properties = dict(line.split("=", 1) for line in (root / "gradle.properties").read_text().splitlines()
                      if line.strip() and not line.lstrip().startswith("#") and "=" in line)
    version = properties["distillpodVersionName"].strip()
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[a-zA-Z0-9]+(?:[.-][a-zA-Z0-9]+)*)?", version):
        raise ValueError("Version must be a semantic version, optionally with a prerelease suffix")
    code = int(properties["distillpodVersionCode"])
    if code <= 0 or code > 2_100_000_000:
        raise ValueError("Android version code is out of range")
    return {"applicationId": APPLICATION_ID, "versionName": version, "versionCode": code,
            "tag": "v" + version, "certificateSha256": ALPHA_CERTIFICATE}


def validate_badging(text, expected):
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text, re.M)
    if not match or match.groups() != (expected["applicationId"], str(expected["versionCode"]), expected["versionName"]):
        raise ValueError("APK package/version does not match the tagged source")
    if re.search(r"^application-debuggable(?:\s|$)", text, re.M):
        raise ValueError("Distribution APK must not be debuggable")


def validate_certificate(text, expected):
    digests = re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", text)
    if [digest.lower() for digest in digests] != [expected["certificateSha256"]]:
        raise ValueError("APK is not signed by the retained personal-alpha certificate")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--verify-signature", action="store_true")
    args = parser.parse_args()
    expected = metadata()
    if args.tag and args.tag != expected["tag"]:
        raise ValueError("Release tag does not match gradle.properties")
    if args.apk:
        if not args.build_tools:
            parser.error("--apk requires --build-tools")
        badging = subprocess.check_output([str(args.build_tools / "aapt"), "dump", "badging", str(args.apk)], text=True)
        validate_badging(badging, expected)
        if args.verify_signature:
            certificate = subprocess.check_output([str(args.build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(args.apk)], text=True)
            validate_certificate(certificate, expected)
    print(json.dumps(expected, indent=2))


if __name__ == "__main__":
    main()
