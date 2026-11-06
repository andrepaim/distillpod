"""Reject personal-data containers in Git without printing their contents."""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess


MANIFEST = "security/reviewed-assets.json"
PRIVATE_SUFFIXES = {
    ".db", ".sqlite", ".sqlite3", ".db-wal", ".db-shm", ".sqlite-wal",
    ".sqlite-shm", ".opml", ".jks", ".keystore", ".p12", ".pfx",
    ".pem", ".key", ".log", ".apk", ".aab", ".env",
}
REVIEW_SUFFIXES = {
    ".png", ".jpg", ".jpeg", ".webp", ".gif", ".wav", ".mp3",
    ".m4a", ".flac", ".ogg", ".mp4", ".zip", ".tar", ".gz",
    ".7z", ".jar", ".bin",
}


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def file_problem(path, data, approved):
    name = PurePosixPath(path).name.lower()
    suffix = PurePosixPath(name).suffix
    if data.startswith(b"SQLite format 3\x00"):
        return "SQLite database (including renamed files)"
    if data[:4] in (bytes.fromhex("feedfeed"), bytes.fromhex("cececece")):
        return "Java keystore (including renamed files)"
    if suffix in PRIVATE_SUFFIXES or name in {
        ".env", ".coverage", "key.properties", "google-services.json",
        "credentials.json", "service-account.json",
    } or (name.startswith(".env.") and name != ".env.example"):
        return "private/generated file type"
    if re.fullmatch(r"distillpod-\d{4}-\d{2}-\d{2}.*\.json", name):
        return "personal backup document"
    if any(part.lower() in {"reports", "logs", "media", "screenshots", ".omc"}
           for part in PurePosixPath(path).parts[:-1]):
        return "personal capture/output directory"
    if len(data) > 8 * 1024 * 1024:
        return "file exceeds the 8 MiB repository limit"
    if suffix in REVIEW_SUFFIXES:
        entry = approved.get(path, {})
        if (not entry.get("reason") or
                hashlib.sha256(data).hexdigest() not in entry.get("sha256", [])):
            return "media/archive requires an exact reviewed-assets checksum"
    return None


def inspect_repository(root, base=None, head="HEAD"):
    head_sha = git(root, "rev-parse", "--verify", f"{head}^{{commit}}").decode().strip()
    approved = json.loads(git(root, "show", f"{head_sha}:{MANIFEST}"))
    revisions = [head_sha]
    if base and set(base) != {"0"}:
        if not re.fullmatch(r"[a-fA-F0-9]{40}|[a-fA-F0-9]{64}", base):
            raise ValueError("base must be a full commit SHA")
        git(root, "cat-file", "-e", f"{base}^{{commit}}")
        revisions += git(root, "rev-list", f"{base}..{head_sha}").decode().splitlines()
    seen = set()
    problems = []
    for revision in dict.fromkeys(revisions):
        for record in git(root, "ls-tree", "-rz", revision).split(b"\0"):
            if not record:
                continue
            metadata, raw_path = record.split(b"\t", 1)
            _mode, kind, object_id = metadata.decode().split()
            path = raw_path.decode("utf-8", errors="surrogateescape")
            if kind != "blob" or (path, object_id) in seen:
                continue  # Submodule histories belong to their upstream repositories.
            seen.add((path, object_id))
            data = git(root, "cat-file", "blob", object_id)
            reason = file_problem(path, data, approved)
            if reason:
                problems.append({"path": path, "commit": revision, "reason": reason})
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", help="Also inspect every new commit, including deleted files")
    args = parser.parse_args()
    root = Path(git(Path.cwd(), "rev-parse", "--show-toplevel").decode().strip())
    problems = inspect_repository(root, args.base)
    for problem in problems:
        print(json.dumps(problem, ensure_ascii=True))
    print(f"Repository data guard: {len(problems)} blocked file versions")
    return bool(problems)


if __name__ == "__main__":
    raise SystemExit(main())
