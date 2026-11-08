"""Regression checks for Android CI's native push path filter.

This models the workflow's limited glob vocabulary, not GitHub's server-side
300-file diff limit. No third-party Python dependency is required.
"""
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/ci.yml"
TEXT = WORKFLOW.read_text()
PUSH = TEXT.split("  push:\n", 1)[1].split("  pull_request:", 1)[0]
PATTERNS = re.findall(r"^      - '([^']+)'$", PUSH, re.MULTILINE)


def matches(path, pattern):
    # Supported GitHub glob subset: ** matches slashes, **/ may be empty.
    regex = re.escape(pattern)
    regex = regex.replace(r"\*\*/", "(?:.*/)?").replace(r"\*\*", ".*")
    regex = regex.replace(r"\*", "[^/]*")
    return re.fullmatch(regex, path) is not None


def should_run(paths):
    return any(not any(matches(path, pattern) for pattern in PATTERNS)
               for path in paths)


class PushFilterTest(unittest.TestCase):
    def test_expected_narrow_patterns(self):
        self.assertEqual(PATTERNS, [
            "**.md", "docs/**/*.png", "docs/**/*.mp4",
            "docs/android/evidence/**/*.json",
            "docs/android/evidence/**/*.jsonl",
            "docs/android/evidence/**/*.txt",
            "design/icon-proposal/assets/Manrope-OFL.txt",
        ])
        self.assertIn("branches: [main]", PUSH)

    def test_current_documentation_inventory(self):
        tracked = subprocess.check_output(
            ["git", "ls-files", "-z"], cwd=ROOT
        ).decode().split("\0")
        docs = [p for p in tracked if p and (
            (p.startswith("docs/") and Path(p).suffix in
             {".md", ".png", ".mp4", ".json", ".jsonl", ".txt"}) or
            p.endswith(".md") or
            p == "design/icon-proposal/assets/Manrope-OFL.txt")]
        self.assertTrue(docs)
        for path in docs:
            with self.subTest(path=path):
                self.assertFalse(should_run([path]))
        self.assertFalse(should_run(docs))

    def test_new_docs_and_deleted_docs_paths(self):
        # Native filtering uses changed paths for additions and deletions alike.
        for path in ["NEW.md", "nested/guide.md", "docs/new.png",
                     "docs/new.mp4", "docs/android/evidence/new.json",
                     "docs/android/evidence/nested/new.jsonl",
                     "docs/android/evidence/nested/new.txt"]:
            with self.subTest(path=path):
                self.assertFalse(should_run([path]))

    def test_code_resources_and_config_alone_or_with_docs(self):
        for path in [
            "app/src/main/Main.kt", "core/src/main/Core.kt",
            "app/src/test/Test.java", "app/src/main/cpp/bridge.cpp",
            "app/src/main/cpp/CMakeLists.txt", "app/src/main/res/icon.png",
            "app/src/main/assets/config.json", "app/build.gradle.kts",
            "gradle.properties", "gradlew", ".github/workflows/ci.yml",
            ".github/dependabot.yml", "firebase/firestore.rules",
            "firebase/firebase.json", "firebase/package-lock.json",
            "scripts/capture-docs-media.py", "scripts/test_ci_push_filter.py",
            "third_party/whisper.cpp", "branding/icon.svg",
            "design/icon-proposal/app.js", "design/icon-proposal/styles.css",
            "design/icon-proposal/index.html", "docs/example.py",
            "docs/example.kt", "docs/example.sh", "docs/config.yml",
            "docs/android/evidence/validator.py", "unknown/new.file",
        ]:
            with self.subTest(path=path):
                self.assertTrue(should_run([path]))
                self.assertTrue(should_run(["README.md", "docs/new.png", path]))

    def test_other_triggers_remain_unfiltered(self):
        triggers = TEXT.split("\npermissions:", 1)[0]
        self.assertIn("  pull_request:\n    branches: [main]\n", triggers)
        self.assertIn("  workflow_dispatch:\n", triggers)
        self.assertIn("  workflow_call:\n    inputs:", triggers)
        self.assertNotIn("paths", triggers.split("  pull_request:", 1)[1])


if __name__ == "__main__":
    unittest.main()
