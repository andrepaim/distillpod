#!/usr/bin/env python3
"""Decide whether the release publish step creates a draft or resumes one.

Prints "create" when no release uses the tag, or "resume" when exactly one draft
uses it and holds no assets other than the ones this run uploads. A published
release, an ambiguous or unexpected state, or a failed lookup prints nothing on
standard output and exits non-zero, so the workflow changes nothing.
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path


class PublicationError(Exception):
    pass


def lookup(repo, run=subprocess.run):
    """Return every release of repo, including drafts visible to the caller's token."""
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo) or ".." in repo:
        raise PublicationError("Repository must be given as owner/name")
    # The list endpoint returns drafts to callers with push access; the by-tag
    # endpoint returns only published releases. gh's error goes to stderr as is.
    command = ["gh", "api", "--paginate", f"repos/{repo}/releases?per_page=100"]
    try:
        result = run(command, stdout=subprocess.PIPE, text=True, check=False)
    except OSError as error:
        raise PublicationError(f"Release lookup failed: {error.strerror or error}") from None
    if result.returncode != 0:
        raise PublicationError(f"Release lookup failed (gh exit {result.returncode})")
    return parse_pages(result.stdout)


def parse_pages(text):
    """Parse the concatenated JSON arrays that gh api --paginate prints."""
    decoder = json.JSONDecoder()
    releases, index, pages = [], 0, 0
    while True:
        while index < len(text) and text[index].isspace():
            index += 1
        if index == len(text):
            break
        try:
            page, index = decoder.raw_decode(text, index)
        except ValueError:
            raise PublicationError("Release lookup returned unreadable output") from None
        if not isinstance(page, list):
            raise PublicationError("Release lookup did not return a list of releases")
        releases.extend(page)
        pages += 1
    if pages == 0:
        raise PublicationError("Release lookup returned no output")
    return releases


def decide(tag, releases, assets):
    matches = []
    for record in releases:
        if not (isinstance(record, dict) and isinstance(record.get("tag_name"), str)
                and isinstance(record.get("draft"), bool)):
            raise PublicationError("Release lookup returned an unexpected record")
        if record["tag_name"] == tag:
            matches.append(record)
    if not matches:
        return "create"
    if any(not record["draft"] for record in matches):
        raise PublicationError(f"Release {tag} is already published; a rerun must not modify it")
    if len(matches) > 1:
        raise PublicationError(f"{len(matches)} draft releases use {tag}; inspect and remove the extras by hand")
    existing = matches[0].get("assets")
    if not isinstance(existing, list) or not all(isinstance(a, dict) and isinstance(a.get("name"), str) for a in existing):
        raise PublicationError(f"Draft release {tag} has unreadable assets")
    unexpected = sorted({a["name"] for a in existing} - {Path(a).name for a in assets})
    if unexpected:
        raise PublicationError(f"Draft release {tag} holds assets this run would not replace: {', '.join(unexpected)}")
    return "resume"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("assets", nargs="+")
    args = parser.parse_args()
    try:
        print(decide(args.tag, lookup(args.repo), args.assets))
    except PublicationError as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
