# Download network policy

Settings → Storage → **Wi-Fi / unmetered downloads only** applies to existing
queued audio and transcription-model downloads, as well as new requests.
Network-dependent queued transcript preparation follows the same setting.
Local-only transcript preparation retains its existing power constraints without
acquiring a network requirement.

Changing the setting updates WorkManager requests in place. Request IDs, unique
work names, enqueue time, initial delay, retry history, partial files, HTTP resume
validators, transcript revisions, and saved checkpoints are retained. Explicitly
cancelled or finished work is not restarted. Download/model enqueue and policy
reconciliation are serialized, as are transcription enqueue and reconciliation,
so an enqueue using an old preference cannot escape a concurrent policy update.
Startup also reconciles pre-existing work using the saved setting.

An **already-running attempt finishes under its original network constraints**.
Its next attempt, if retried, uses the new policy. This is intentional: updating
the policy does not cancel a transfer or create another writer for its partial
file. To stop the current transfer immediately, use its existing Pause action.
The Settings explanation states this exception. Network changes can still cause
Android to stop a worker according to the constraints of its current attempt.
Foreground transcription sessions check the current policy before selecting
their next chunk.

Periodic subscription refresh is updated alongside the download policy. Manual
refresh, AI requests, account operations, and cloud-sync policy are unchanged.
The setting remains device-local; no schema, backup format, release metadata, or
production configuration changes are needed.

## Regression checks

`DownloadNetworkPolicyTest` exercises the actual Settings action and WorkManager
records for both toggle directions, audio and models, network-dependent and local
transcripts, running workers, explicit cancellation, repeated requests, and
concurrent enqueue/reconciliation/cancellation. Existing downloader and
transcription tests cover validated HTTP Range resumption, cancellation closing
the writer, and transcript checkpoints.

`DownloadNetworkPolicyDeviceTest` uses the real Settings switch to update delayed
audio/model/transcript requests without replacing them or losing progress. A
second case runs an actual audio worker against a loopback server, changes the
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
