import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from release_publication import PublicationError, decide, lookup

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/release_publication.py"
TAG = "v1.2.0-alpha.1"
ASSETS = ["dist/distillpod-1.2.0-alpha.1.apk", "dist/distillpod-1.2.0-alpha.1-source.tar.gz", "dist/SHA256SUMS",
          "dist/build-info.json", "dist/THIRD_PARTY_NOTICES.md", "dist/source-manifest.json"]


def release(tag, draft, assets=()):
    return {"id": 7, "tag_name": tag, "draft": draft, "prerelease": True, "body": "notes",
            "assets": [{"name": name} for name in assets]}


class Completed:
    def __init__(self, returncode, stdout):
        self.returncode = returncode
        self.stdout = stdout


class DecisionTests(unittest.TestCase):
    def test_creates_when_no_release_uses_the_tag(self):
        self.assertEqual("create", decide(TAG, [release("v1.1.0-alpha.1", False)], ASSETS))
        self.assertEqual("create", decide(TAG, [], ASSETS))

    def test_resumes_a_draft_left_by_an_earlier_attempt(self):
        partial = release(TAG, True, ["distillpod-1.2.0-alpha.1.apk", "SHA256SUMS"])
        self.assertEqual("resume", decide(TAG, [release("v1.1.0-alpha.1", False), partial], ASSETS))
        self.assertEqual("resume", decide(TAG, [release(TAG, True)], ASSETS))

    def test_published_release_is_never_resumed(self):
        with self.assertRaisesRegex(PublicationError, "already published"):
            decide(TAG, [release(TAG, False, [Path(a).name for a in ASSETS])], ASSETS)

    def test_published_release_with_a_stray_draft_is_never_resumed(self):
        with self.assertRaisesRegex(PublicationError, "already published"):
            decide(TAG, [release(TAG, True), release(TAG, False)], ASSETS)

    def test_ambiguous_drafts_fail_closed(self):
        with self.assertRaisesRegex(PublicationError, "2 draft releases"):
            decide(TAG, [release(TAG, True), release(TAG, True)], ASSETS)

    def test_draft_with_assets_this_run_would_not_replace_fails_closed(self):
        with self.assertRaisesRegex(PublicationError, "old.apk"):
            decide(TAG, [release(TAG, True, ["old.apk", "SHA256SUMS"])], ASSETS)

    def test_unexpected_release_records_fail_closed(self):
        for records in ([{"tag_name": TAG}], [{"draft": True}], ["v1.2.0"], [release(TAG, "false")],
                        [{**release(TAG, True), "assets": None}], [{**release(TAG, True), "assets": [{}]}]):
            with self.subTest(records=records), self.assertRaises(PublicationError):
                decide(TAG, records, ASSETS)


class LookupTests(unittest.TestCase):
    def lookup_output(self, returncode, stdout):
        calls = []

        def run(command, **kwargs):
            calls.append((command, kwargs))
            return Completed(returncode, stdout)
        return lookup("owner/repo", run=run), calls

    def test_reads_every_page_of_the_release_list(self):
        pages = json.dumps([release("v1.1.0-alpha.1", False)]) + "\n" + json.dumps([release(TAG, False)])
        releases, calls = self.lookup_output(0, pages)
        self.assertEqual(["v1.1.0-alpha.1", TAG], [r["tag_name"] for r in releases])
        command = calls[0][0]
        self.assertEqual(["gh", "api", "--paginate", "repos/owner/repo/releases?per_page=100"], command)

    def test_empty_release_list_is_an_answer(self):
        self.assertEqual([], self.lookup_output(0, "[]\n")[0])

    def test_failed_lookup_fails_closed(self):
        with self.assertRaisesRegex(PublicationError, "lookup failed"):
            self.lookup_output(1, "")

    def test_unreadable_lookup_fails_closed(self):
        for stdout in ("", "not json", '{"message": "Not Found"}', "[] trailing", "[]{}"):
            with self.subTest(stdout=stdout), self.assertRaises(PublicationError):
                self.lookup_output(0, stdout)

    def test_missing_gh_fails_closed(self):
        def run(command, **kwargs):
            raise FileNotFoundError("gh")
        with self.assertRaisesRegex(PublicationError, "lookup failed"):
            lookup("owner/repo", run=run)

    def test_repository_must_be_owner_and_name(self):
        for repo in ("", "owner", "owner/repo/extra", "../repo", "owner/repo?x=1"):
            with self.subTest(repo=repo), self.assertRaises(PublicationError):
                lookup(repo, run=lambda *a, **k: self.fail("gh must not run"))


class CommandLineTests(unittest.TestCase):
    def run_script(self, gh_exit, gh_stdout):
        with tempfile.TemporaryDirectory() as folder:
            fake = Path(folder) / "gh"
            (Path(folder) / "out.json").write_text(gh_stdout)
            fake.write_text(f"#!/bin/sh\ncat '{folder}/out.json'\nexit {gh_exit}\n")
            fake.chmod(0o755)
            env = {**os.environ, "PATH": f"{folder}{os.pathsep}{os.environ['PATH']}", "GH_TOKEN": "TOKEN-MARKER"}
            return subprocess.run([sys.executable, str(SCRIPT), "--repo", "owner/repo", "--tag", TAG, *ASSETS],
                                  capture_output=True, text=True, env=env)

    def test_prints_only_the_decision(self):
        result = self.run_script(0, json.dumps([release(TAG, True)]))
        self.assertEqual((0, "resume\n"), (result.returncode, result.stdout))
        result = self.run_script(0, "[]")
        self.assertEqual((0, "create\n"), (result.returncode, result.stdout))

    def test_refusal_prints_no_decision_and_no_credentials(self):
        for gh_exit, stdout in ((0, json.dumps([release(TAG, False)])), (1, "")):
            with self.subTest(gh_exit=gh_exit):
                result = self.run_script(gh_exit, stdout)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", result.stdout)
                self.assertIn(TAG if gh_exit == 0 else "lookup failed", result.stderr)
                self.assertNotIn("TOKEN-MARKER", result.stdout + result.stderr)


class WorkflowTests(unittest.TestCase):
    def test_publish_step_creates_or_resumes_with_one_asset_list(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        step = workflow.split("- name: Publish prerelease with verified assets", 1)[1]
        self.assertIn('python3 scripts/release_publication.py --repo "$GH_REPO" --tag "$GITHUB_REF_NAME" "${assets[@]}"', step)
        self.assertEqual(1, step.count("gh release create"))
        self.assertIn('--draft "${assets[@]}"', step)
        self.assertIn('gh release upload "$GITHUB_REF_NAME" --clobber "${assets[@]}"', step)
        self.assertEqual(1, step.count("assets=("))

    def test_source_job_has_a_timeout(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        source = workflow.split("  source:\n", 1)[1].split("\n  validation:", 1)[0]
        self.assertRegex(source, r"\n    timeout-minutes: \d+\n")


if __name__ == "__main__":
    unittest.main()
