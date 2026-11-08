#!/usr/bin/env python3
"""Package committed app/build sources, pinned Whisper, and verified upstream sources.

Only Git-tracked blobs enter the project archive. Local credentials, device state,
build outputs and ignored files are never enumerated or copied.
"""
import argparse
import hashlib
import json
import subprocess
import tarfile
import tempfile
import urllib.request
from pathlib import Path


def tracked_archive(root, archive, prefix, revision="HEAD"):
    process = subprocess.Popen(["git", "archive", "--format=tar", "--prefix=" + prefix, revision], cwd=root, stdout=subprocess.PIPE)
    try:
        with tarfile.open(fileobj=process.stdout, mode="r|") as source:
            for member in source:
                archive.addfile(member, source.extractfile(member) if member.isfile() else None)
    finally:
        process.stdout.close()
    if process.wait() != 0:
        raise RuntimeError("Cannot archive the committed source")


def verified_download(component, destination):
    digest = hashlib.sha256()
    request = urllib.request.Request(component["source"], headers={"User-Agent": "DistillPodSource/1.0"})
    with urllib.request.urlopen(request, timeout=60) as source, destination.open("wb") as output:
        total = 0
        while chunk := source.read(1024 * 1024):
            total += len(chunk)
            if total > 300 * 1024 * 1024:
                raise ValueError("Upstream source exceeds the package limit")
            digest.update(chunk)
            output.write(chunk)
    if digest.hexdigest() != component["sha256"]:
        raise ValueError("Upstream source checksum mismatch: " + component["repository"])


def package(root, destination, version):
    subprocess.run(["git", "diff", "--exit-code", "HEAD", "--"], cwd=root, check=True, stdout=subprocess.DEVNULL)
    prefix = "distillpod-" + version + "/"
    submodule = root / "third_party/whisper.cpp"
    expected = subprocess.check_output(["git", "ls-tree", "HEAD", "third_party/whisper.cpp"], cwd=root, text=True).split()[2]
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=submodule, text=True).strip()
    if expected != actual:
        raise ValueError("Whisper source does not match the committed revision")
    manifest = json.loads((root / "app/src/main/assets/licenses/source-manifest.json").read_text())
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="distillpod-source-") as scratch, tarfile.open(destination, "w:gz") as archive:
        tracked_archive(root, archive, prefix)
        tracked_archive(submodule, archive, prefix + "third_party/whisper.cpp/")
        for component in manifest["components"]:
            name = component["repository"].replace("/", "-") + "-" + component["version"] + "-source.tar.gz"
            file = Path(scratch) / name
            verified_download(component, file)
            def anonymous(member):
                member.uid = member.gid = 0
                member.uname = member.gname = ""
                member.mtime = 0
                return member
            archive.add(file, arcname=prefix + "third-party-sources/" + name, recursive=False, filter=anonymous)
    print("Created source distribution from committed sources and verified upstream archives")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    package(Path(__file__).resolve().parent.parent, args.output, args.version)
