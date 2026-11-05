DistillPod 0.13.0-alpha.1 adds contextual saved moments while retaining Android Auto and autonomous social sharing from recent releases.

- Distill keeps the original capture and examines bounded surrounding transcript context to select one complete thought. A second AI request reviews the takeaway against the selected source.
- Supporting quotes are optional. A supported takeaway can be saved without a quote when the quotation would lose context; unsupported or incomplete results remain unpublished.
- Improve a saved distill, expand its context explicitly, inspect the source, and restore an earlier version. Replay and new share drafts use the selected passage; existing share drafts and explanations retain their original source snapshots.
- Captures near the end of a complete publisher transcript now work when the feed omits episode duration. Android Auto still saves immediately and recovers pending preparation after restart.
- Local database and backup upgrades preserve captures, generation attempts and version history. Synced snapshots use account protocol 3; Settings offers an explicit upgrade after all installations are updated. Until that upgrade, new-format moment uploads remain pending while unrelated library sync continues. Transcripts, attempts and version history stay local and are included in backups.

Automated checks cover source bounds, review decisions, cancellation, retry, history, backups, migrations, Android Auto and sync compatibility. Live-model comparison and blind human assessment have not been completed; this alpha does not claim a measured improvement in excerpt quality. A normal successful generation now uses two AI requests and may use more time and account allowance.

This personal-alpha release retains the existing signing certificate and Firebase client configuration. Paid content sync remains disabled and transcripts stay local. Install as an update over the compatible alpha app; do not uninstall. Android 10+; ARM64 and x86_64. Version code: 22.

See [contextual distills](https://github.com/andrepaim/distillpod/blob/main/docs/android/contextual-distills.md) and the [Android Auto guide](https://github.com/andrepaim/distillpod/blob/main/docs/android/android-auto.md).
