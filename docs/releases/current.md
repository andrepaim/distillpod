DistillPod 0.19.0-alpha.1 adds public YouTube playlists and videocasts alongside podcast listening, saved ideas and voice questions.

- **Discover and follow:** YouTube playlist results arrive independently from Apple podcast results. Add a public playlist or video link directly; retry an unavailable provider without discarding other results.
- **Audio or video:** episodes start in audio mode. Choose Video in the player, then return to audio at the same position. Background playback, queue and speed stay with the existing media service. Source swaps preserve the starting position even while an earlier timeline is preparing.
- **Offline learning:** download YouTube audio for offline playback and local transcription. Video remains streamed; offline downloads contain audio only.
- **Reliability:** bounded extraction deadlines/retries, scoped cancellation, short-lived stream caching and one refresh of a rejected initial CDN URL. New optional diagnostics cover extraction and video/audio transitions without collecting titles, URLs, audio or transcripts.
- **Source and license:** the Android app, shared core and their original build/release scripts are GPL-3.0-only. Settings → About DistillPod provides license/source access. This release includes the matching source archive, pinned upstream source archives, component manifest and third-party notices; independent legacy/web code retains its existing terms.

Install as an update without uninstalling. Version code **36** supersedes code 35. Application ID, retained signing identity, library database, backup format and existing Firebase protocol stay compatible. YouTube subscriptions, progress and saved moments remain local under that protocol; RSS sync keeps its existing behavior. Voice preview stays enabled and paid content sync stays disabled.

This personal-alpha import loads at most the first 200 playlist entries. Unknown dates remain unknown. Private/restricted/live content is unsupported, and YouTube availability can change by device/network. Audio fallback requests cannot prove audible output. Bluetooth/head-unit behavior requires separate hardware evidence.

Validation and publication gates are still in progress. Final results will be recorded in the [delivery receipt](https://github.com/andrepaim/distillpod/blob/v0.19.0-alpha.1/docs/android/evidence/videocast-delivery.md) before publication.
