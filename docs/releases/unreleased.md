# Unreleased

- Transcript preparation starts when an episode plays, including playback through system controls and automatic queue advancement. **Settings → Local transcription → Transcribe when playing** defaults to on for new and existing installations.
- Completed transcripts and queued jobs are reused. Playback respects explicit transcript pauses and preserves failed/partial work for manual retry. Changing model or language settings does not regenerate existing text automatically.
- Publisher transcripts, optional existing online lookup, and local Whisper continue through the existing preparation pipeline. Downloads and processing respect network, battery, charging and storage policies. Local transcription waits for an explicitly downloaded model when needed.
- The player shows transcript progress and **Pause transcript**. Turning the setting off prevents new automatic requests; accepted work continues. Turning it on during playback evaluates the current episode.
