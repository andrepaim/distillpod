# Troubleshooting

Use the [current signed APK](https://github.com/andrepaim/distillpod/releases) and
keep the app installed while updating. The [usage guide](getting-started.md)
describes the normal flow; the cases below explain common states and the next
action. These are personal-alpha behaviors, not guarantees about every device.

## A saved moment has no takeaway

Saving the source and generating its takeaway are separate steps. A headphone
gesture or **Distill last 60 seconds** can save successfully while the transcript,
source review, ChatGPT connection or allowance still needs attention. Open the
moment in **Saved** and follow its displayed action. A confirmation sound means
the moment was saved; it does not mean generation finished.

Connect through **Settings → ChatGPT** for generation. If the source needs review,
read the proposed passage before confirming it. **Expand context and retry** or
**Transcribe and retry** can help when offered. A takeaway may legitimately omit
a quote. **Improve this distill** retains the current result, and previous
versions can be restored. Requests can consume account allowance even if they
fail; retries are deliberate. [Source and review behavior](android/contextual-distills.md).

## Transcript preparation is waiting or incomplete

| What you see | What to check |
| --- | --- |
| A model is needed | Download Tiny or Base in **Settings → Local transcription**. Model downloads are explicit. |
| Waiting for network or charging | Check **Downloads & storage**, the Wi-Fi/unmetered policy and **Transcribe while charging**. |
| Paused, failed or partial text | Use the transcript's resume/retry action. Pressing Play does not override an explicit pause or failure. |
| No useful online match | Lookup is optional and public-source-only. Prepare locally when no suitable existing text is found. |
| Online text is readable but a moment needs more preparation | Online lookup timing is not used for source-window capture or automatic ad skipping; publisher/local timing is required. |
| Text looks inaccurate | Review the audio, choose an appropriate language/model, then use **Transcribe locally again** if needed. Changing a preference alone does not rewrite existing text. |

Automatic preparation is controlled by **Transcribe when playing**, on by
default. Turning it off stops new automatic requests; pause existing work in the
transcript controls. Full-episode chat and summaries require complete text.
See [transcript preparation](getting-started.md#prepare-a-transcript).

## Headphone controls skip instead of saving

Enable **Settings → Playback & headphones → Distill with previous-track button**;
it is off by default and stays on this device. In the accessory's app, map the
desired gesture to **Previous track**, if supported. DistillPod responds to that
media-button command, not directly to the physical tap pattern. Another gesture
that sends the same command will also capture while DistillPod handles playback.

Turning the option off restores the previous-track action. Single-earbud,
multipoint, calls and another active player can change which command is sent or
which app receives it. The feature does not promise to start a force-stopped app.
Physical accessory behavior still needs device-specific validation.
[Setup and current limits](android/headphone-distill.md).

For a missing confirmation sound, check **Distill confirmation sound**, media
volume and the active media output. Repeated button events may be suppressed;
look in Saved before assuming a new moment was not kept.

## ChatGPT sign-in or an AI request does not finish

Complete the browser flow and return to DistillPod to check its final connection
state. A provider confirmation email alone does not prove the app connected.
Account eligibility, model access and allowance depend on the connected account.
Follow the app's reconnect or retry action; playback and saved source moments
remain available without a completed AI request.

Google library-sync sign-in does not connect ChatGPT. Do not put account tokens
or callback URLs in a public issue. [Account and data handling](privacy.md).

## Library sync is unavailable or pending

An ordinary source/debug build is intentionally unconfigured. Use an appropriate
configured release, or follow the [development configuration guide](android/subscription-sync.md#build-configuration).
API-key restrictions must match the app's actual signing certificate.

Approve public feeds for sharing and use the same Google account on both
installations. The local library remains bound to its original sync account;
uninstalling is not an account-switching procedure. Offline changes wait for a
connection; conflicts require the displayed local/cloud choice. Android idle or
force-stop may defer work until the app is reopened.

New contextual moments can wait for the explicit protocol-3 upgrade. Update all
installations first. **Sync queue & preferences** is a separate optional scope.
Full transcripts, episode chat, analyses, explanations and social posts are not
part of enabled library sync; use manual backups for their transfer. See
[protocol compatibility](android/subscription-sync.md#contextual-moments-and-protocol-3).

## Drive looks empty or a restore does not replace progress

Check the account selected in Android's file picker and enter **My Drive**.
Use **Connect existing backup** for a managed ZIP history, or **Import older
backup** for a standalone JSON export. That import picker shows all file types;
DistillPod validates the actual contents.

Restore is a merge: it inserts missing records and keeps existing local records
and listening positions. It is not a rewind to an older library snapshot.
Reconnect the selected backup document on a new installation. Reconnect accounts and re-download audio and model binaries separately. A readable
provider file does not prove another device has downloaded its latest bytes.
[Backup, recovery and retention](android/drive-backup.md).

## An APK update is rejected

Use the signed APK attached to a published release, not a temporary CI debug
artifact. Android updates require the same application ID and signing identity,
and a compatible version code. A privately installed build may be ahead of the
latest public release. Do not uninstall or clear app storage as an update fix;
preserve the existing library and check the installed/source versions first.
[Signing and release details](releases.md).

## Report a reproducible problem

Include app version, Android version, device model, the steps, expected behavior
and the displayed error. Say whether the issue affects streaming, downloaded
audio, a particular source, or an isolated emulator. Use synthetic content in
public captures; omit credentials, private feeds, personal transcripts, backups
and account screenshots. [Privacy](privacy.md) and [security](security.md) explain
the data boundaries. Broader Bluetooth, battery and accessibility coverage is
tracked in [implementation status](android/implementation-status.md).
