import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from repository_data_guard import file_problem, inspect_repository


class RepositoryDataGuardTests(unittest.TestCase):
    def test_database_and_keystore_cannot_hide_behind_text_extension(self):
        for data in (b"SQLite format 3\x00fixture", bytes.fromhex("feedfeed") + b"fixture"):
            self.assertIsNotNone(file_problem("docs/example.txt", data, {}))

    def test_sensitive_containers_are_blocked_without_reading_values(self):
        for path in (".env", ".env.production", "config/owner.jks", "capture.opml",
                     "debug.log", "google-services.json", "reports/result.html",
                     "distillpod-2026-11-06.json", "renamed.sqlite3"):
            with self.subTest(path=path):
                self.assertIsNotNone(file_problem(path, b"synthetic fixture", {}))
        self.assertIsNone(file_problem(".env.example", b"EMPTY=", {}))

    def test_review_is_bound_to_both_path_and_exact_asset_bytes(self):
        data = b"synthetic public image fixture"
        approved = {"docs/fixture.png": {
            "reason": "Synthetic test fixture", "sha256": [hashlib.sha256(data).hexdigest()],
        }}
        self.assertIsNone(file_problem("docs/fixture.png", data, approved))
        self.assertIsNotNone(file_problem("docs/fixture.png", data + b"changed", approved))
        self.assertIsNotNone(file_problem("docs/owner.png", data, approved))

    def test_deleted_leak_in_new_history_still_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def run(*args):
                return subprocess.check_output(["git", "-C", directory, *args], stderr=subprocess.DEVNULL)

            run("init", "-q")
            run("config", "user.name", "Synthetic Fixture")
            run("config", "user.email", "fixture@example.invalid")
            (root / "security").mkdir()
            (root / "security/reviewed-assets.json").write_text(json.dumps({}))
            run("add", ".")
            run("commit", "-qm", "Clean baseline")
            base = run("rev-parse", "HEAD").decode().strip()
            (root / "library.txt").write_bytes(b"SQLite format 3\x00synthetic")
            run("add", "library.txt")
            run("commit", "-qm", "Accidental database")
            run("rm", "library.txt")
            run("commit", "-qm", "Remove database")
            (root / ".env").write_text("LOCAL_ONLY=fixture")
            self.assertEqual([], inspect_repository(root))
            problems = inspect_repository(root, base)
            self.assertEqual(1, len(problems))
            self.assertEqual("library.txt", problems[0]["path"])


if __name__ == "__main__":
    unittest.main()
