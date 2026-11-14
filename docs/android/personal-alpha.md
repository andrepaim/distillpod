# DistillPod Android 0.4.0 personal alpha

Historical build guide. For current usage and source/release availability, see [getting started](../getting-started.md) and [implementation status](implementation-status.md). Paths, installed models and validation below refer to this build.

Updated November 3, 2026 for the Samsung S22 Ultra SM-S908E, Android 16. The owner chose a fresh Android library. The existing web app remains unchanged, and the phone's verified ChatGPT connection is preserved.

The refreshed interface adds Continue listening, an artwork mini-player with direct queue access, clearer episode/queue states, a focused player and quote-focused saved moments. [Research, proposal and implementation](ui-improvement-proposal.md) describe the design decisions. The Library, Search, Saved and Queue destinations remain familiar; the full player has explicit Back and Queue controls.

Search now provides publisher thumbnails, preview sheets, Follow/Open actions, topic tiles, clearable local recent searches and RSS entry. Editing a query cancels its obsolete request. [Search research and proposal](search-ui-proposal.md) record the design and physical checks, including Android system text at 160%.

## Start using it

1. Open **Search**, find a show or add its RSS URL, then follow it.
2. Play an episode or download it for offline listening. Queue, seek, speed and progress work without ChatGPT.
3. Open the episode and select **Prepare transcript**. Tiny is selected for speed; verified Tiny and Base multilingual files are already installed. Choose the language in Settings, or leave Auto. Completed chunks survive pause/resume.
4. While listening, tap **Distill** in the player to save the last 60 seconds. The source window is saved before AI work. Saved moments support replay, copy/share, retry and transcript explanations.
5. **Summary & chapters** and **Ask episode** use the prepared transcript. Chapter and chat source buttons replay the original audio timeline. Suggested ads require review plus the skip switch; Undo or a deliberate seek replays them normally.
6. **Search → Explore by interest** requests real-feed suggestions. Sending followed-show titles is optional; suggestions do not run automatically.
7. Use **Settings → Backup & Restore** for library metadata/text. Restore merges existing records safely. OPML transfers subscriptions. Backups exclude account credentials, audio files and models.

AI requests use the existing authorized ChatGPT allowance. Audio transcription runs locally. A saved streamed moment may ask for source review before sending text for generation. Quota/auth errors preserve the excerpt, and explicit retries may consume allowance again.

## Validation

The local suite passes 110 tests (48 core, 62 Android JVM), lint and both APK assemblies. Physical receipts cover library/download/transcript/capture/replay, native chunk recovery, schema 1→2 upgrade, live account renewal, real quote/insight/summary/chat/explanation requests, six verified public feed suggestions, reviewed ad skip/replay and a large Portuguese RSS feed. Transcript preparation now shows live progress within each chunk.

A complete public enclosure of 10m46s downloaded and transcribed across six chunks in about 94 seconds. MP3 frame rounding and seek-offset defects found by that test were corrected. Tiny and Base also transcribed real 90-second English/Portuguese clips; see [benchmark details](podcast-benchmark.md). Transcription still makes errors, especially with names and word boundaries.

The [locked-screen hour](background-playback.md) passed 60m17s without interruption, errors or underruns. Synthetic audio-focus interruptions and force-stop/reopen also passed. The broader UI device regression passes actual resume/seek/speed/queue actions, saved-source navigation, filter recovery and 160% text layout. The authoritative current artifact checksum and install status are in [the search build receipt](evidence/search-ui-build.json). The source APK is `android/app/build/outputs/apk/debug/app-debug.apk`; a versioned copy is retained outside the repository under the local DistillPod tools release directory. The media service is unchanged from the earlier hour-test build.

## Personal-use boundaries

This is the build for the owner trial. Real Bluetooth/telephone interruptions, simultaneous playback/transcription, multi-hour quality/unplugged power/OS quotas, broader accessibility/localization/device coverage and the seven-day trial remain acceptance work. The development signing certificate is retained so this APK upgrades the connected installation without clearing its data; public-store distribution is separate.

Web research is not enabled. Saved-moment explanations explicitly use transcript context and do not invent web citations. Server/browser migration is deferred because the owner chose a fresh Android library.
