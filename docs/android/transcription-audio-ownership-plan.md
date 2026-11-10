# Keep transcription audio owned during use (#60)

Base: `9a082cdcaa8de4714f023bd617a21192c50b2474`. This is one
prerequisite for [bulk cleanup](retention-cleanup-plan.md).

Transcription chooses a downloaded file and then reads its episode row before
acquiring processing ownership. An episode transfer lock currently protects
selection and deletion separately. A worker paused in that gap can lose its
copy because the persisted pause no longer blocks deletion. Also, creating
the temporary waveform before entering the release guard can leak ownership
if creation throws. These are source-level findings, without evidence of an
organic occurrence.

Add an internal scoped read to MediaRepository: select or download and acquire
processing ownership under the existing episode transfer lock, then release
the lock before the reader runs. Release the file on success, exception, or
cancellation. Use it in transcription before any suspendable DAO reads, with
model ownership and temporary-file cleanup protected from exceptions. Keep
download-only callers, typed deletion blocks, progress, checkpoint and cancel
handling, and the serialized queue baseline. No data migration, retention
settings, automatic cleanup or changes to playback ownership in this increment.

Before fixing the handoff, run a controlled regression with real Room and file
deletion using the old unprotected selection. Then require the same fixture
to preserve paused transcript state, file bytes and age while a reader holds
the copy. Check that unrelated audio remains removable, orphan cleanup skips
owned files, nested use releases independently, and exceptions and cancellation
allow subsequent deletion. Use a private in-memory database and per-test
synthetic files. Include the same contracts in platform instrumentation.

Risk: incorrect lifetime can remove a file in use or leave it permanently
blocked. Review both lock order and every worker exit; preserve #56 startup,
#58 deletion feedback and #81 conditional state transitions. Run focused
regressions, both default/voice JVM/lint/APK gates, applicable emulator tests,
the same controlled scenarios on an offline isolated S22 APK pair, and full
hosted PR and combined-main CI. These phone fixtures verify real filesystem
and Room behavior during controlled cancellation, not uncontrolled OEM
scheduling. Preserve the production phone app and data. No release.

Keep #60 open after this increment. Playback/queue/loader exclusion, bulk
preview and confirmation, transcript suppression, policy UI and scheduling
still need implementation and their own acceptance.
