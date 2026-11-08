#!/usr/bin/env python3
"""Fetch the immutable public asset in CI using its normal read-only GitHub token.

The Gradle build independently verifies the same checksum before packaging. No
GitHub credential is read by Gradle or written into app resources.
"""
import argparse
import hashlib
import json
import subprocess
import time
from pathlib import Path


def fetch(destination):
    spec = json.loads((Path(__file__).parent / "youtube_extractor.json").read_text())
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(".download")
    try:
        for attempt in range(3):
            with temporary.open("wb") as output:
                result = subprocess.run(["gh", "api", spec["asset"], "--header", "Accept: application/octet-stream"], stdout=output, stderr=subprocess.PIPE, timeout=90)
            if result.returncode == 0:
                if hashlib.sha256(temporary.read_bytes()).hexdigest() != spec["sha256"]:
                    raise ValueError("Pinned extractor checksum mismatch")
                temporary.replace(destination)
                print("Verified pinned YouTube extractor " + spec["version"])
                return
            if attempt < 2:
                time.sleep(attempt + 1)
        raise RuntimeError("Cannot fetch the verified public extractor asset")
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    fetch(parser.parse_args().output)
