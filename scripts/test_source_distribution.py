import io
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path
from source_distribution import tracked_archive, verified_download


class SourceDistributionTests(unittest.TestCase):
    def test_only_committed_sources_are_archived_not_local_credentials_or_dirty_content(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            (root / "source.kt").write_text("committed source")
            subprocess.run(["git", "add", "source.kt"], cwd=root, check=True)
            subprocess.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test", "-c", "commit.gpgsign=false", "commit", "-qm", "fixture"], cwd=root, check=True)
            (root / "source.kt").write_text("uncommitted replacement")
            (root / "local.properties").write_text("LOCAL-CREDENTIAL-MARKER")
            (root / "private-token").write_text("LOCAL-CREDENTIAL-MARKER")
            data = io.BytesIO()
            with tarfile.open(fileobj=data, mode="w") as archive:
                tracked_archive(root, archive, "distillpod-fixture/")
            data.seek(0)
            with tarfile.open(fileobj=data) as archive:
                self.assertEqual(["distillpod-fixture", "distillpod-fixture/source.kt"], archive.getnames())
                self.assertEqual(b"committed source", archive.extractfile("distillpod-fixture/source.kt").read())
            self.assertNotIn(b"LOCAL-CREDENTIAL-MARKER", data.getvalue())

    def test_source_hash_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "upstream.tar.gz"
            source.write_bytes(b"changed upstream archive")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                verified_download({"source": source.as_uri(), "sha256": "0" * 64, "repository": "fixture/source"}, Path(folder) / "download")


if __name__ == "__main__":
    unittest.main()
