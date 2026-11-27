"""Guard the Gradle-resolved Robolectric jars that unit tests run against offline (#282)."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = (ROOT / "app/build.gradle.kts").read_text()


def resolved_levels():
    table = BUILD.split("val robolectricAndroidAll = mapOf(", 1)[1].split(").map", 1)[0]
    return {int(level) for level in re.findall(r"(\d+) to \"", table)}


def requested_levels():
    target = int(re.search(r"targetSdk = (\d+)", BUILD).group(1))
    levels = set()
    # test/ plus each distribution switch's testXxxOn/testXxxOff directory (#319).
    directories = [*(ROOT / "app/src").glob("test*"), ROOT / "app/src/sharedTest"]
    for source in [path for directory in directories for path in directory.rglob("*.kt")]:
        text = source.read_text()
        levels.update(int(n) for group in re.findall(r"@Config\([^)]*sdk\s*=\s*\[([^\]]*)\]", text)
                      for n in re.findall(r"\d+", group))
        # Robolectric runs a class without a class-level sdk on targetSdk.
        if re.search(r"RunWith\((RobolectricTestRunner|AndroidJUnit4)::class", text) and not re.search(r"@Config\([^)]*sdk\s*=", text):
            levels.add(target)
    return levels


class RobolectricOfflineTest(unittest.TestCase):
    def test_tests_run_offline_without_an_online_fallback(self):
        self.assertIn('systemProperty("robolectric.offline", "true")', BUILD)
        self.assertIn('systemProperty("robolectric.dependency.dir"', BUILD)

    def test_every_requested_sdk_level_is_resolved_by_gradle(self):
        missing = requested_levels() - resolved_levels()
        self.assertFalse(missing, f"add the android-all-instrumented jar for SDK {sorted(missing)} to app/build.gradle.kts")

    def test_no_unused_sdk_jar_is_downloaded(self):
        self.assertEqual(set(), resolved_levels() - requested_levels())


if __name__ == "__main__":
    unittest.main()
