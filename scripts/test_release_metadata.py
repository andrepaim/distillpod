import unittest
from release_metadata import metadata, validate_badging, validate_certificate


class ReleaseIdentityTests(unittest.TestCase):
    def setUp(self):
        self.expected = metadata()
        self.badging = (f"package: name='{self.expected['applicationId']}' "
                        f"versionCode='{self.expected['versionCode']}' versionName='{self.expected['versionName']}'\n")

    def test_rejects_a_valid_but_different_app(self):
        with self.assertRaises(ValueError):
            validate_badging(self.badging.replace("dev.distillpod.android", "dev.other.app"), self.expected)

    def test_rejects_stale_apk_version(self):
        with self.assertRaises(ValueError):
            validate_badging(self.badging.replace(f"versionCode='{self.expected['versionCode']}'", "versionCode='1'"), self.expected)

    def test_rejects_debuggable_distribution(self):
        with self.assertRaises(ValueError):
            validate_badging(self.badging + "application-debuggable\n", self.expected)

    def test_rejects_runner_generated_signing_key(self):
        with self.assertRaises(ValueError):
            validate_certificate("Signer #1 certificate SHA-256 digest: " + "a" * 64, self.expected)

    def test_accepts_expected_release_identity(self):
        validate_badging(self.badging, self.expected)
        validate_certificate("Signer #1 certificate SHA-256 digest: " + self.expected["certificateSha256"], self.expected)


if __name__ == "__main__":
    unittest.main()
