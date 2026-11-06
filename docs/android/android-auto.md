# Using DistillPod with Android Auto

DistillPod's Android Auto preview runs on the connected phone and presents its existing podcast library on a compatible car display. It supports USB and host-managed wireless projection; a separate Android Automotive OS installation is not included.

## Find the app

Install the signed preview as an upgrade to the existing app, open DistillPod once, then connect the phone to Android Auto. Look for DistillPod in the car's app launcher; scroll through the launcher or use Customize launcher if needed.

For privately installed builds, enable Android Auto developer mode and **Unknown sources** in its developer settings. These settings belong to Android Auto, separately from USB debugging. In Android Auto's settings, expand Version and tap Version and permission info ten times, accept the developer dialog, then open Developer settings from the overflow menu. [Official testing instructions](https://developer.android.com/training/cars/testing)

A public Play installation would use the usual distribution and car-quality review. This preview has not completed that review.

## Browse and listen

| Section | Contents |
| --- | --- |
| Continue | Unfinished episodes with progress; the saved current episode first |
| Queue | The existing queue in its current order |
| Downloads | Downloaded episodes whose local audio files still exist |
| Podcasts | Subscribed shows, followed by their episodes |

Selecting an unfinished episode resumes its saved position. Selecting a completed episode starts it again. Existing queued episodes keep their queue position; a new selection is placed at the front without duplicates. Browsing alone does not start playback.

Large folders expose More episodes when a host asks for the entire folder at once. Covers load asynchronously into a 32 MiB cache. Cached browse thumbnails can be read offline; uncached or unavailable artwork uses the DistillPod icon. Artwork never blocks the audio player. Search results are capped at the best 100 matches; the podcast browser remains available for deeper browsing.

Downloads work offline. Streaming, downloading new covers, assistant speech recognition and AI generation can require connectivity. Missing local audio falls back to the ordinary streaming policy when possible. Android Auto uses the same queue, listening progress, playback speed and audio-focus handling as the phone.

## Controls and saved moments

The expanded media view offers play/pause, back 10 seconds, forward 30 seconds and Distill. Android Auto controls placement and may put actions in overflow. Some dashboard widgets use their own seek icons; inspect the expanded player for the requested intervals. Episode next/previous commands remain distinct from time seeking.

Distill saves the preceding 60 seconds at the actual player position, clamped to the start. It works while paused and can acknowledge a local save without a ready transcript or AI connection. The car shows saving/saved feedback where its host supports action updates; the moment also appears in Saved on the phone.

Preparation runs separately. If audio, a model, source verification, connectivity or authorization is needed, the moment remains saved for later. Existing source-review requirements still apply to streamed audio. Pending preparation can recover after app restart. Transcripts stay local and paid content sync remains disabled.

The phone lock-screen notification retains back 10 seconds, play/pause and Distill in its compact controls, with forward 30 seconds available in the expanded controls. Android and Samsung ultimately choose the presentation.

## Voice requests

The service handles library searches and play requests from media ID or search metadata. It matches show/episode titles without case or accent sensitivity. A show request resumes its most recently played unfinished episode, otherwise its latest unfinished episode, then its newest episode. A generic request uses the saved session, then queue, then newest unfinished episode. No match leaves the current playback unchanged.

Speech recognition and app-name routing are provided by Gemini/Google Assistant. Callback-level matching is tested independently of speech recognition. Actual assistant recognition/app routing must be checked on the configured phone and language. The Distill action does not register a custom spoken “Distill this” command.

## Troubleshooting

If DistillPod is absent, check Unknown sources for a private build, open the app once and reconnect Android Auto. When replacing an APK during testing, reconnect the head unit so it drops the old media session. Confirm another development task has not replaced the APK with a build lacking automotive support.

If a cover is missing, allow it to load online or reopen the folder. If the library is empty, add subscriptions on the phone when parked. Ordinary local playback does not require sync sign-in. Permission or authorization setup belongs on the phone when it is safe to use it.

If playback cannot load, try a downloaded episode and check connectivity. A deliberately force-stopped application may need to be opened again. A normal host-requested resume restores the saved queue and position; connecting or browsing alone does not force playback.

## Development checks

Use a disposable emulator with the debug app and test APK already installed:

```sh
DISTILLPOD_SERIAL=emulator-5570 scripts/android-auto-tests.sh
```

Set `DISTILLPOD_ADB` and `DISTILLPOD_ADB_PORT` for Windows ADB from WSL. The runner refuses physical phones. It exercises modern/legacy browsing, subscription refresh, seek actions, paused capture, and two separate instrumentation phases around a force-stop. The ordinary emulator runner includes browser tests and invokes the separate restart phases afterward.

For a real phone, use Google's Desktop Head Unit. Configure Android Auto's head-unit server and forward port 5277 with the correct phone serial. Keep DHU and ADB on the same host. Personal library exports, installed APKs and car screenshots belong in private local evidence, not CI artifacts. [DHU instructions](https://developer.android.com/training/cars/testing/dhu)

See the [implementation plan](android-auto-plan.md), [TDD receipt](evidence/android-auto-tdd.md) and [validation record](evidence/android-auto-validation.md) for executed checks and remaining hardware coverage.


## Voice preview

Voice-enabled private builds advertise **Ask about this** in the expanded media player's extra/overflow actions. On supported templated-media hosts, open **DistillPod** from the car launcher to get the native playback screen with **Ask** and **Library** header actions. Do not look for a separate Voice icon: Android Auto can use the existing DistillPod launcher entry for the templated experience. Reconnect Android Auto after an upgrade, then open the app from the launcher if a recent playback shortcut still shows the old player.

If the host only supports the ordinary media browser, Ask returns an unsupported result and does not use the phone microphone. **Settings → Voice** on the phone reports whether a car voice screen is connected. The S25 with Android Auto 17.9 and Desktop Head Unit rendered the native Ask/Library controls on November 6, 2026; this does not establish real-car microphone support. See [voice preview setup and validation limits](voice-mode.md).
