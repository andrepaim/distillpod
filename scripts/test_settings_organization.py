"""Guard canonical settings ownership and stable navigation entry points."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FEATURES = ROOT / "app/src/main/java/dev/distillpod/android/features"

class SettingsOrganizationTest(unittest.TestCase):
    def setUp(self):
        self.product = (FEATURES / "ProductApp.kt").read_text()

    def section(self, name):
        return self.product.split('if(section=="' + name + '") {', 1)[1].split('if(section==', 1)[0]

    def test_sound_has_one_editable_control(self):
        source = "\n".join(p.read_text() for p in FEATURES.glob("*.kt"))
        self.assertEqual(1, len(re.findall(r'vm.flag\("distillSound",', source)))

    def test_charging_belongs_to_transcript_preparation(self):
        self.assertIn('Only transcribe while charging', self.section("transcription"))
        self.assertNotIn('prefs.chargingOnly', self.section("storage"))
        self.assertIn('vm.open("settings:storage")', self.section("transcription"))

    def test_subscription_refresh_belongs_to_library(self):
        self.assertIn('prefs.refresh', self.section("library"))
        self.assertIn('RefreshIntervalControl(vm, prefs)', self.section("library"))
        self.assertNotIn('prefs.refresh', self.section("storage"))

    def test_notifications_have_dedicated_destination(self):
        self.assertIn('section=="notifications"', self.product)
        data = (FEATURES / "DataControls.kt").read_text()
        library, notifications = data.split('@Composable fun NotificationControls()', 1)
        self.assertNotIn('RequestPermission', library)
        self.assertIn('NotificationAccess()', notifications)
        self.assertNotIn('NotificationAccess()', library)
        self.assertNotIn('RequestPermission', notifications)
        self.assertIn('NotificationControls()', self.section("notifications"))
        access = (FEATURES / "NotificationAccess.kt").read_text()
        self.assertIn('RequestPermission', access)
        self.assertIn('Lifecycle.Event.ON_RESUME', access)

if __name__ == "__main__":
    unittest.main()
