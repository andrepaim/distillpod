# S22 Ultra podcast transcription check

> Historical validation report. Results and commands below apply to the stated
> revision and device. For current behavior, use the [usage guide](../getting-started.md),
> [build instructions](../development.md) and [implementation status](implementation-status.md).

Executed November 2, 2026 on the Samsung S22 Ultra SM-S908E, Android 16/API 36, ARM64. This supplements the earlier repeated-speech chunk recovery test with real English and Brazilian Portuguese podcast audio. The official multilingual Tiny and Base files were size/hash verified; both are now available in the installed app.

| Model | Language | Audio | Native elapsed | Real-time factor | Sampled peak process PSS |
| --- | --- | --- | --- | --- | --- |
| Tiny | English | 90 s | 8.64 s | 0.096 | 447 MiB |
| Tiny | Portuguese | 90 s | 9.22 s | 0.102 | 473 MiB |
| Base | English | 90 s | 20.20 s | 0.224 | 582 MiB |
| Base | Portuguese | 90 s | 22.55 s | 0.251 | 547 MiB |

[Raw metrics](evidence/s22-podcast-benchmark.json) include segment/character counts and sampled managed/native allocation values. Process PSS and allocator-reported bytes measure different things; they should not be added together. Memory was sampled every 200 ms and can miss brief peaks. Model loading is included in the native elapsed time; model download and integrity checks are excluded. Language was specified explicitly for these runs.

Android reported thermal status 0 before and after all four short runs. This does not measure sustained heat, battery drain, playback contention, or all-day job quotas. Short-clip speed must not be extrapolated into a completed multi-hour benchmark.

Sources were public podcast excerpts: NPR *The Indicator*, “How crypto world made inroads in Pakistan” ([feed](https://feeds.npr.org/510325/podcast.xml)), and *Naruhodo* #478, “Por que mexemos os braços ao andar?” ([feed](https://feeds.simplecast.com/hwQVm5gy)). Audio and model files are kept outside the repository. The Portuguese clip begins 30 seconds into the audio, after stripping the source's large ID3 tag for this host-prepared benchmark fixture.

The Portuguese output contains recognizable source material, with proper-name and word-segmentation errors in both models. Base produced more plausible wording in several places, but no manually aligned reference or word-error rate was measured. Saved quotes still need an audio check when accuracy matters. Tiny remains the faster default; Base is an available alternative.

The same phone run passed production library/capture/replay, schema 1→2 upgrade, summary/chat/ad-review/replay, public identity-key connectivity, and loading the first 200 episodes of the large Naruhodo feed. [Runner output](evidence/s22-final-phone-tests.txt) records all six checks. The [complete public-episode check](evidence/s22-complete-episode-tests.txt) subsequently passed: a 646.426-second enclosure downloaded and completed six transcript chunks in 94.457 seconds. The feed/test input advertised 537 seconds; the pipeline used the downloaded audio timeline instead. [Full-episode metadata](evidence/s22-full-episode.json) records 10,342,893 downloaded bytes and 161 timed segments.
