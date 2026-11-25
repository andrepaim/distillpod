# Download network policy

Settings → Storage → **Wi-Fi / unmetered downloads only** applies to existing
queued audio and transcription-model downloads, as well as new requests.
Network-dependent queued transcript preparation follows the same setting,
including online lookup when the episode audio is already downloaded. Legacy
lookup jobs missing a network constraint are corrected during reconciliation.
Local-only transcript preparation retains its existing power constraints without
acquiring a network requirement.

Changing the setting normally updates WorkManager requests in place. Request IDs, unique
work names, enqueue time, initial delay, retry history, partial files, HTTP resume
validators, transcript revisions, and saved checkpoints are retained. Explicitly
cancelled or finished work is not restarted. Download/model enqueue and policy
reconciliation are serialized, as are transcription enqueue and reconciliation,
so an enqueue using an old preference cannot escape a concurrent policy update.
Startup also reconciles pre-existing work using the saved setting.

**Legacy transcript exception:** requests created before revision tags were added
cannot be safely identified from public WorkManager metadata. Their first
reconciliation waits for an active transcript chunk to finish, then replaces
pending legacy requests with current-revision recovery as needed. This replaces
legacy IDs, retry/backoff history and initial delays once, but retains transcript
text and checkpoints. Settings completion and new audio/model enqueues can wait
during that migration. See [queued transcript policy](transcript-network-policy.md)
for the compatibility tradeoff, revision isolation and cancellation guarantees.

An **already-running attempt finishes under its original network constraints**.
Its next attempt, if retried, uses the new policy. This is intentional: updating
the policy does not cancel a transfer or create another writer for its partial
file. To stop the current transfer immediately, use its existing Pause action.
The Settings explanation states this exception. Network changes can still cause
Android to stop a worker according to the constraints of its current attempt.
Foreground transcription sessions check the current policy before selecting
their next chunk.

Subscription refresh, periodic and manual, is not limited by this setting: feed
checks are small, so they run on any connected network, including mobile data.
An earlier version also gave the periodic refresh a Wi-Fi/unmetered constraint;
the next app start updates that schedule in place to any network. Toggling the
setting does not touch the refresh schedule. AI requests, account operations,
and cloud-sync policy are also unaffected.
The setting remains device-local; no schema, backup format, release metadata, or
production configuration changes are needed.

## Background starts, pauses and system stops

Audio and model downloads run as foreground work with a progress notification.
Android 12+ can refuse to start that foreground service when the job begins
while DistillPod is in the background, for example after a Wi-Fi constraint is
met, after a retry backoff, from show automation or from startup recovery. A
later attempt would be refused the same way, so the transfer continues at once
as ordinary background work, without the notification (#292). Progress still
shows on the episode or model row. The row never waits for the user because of
a refused start.

Without the foreground service, only a running job keeps DistillPod's process
active. WorkManager 2.10 records the worker as foreground work before Android
refuses the service, so the job scheduler's own stop (for example at its
10-minute limit) does not interrupt the worker, and a later start of the same
job joins the running worker instead of starting a second transfer. Once the
job has ended, though, Android 15+ puts the process in the cached state and
blocks the app's network about five seconds later (the job also ends this way
when WorkManager reschedules it after a process restart). The connection breaks
and every new request fails immediately. WorkManager's own retry would run in
the same cached process from its in-app timer and fail too, using up the retry
budget and failing the row.

So when a transfer whose foreground start was refused fails and Android reports
the default network as blocked for DistillPod (`DownloadNetwork`, from the
default network callback's blocked status), the transfer does not return to
WorkManager. The row goes back to **Queued** with its partial file and HTTP
validators, and the same worker run waits until the network is usable: present,
not blocked, and allowed by the Wi-Fi / unmetered setting. Meanwhile WorkManager
keeps the request's pending job. When Android starts that job (minutes later in
the background, longer in a restricted standby bucket) it joins the waiting
worker and binds the process, which lifts the block. Opening DistillPod lifts it
too. The transfer then resumes with a Range request. This wait does not count as
a retry. A failure with no reported block (a real outage or server error), or
one during a transfer whose foreground service did start, keeps the normal
retry budget. If the process is killed while waiting, the row stays **Queued**
and the next run resumes from the partial file. Pause still cancels the wait.

Earlier versions retried a refused start five times and then paused the row
with "Android blocked the background download. Open DistillPod and tap Resume."
(the model message was similar). The next app start resumes rows left with that
message; other paused rows stay paused. Other errors raised before the transfer
begins mark the row **Failed** instead of leaving it queued without work.

**Pause** in the app cancels the request and records the pause, even when the
request was still waiting for its network constraint or backoff, so a later
app start does not resume it. The notification's Pause action is also an app
cancellation. Any other stop (lost network, job time limits, quota, standby
restrictions or the Android 15 foreground-service time limit) is not a user
pause. WorkManager reschedules the request, so the row returns to **Queued** and
the transfer resumes from its partial file.

`DownloadForegroundRecoveryTest` drives the real `LibraryWorker` with an
injected foreground updater that rejects, fails or never starts. It covers a
refused start completing as ordinary work, a time-limit stop after a refusal
resuming with a Range request (episode and model), a pause during such a
transfer, a single transfer for concurrent attempts, startup resuming rows an
earlier version paused, pausing before start with startup recovery, and a system
stop compared with an app cancellation during a loopback transfer. A simulated
firewall breaks the connection and fails new requests while a fake network
status reports the block: transfers cut mid-way or started while blocked wait
as queued and resume in the same run (episode with the exact bytes, model with
a Range request), a pause during the wait sticks, a block lifted on a metered
network under Wi-Fi-only keeps waiting, and failures without a reported block or
with a foreground service keep the retry budget. `DownloadNetworkTest` covers
the wait itself, including a status change that never arrives.

## Regression checks

`DownloadNetworkPolicyTest` exercises the actual Settings action and WorkManager
records for both toggle directions, audio and models, network-dependent and local
transcripts, running workers, explicit cancellation, repeated requests, and
concurrent enqueue/reconciliation/cancellation. Existing downloader and
transcription tests cover validated HTTP Range resumption, cancellation closing
the writer, and transcript checkpoints.

`DownloadNetworkPolicyDeviceTest` uses the real Settings switch to update delayed
audio/model/revision-tagged transcript requests without replacing them or losing
progress, including capture-priority changes. A separate legacy case verifies
cancellation without duplicating current-revision recovery or losing checkpoints.
Another case runs an actual audio worker against a loopback server, changes the
policy while the server holds the response, and requires exactly one validated
Range transfer to finish with the full expected file.

For physical-device acceptance, build with a temporary Gradle init script that
sets only the validation application and test IDs:

```groovy
allprojects {
    afterEvaluate {
        if (path == ':app') {
            android.defaultConfig.applicationId = 'dev.distillpod.android.validation.issue19'
            android.defaultConfig.testApplicationId = 'dev.distillpod.android.validation.issue19.test'
        }
    }
}
```

Build `:app:assembleDebug :app:assembleDebugAndroidTest` with `-I` pointing to
that script, confirm both package names are unused, install only those packages,
then run `DownloadNetworkPolicyDeviceTest` through `IsolatedTestRunner` on an
emulator and the USB-connected phone. The phone must be unlocked for the UI
checks. Verify the logs with `scripts/device_result.py`, then remove only the
temporary packages. Never replace, clear, or uninstall the owner's installation
for these synthetic checks.

These tests do not measure carrier billing, long-duration background scheduling,
or a real model-server download. They test network constraints in WorkManager;
physical acceptance does not require changing the owner's Wi-Fi/mobile settings.
Record the exact revision, devices, results, and limitations separately before
claiming device validation.
