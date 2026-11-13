# Notification access

Settings → Notification access shows the notification state reported by
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
while visiting the application's notification Settings. Actual runtime revocation is verified in a second invocation after
the first instrumentation run ends because Android can kill the app on revoke.
A third invocation verifies a fresh runtime grant. Retained-screen revocation
is covered by the lifecycle regression tests.

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

The ordinary emulator runner excludes notification permission mutations from
its shared instrumentation invocation, then calls
`scripts/notification-device-tests.sh` for three separate invocations: denial
and blocked recovery, external revocation, and a fresh runtime grant. Each result
must be nonempty and successful. Two further invocations run the in-context
`promptDeny` and `promptGrant` phases, each after a fresh revocation. The runner
requires both an emulator serial and the emulator system property before
changing permissions; it cannot be used for the separate physical-phone
validation described above.

## In-context request (#193)

On Android 13 and later, the first episode download, model download or transcript
preparation the user starts (episode row or page, player, transcript tools,
Downloads & storage or Settings → Transcript preparation) is queued first. Then,
if notifications are off, the runtime permission is not granted and neither this
offer nor the Settings runtime request was used before, a short explanation offers
**Allow notifications** or **Not now**. Allow records the same attempt marker as
Settings and shows Android's dialog. A denial, **Not now** or dismissal is never
asked again; a single snackbar then links to Settings → Notification access, which
remains the way back. Work continues whatever the answer. Background and automatic
work never prompts, and Android 10–12L has no runtime request to offer.

`NotificationPromptTest` covers when the offer appears and when it does not, and
`DownloadNetworkPolicyTest` covers the view-model entry points. In
`NotificationPromptDeviceTest`, the `promptDeny` and `promptGrant` phases tap a
synthetic episode's Download button and answer Android's real dialog. Each starts
from a fresh revocation. For a physical phone, build with
`scripts/notify193-validation.init.gradle` (offline
`dev.distillpod.android.validation.notify193` and `.test` IDs). Revoke only that
package's permission before each phase and remove both packages afterwards.
