# Notification access

Settings → Import & notifications shows the notification state reported by
Android. It refreshes when the screen resumes and after a permission request,
including grants and revocations made in system Settings while the screen is
retained.

When notifications are off, **Open notification settings** is always available.
Android 13 and later also offer **Enable notifications** for an initial runtime
request. After an app-initiated request, recovery uses Settings. The local attempt
marker survives recreation and restart; it records an attempt rather than
claiming the app knows Android's permanent-denial state. An older installation
without this marker can still open Settings directly, and a suppressed request
leaves the Settings action available.

Android 10–12L and already-granted-but-disabled cases open notification Settings
directly. The intent targets the current application package, with app-details
Settings as a fallback when an OEM does not provide the notification activity.
The explanatory copy concerns download/transcript progress and does not claim
that denying notifications removes Android's exempt media-session controls.

`NotificationAccessTest` covers initial status, runtime grant/denial, retained
screen grant/revocation, already-attempted recovery, and Android 12L Settings
return. `NotificationAccessDeviceTest` covers an actual runtime grant, denial
with recreation, blocked legacy permission, and external grants/revocations
while visiting the application's notification Settings.

Physical acceptance must use a separate `dev.distillpod.android.validation.issue20`
application ID and matching `.test` ID, overridden with a temporary Gradle init
script as described in [download policy validation](download-network-policy.md).
Run only `NotificationAccessDeviceTest` through `IsolatedTestRunner`, verify the
nonempty result with `scripts/device_result.py`, and remove only the temporary
packages afterward. The phone must be unlocked. Permission commands in this
fixture target only the validation package; the owner's installed application
and its permissions must remain untouched.

Record the tested source revision and device results in the issue/PR before
claiming device acceptance. Android 12L coverage is automated; a current Android
phone does not establish physical-device behavior on older OS versions. No
account action, data migration, release publication, or production configuration
change is needed.
