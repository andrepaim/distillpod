# YouTube sources and videocasts

Search publishes Apple podcast results and public YouTube channel/playlist results independently, with provider-specific loading, failures and retries. **All**, **Audio**, and **Video** filters keep the two kinds easy to find. Late results append without moving already displayed results. YouTube results identify the channel or playlist explicitly. It does not hardcode a show, scrape
Spotify, require a paid directory, or create an RSS feed that points to HTML pages.

For Falha de Cobertura, the official Podpah TV playlist is
https://www.youtube.com/playlist?list=PLaE_mZALZ0V3lAEAvIfZC-aKf2ATIY3uy .
TV Quase also maintains https://www.youtube.com/playlist?list=PLA2Gd9vTv5MVeugwRKZeYaIn9Q1A7ojJv .
Choose the publisher in search, or paste its channel or playlist link in the add-feed sheet. A single
YouTube video URL can also be added. Channels use their latest **Videos** tab, newest first. Refresh loads up to the first 200 source entries;
unavailable, private, upcoming and live entries are excluded when their availability is known.
This is a bounded initial import, not a complete archive import of longer playlists.
Flat channel and playlist extraction omits each video's upload date and description. Refresh
therefore also reads YouTube's public, account-free feed for the channel or playlist
(`/feeds/videos.xml`), which lists the newest 15 videos with their exact publication time and
original description. Those values fill matching episodes. If the feed is unavailable, the
refresh still succeeds. This metadata never reorders a show, and a later refresh that lacks it
keeps the stored date and description. Opening an older video whose date or description is
still missing makes one single-video lookup and fills only the missing fields. The episode page
states when it is checking, when YouTube supplies no date or description, and when the lookup
failed (with **Retry**). Dates use the exact upload time when known; a date-only value keeps its
calendar day in local time zones. Dates outside the current year include the year. Values that
YouTube does not supply remain unknown rather than being invented.

The library preserves source order for unknown-date entries instead of alphabetizing them. Dated playlist entries sort newest first; undated playlists retain publisher order, which may not be chronological. Archived entries outside the bounded refresh remain available after the current source snapshot.

The player starts in audio mode. For video episodes, **Video** displays the picture above
the existing playback controls; **Audio** restores audio at the same position. The fullscreen button opens an immersive landscape view, including with rotation locked. With system auto-rotate enabled, turning to landscape enters fullscreen and returning upright exits. Back exits fullscreen first. Tap the picture to reveal or hide pause/play, seek, Distill, voice question and exit controls; controls stay visible during pause or interaction. Fullscreen changes the view and window policy while retaining the same MediaController, media item and video surface. Distill saves the current moment without leaving fullscreen; voice uses the existing setup and session sheet. Leaving the
player or backgrounding the app restores audio mode. Public RSS video enclosures also use
the video surface. YouTube video uses a separate H.264 track up to 720p with the original
audio track; auto-dubs have lower priority. Background audio, queue and position remain
owned by the existing Media3 service. Queue transitions reset the next episode to audio mode.
Queue edits preserve the current video selection. Offline YouTube downloads contain audio only. Reviewed ad skipping for YouTube uses that download in Audio mode; streamed Video is not skipped. Show automation uses the same first-refresh baseline and future-discovery rules as RSS, including entries whose publication dates are unknown.

Subscriptions store canonical playlist URLs and episodes store canonical watch URLs. Video
IDs determine episode identity. Temporary Google CDN URLs are resolved on the loading thread,
cached only in memory until one minute before the earliest signed URL expiry, with a 30-minute maximum (two minutes if expiry is absent), and never written to the database, backups or sync records.
Downloads resolve the audio again and validate that the downloaded file has an audio track.
Playback and downloads use byte ranges of at most 1 MiB: observed audio CDN requests with
open-ended or 5–10 MiB ranges returned HTTP 403 while 1 MiB ranges succeeded. Downloads
restart rather than append a partial from a possibly different representation. The existing
transcription pipeline consumes the resulting local audio file.

## Dependency and limitations

`io.github.junkfood02.youtubedl-android:library:0.18.1` packages the extraction runtime locally
(Python 3.12 and QuickJS); the APK overlays the checksum-pinned yt-dlp 2026.10.26 executable and its EJS 0.8.0 scripts. It increases APK/install size and requires extracted native
libraries. No account cookies, tokens, backend or automatic runtime code updates are used.
See [third-party notices](../../THIRD_PARTY_NOTICES.md) for the GPL dependency and distribution
requirements; the Android app and shared core are distributed under GPL-3.0-only. Releases include matching project/Whisper source, verified wrapper/yt-dlp/EJS source archives and a source manifest. Independent legacy/web code retains its existing terms.

YouTube can change extraction or reject requests by network/device. Restricted/DRM content
and live streams are unsupported. There is no claim that a search result guarantees playback.
YouTube subscriptions, progress and saved moments remain local under the existing cloud
protocol: its query-string URL guard rejects canonical playlist/watch URLs, and the client explicitly keeps queryless channel subscriptions local for compatibility with older RSS-only clients. This branch does
not change that protocol or the deployed Firebase rules. Local backups and OPML retain the
canonical URLs; restoring YouTube sources requires a build with this feature. RSS sync keeps
its existing behavior.

## Reliability improvements

Extraction has a 40-second total deadline including its single transient retry, bounded subprocess concurrency and request-specific cancellation. Cache locks cover memory operations only; requests for the same video share in-flight work. A rejected initial CDN URL triggers one cache invalidation and fresh resolution. Repeated rejection remains an error. Audio/video source changes carry the queue and starting position in one controller command, including changes while an earlier timeline is preparing. Video failures request an audio fallback once; audio failure remains visible.

Sanitized extraction and mode/output events use the existing opt-in diagnostics. Extraction events have random operation identifiers, categorical reasons and elapsed time; no video identifiers, titles, URLs or extractor output are uploaded. Instrumented acceptance emits marked test logs and does not replace real operation history. Dashboard counts describe observed events, including retries, rather than success-rate denominators.

## Validation

The current search, ordering, startup and fullscreen work is described in [video experience](video-experience.md).

The historical results below describe the initial implementation. Current integration and S22 delivery evidence is recorded in [videocast delivery](evidence/videocast-delivery.md).

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
