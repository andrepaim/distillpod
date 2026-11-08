import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from fetch_youtube_extractor import fetch


class ExtractorFetchTests(unittest.TestCase):
    def test_successful_http_response_with_wrong_payload_cannot_replace_an_existing_artifact(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "ytdlp.bin"
            output.write_bytes(b"previous artifact")

            def wrong_asset(command, stdout, **kwargs):
                stdout.write(b"unexpected executable or error page")
                return subprocess.CompletedProcess(command, 0)

            with patch("fetch_youtube_extractor.subprocess.run", side_effect=wrong_asset):
                with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                    fetch(output)
            self.assertEqual(b"previous artifact", output.read_bytes())
            self.assertFalse(output.with_suffix(".download").exists())


if __name__ == "__main__":
    unittest.main()
