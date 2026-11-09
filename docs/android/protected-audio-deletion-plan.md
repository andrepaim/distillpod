# Protected audio deletion (#58)

## Plan and risk
Treat in-use audio deletion as a typed expected blocked action with stable reason and actionable feedback. Preserve transaction/transfer/file-lock guards, canonical path validation, metadata, queued work, and unexpected error reporting. Expose known readiness at both episode menus and storage management; repository checks remain authoritative for races.

1. Prove the existing repository rejection is not structured with a failing regression.
2. Add a narrow typed rejection and action handling; cover all protection owners, unchanged files/metadata, release-and-retry, invalid paths and I/O failures.
3. Disable/explain deletion when observable ownership is known and test affected UI.
4. Run unit/lint/build/security checks and isolated emulator plus phone acceptance, then draft PR.

Risk: low diagnostic/UI change, but deletion and persisted state demand explicit preservation tests. No schema, release, credentials, accounts or production content changes. Phone acceptance is expressly required by the issue. Use only a distinct test application ID and synthetic fixture files, never production installation or personal content. Coordinate an exclusive slot before device actions. Required device or combined-main checks remain blockers until recorded.

## Evidence
Pending. Base: 9528abf7bff2a55ce713dda9e9e911793dd8ba04.

## Related work
Issue #60 adds bulk deletion and retention. It should reuse this typed refusal and guards to skip/defer protected items; it is separate work and does not block #58. No automatic deletion or retention policy is introduced here.

## Review correction
A paused-partial confirmation can race with resumed/queued downloading. Its paused-only precondition previously ran before protection checks, misclassifying that expected block. A new action-level regression failed before reordering the checks. Canonical validation and protection now precede the paused-only shape check; non-protected invalid states still report unexpected failures.

## Acceptance protocol
The synthetic UI fixture covers both episode-menu and storage deletion readiness, queued/transcribing/downloading/active owners, a confirmation-time ownership change, cancel, direct repository rejection, and successful retry after release. It preserves synthetic resume metadata, listening position, transcript, audio and partial bytes while protected. Physical execution requires the exact isolated validation application ID, with an offline manifest overlay and no production configuration. Ordinary hosted emulator CI also runs the fixture. Existing non-atomic ActiveFiles ownership transitions are not expanded or redesigned by this classification change.
