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
    for source in [*(ROOT / "app/src/test").rglob("*.kt"), *(ROOT / "app/src/sharedTest").rglob("*.kt")]:
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

    def test_unit_tests_run_on_the_java_version_each_resolved_sdk_requires(self):
        # DefaultSdkProvider refuses SDK 36 ("Android SDK 36 requires Java 21") on an older JVM,
        # while the build itself stays on JDK 17. Only the test JVM moves.
        launcher = re.search(r"tasks\.withType<Test>\(\)\.configureEach \{[^}]*?javaLauncher = javaToolchains\.launcherFor "
                             r"\{ languageVersion = JavaLanguageVersion\.of\((\d+)\) \}", BUILD)
        if max(resolved_levels()) < 36:
            return
        self.assertIsNotNone(launcher, "SDK 36 needs the app's Test tasks to run on a Java 21 launcher")
        self.assertGreaterEqual(int(launcher.group(1)), 21)


if __name__ == "__main__":
    unittest.main()
