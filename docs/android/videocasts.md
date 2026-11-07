# YouTube sources and videocasts

Search combines Apple's podcast directory with public YouTube playlist results. Results identify
the playlist owner and are labelled **YouTube · videocast**. It does not hardcode a show, scrape
Spotify, require a paid directory, or create an RSS feed that points to HTML pages.

For Falha de Cobertura, the official Podpah TV playlist is
https://www.youtube.com/playlist?list=PLaE_mZALZ0V3lAEAvIfZC-aKf2ATIY3uy .
TV Quase also maintains https://www.youtube.com/playlist?list=PLA2Gd9vTv5MVeugwRKZeYaIn9Q1A7ojJv .
Choose the publisher in search, or paste its playlist link in the add-feed sheet. A single
YouTube video URL can also be added. Refresh loads up to the first 200 playlist entries;
unavailable, private, upcoming and live entries are excluded when their availability is known.
This is a bounded initial import, not a complete archive import of longer playlists.
Flat playlist entries often omit upload dates; those dates remain unknown rather than being
invented. The current library sorts unknown-date entries by title.

The player starts in audio mode. For video episodes, **Video** displays the picture above
the existing playback controls; **Audio** restores audio at the same position. Leaving the
player or backgrounding the app restores audio mode. Public RSS video enclosures also use
the video surface. YouTube video uses a separate H.264 track up to 720p with the original
audio track; auto-dubs have lower priority. Background audio, queue and position remain
owned by the existing Media3 service. Queue transitions reset the next episode to audio mode.
Queue edits preserve the current video selection. Offline YouTube downloads contain audio only.

Subscriptions store canonical playlist URLs and episodes store canonical watch URLs. Video
IDs determine episode identity. Temporary Google CDN URLs are resolved on the loading thread,
cached in memory for two minutes, and never written to the database, backups or sync records.
Downloads resolve the audio again and validate that the downloaded file has an audio track.
Playback and downloads use byte ranges of at most 1 MiB: observed audio CDN requests with
open-ended or 5–10 MiB ranges returned HTTP 403 while 1 MiB ranges succeeded. Downloads
restart rather than append a partial from a possibly different representation. The existing
transcription pipeline consumes the resulting local audio file.

## Dependency and limitations

`io.github.junkfood02.youtubedl-android:library:0.18.1` packages the extraction runtime locally
(including yt-dlp 2025.11.12). It increases APK/install size and requires extracted native
libraries. No account cookies, tokens, backend or automatic runtime code updates are used.
See [third-party notices](../../THIRD_PARTY_NOTICES.md) for the GPL dependency and distribution
requirements; this change does not authorize an APK publication or relicense repository code.

YouTube can change extraction or reject requests by network/device. Restricted/DRM content
and live streams are unsupported. There is no claim that a search result guarantees playback.
YouTube subscriptions, progress and saved moments remain local under the existing cloud
protocol: its query-string URL guard rejects canonical playlist/watch URLs. This branch does
not change that protocol or the deployed Firebase rules. Local backups and OPML retain the
canonical URLs; restoring YouTube sources requires a build with this feature. RSS sync keeps
its existing behavior.

## Validation

Deterministic tests cover canonical URLs, lookalike-host and invalid-path rejection, a Falha
fixture absent from Apple, subscription/refresh identity and progress, offline re-follow,
partial-provider failure, cancellation, original-language audio selection, DRM/unexpected-host
rejection, local-only cloud behavior, video-marker preservation and offline audio selection.
Bounded-range tests cover multiple chunks, seeks, shorter final chunks, ignored ranges,
changing representations and incomplete downloads.

Validation was performed in WSL on 2026-11-07, on the feature branch based on `5b800bb`.
The commands and results below apply to this feature revision, not a published APK:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 -m unittest discover -s scripts -p 'test_*.py'
git diff --check
```

| Check | Result |
| --- | --- |
| Core unit tests | 94 passed |
| Android unit tests | 346 passed |
| Repository-script tests | 22 passed |
| Android lint | No errors; 72 warnings and one informational finding |
| Debug and instrumentation APK builds | Passed |
| APK/native packaging | `zipalign -c -P 16 4` passed; packaged ELF load segments align to 16 KiB |
| Synthetic emulator acceptance | Three tests passed |
| Live YouTube acceptance | Audio/video playback observed; final repeat failed during extraction |

The development APK is about 118 MiB with the bundled extraction runtime. No APK was published.

`VideocastPlaybackDeviceTest` uses synthetic/local audio to check controller/service mode
changes, paused position, speed, queue transitions and cleanup from an obsolete episode.
It does not establish video decoding. `PlaybackDeviceTest` and `QueueSyncPlaybackDeviceTest`
check background audio and current-item preservation when the queue changes. These three
tests passed on an isolated, read-only Android API 36 x86_64 emulator instance,
`emulator-5580` (no owner phone).
Their only media fixture is the pinned public `jfk.wav` used by the existing device suite.

The deliberate `YouTubeAcceptanceDeviceTest` performs the actual search, loads the official
playlist, checks media bytes (rejecting HTML error pages), and requires Media3 to decode audio
and render a video frame. Run only on an isolated development emulator, with
`allowPublicYoutube=true` and the test class selected explicitly. It skips by default and is
also excluded from ordinary CI. It does not modify subscriptions or use live accounts.

Package the public WAV fixture for the three synthetic playback tests, then install the debug
and instrumentation APKs on the isolated emulator. For Windows ADB from WSL, translate APK
paths with `wslpath -w`; see [development instructions](../development.md).

```sh
mkdir -p /tmp/distillpod-videocast-fixtures
cp third_party/whisper.cpp/samples/jfk.wav /tmp/distillpod-videocast-fixtures/jfk.wav
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PdistillpodFixtureDir=/tmp/distillpod-videocast-fixtures
adb -s emulator-5580 shell am instrument -w -r \
  -e class dev.distillpod.android.YouTubeAcceptanceDeviceTest \
  -e allowPublicYoutube true \
  dev.distillpod.android.test/dev.distillpod.android.IsolatedTestRunner
```

With the 1 MiB range implementation, the live test passed: public search found the official
Falha playlist, its episode audio returned valid container bytes, and Media3 decoded audio and
rendered a video frame. That passing run preceded the final queue-preservation helper. On the
final branch revision, the three synthetic tests passed again, while the live test failed
before playback in `YouTubeSource.streams` with "YouTube is unavailable. Try again later."
Earlier attempts also encountered a CDN connection timeout. Live acceptance is intermittent;
the observed playback success does not establish reliable public-network availability.
Full-episode download/transcription and physical-device checks have not been performed.
