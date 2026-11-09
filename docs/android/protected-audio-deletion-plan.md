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

## Local validation
Tested combined source revision: 7e5adf23ecfb83d37760b7257b2a2d3b5efbe0ca (#58 plus #54 queue controls).
- Three red regressions reproduced: unstructured protection exception; expected rejection sent to unexpected-error sink; resumed paused-partial race misclassified.
- Final combined :core:test (99 tests), :app:testDebugUnitTest (513 tests), :app:lintDebug, :app:assembleDebug and :app:assembleDebugAndroidTest passed. No test failures or skips.
- Repository Python suite: 87 passed. Committed-data guard, secret-rule regression and fully redacted full-history Gitleaks passed.
- Hosted emulator acceptance and isolated phone validation are pending, not waived. Coordinator owns current-main integration and final combined CI. No release or production data changes.

## Acceptance fixture synchronization
Integrated #54 test-only synchronization correction 24c7905 and explicitly scroll the library before opening the protected-audio episode menu. Both changes preserve the behavioral assertions and runtime code. Normal lint/application/instrumentation builds and isolated offline application/instrumentation builds passed at b44907a9611c6eaefe2a36b636565adc3f927414. Hosted and phone execution remain required.

## Hosted acceptance diagnosis
Run 42600295369 passed Kotlin/lint/APK, native, Firebase and security gates. The protected-deletion fixture passed queued episode-menu/storage assertions, then timed out waiting for transcription-only feedback. It had cleared only the DAO queue, while PlaybackService restores that queue into Media3 and checkpoints real ownership back into ActiveFiles/Room. The revised fixture explicitly observes live queued ownership, clears the controller, and awaits controller/Room/ActiveFiles release before the next phase. It records content-free state evidence and uses acquire/release for stable processing ownership. Production guards and reason precedence are unchanged. The queue-controls fixture also failed in that combined run; its owner is investigating separately. No failed check is waived.
Normal lint/application/instrumentation builds passed for this fixture correction. New hosted acceptance and physical validation remain pending. Phone attempts stopped before any installation because ADB transport was unavailable; no production data was accessed or deleted.

## Blocked-action completion barrier
The confirmation-race fixture now retains processing ownership until the exact delete action's busy key is cleared by its finally block, then verifies files and metadata before releasing the owner. Existing disabled-action feedback alone was not a reliable completion signal. Normal lint/application/instrumentation and offline APK builds passed; this is a test-only correction.

## Diagnostic-only follow-up for the generic timeout
Final run 42604143242 failed ProtectedAudioDeletionDeviceTest and QueueControlsDeviceTest with generic 10-second timeout stacks (79 tests, 2 failures). The retained instrumentation artifact preserves exception messages, but not the fixture's println phase messages, so the failing wait and any cleanup masking could not be established.
Every explicit protected-deletion timed wait now throws a phase-labelled AssertionError with its original cause and last content-free fixture state. The original 10/15-second bounds, predicates, assertions, ownership barrier and protection rules are unchanged. Cleanup errors are suppressed onto the original body failure instead of replacing it.
Independent read-only review passed; individual and combined instrumentation Kotlin compilation passed at cad434d5caa5f9cd6b8571e1c4fd7574d69f01dd. Includes #54's reviewed equivalent diagnostics df9cd159f461d7b9c2d0f11263ded3e6ffe64356. This is test-only observability, not an established product fix or a passing acceptance result. One normal hosted diagnostic run will provide the next evidence; no unchanged/manual rerun or ADB action was performed.

## Handoff integration

Integrated main 2042306 and reviewed queue revision 5888db1 by ordinary merges.
The deletion implementation, action handling, ViewModel and device fixture are
unchanged from handed-off 888ca12. The serialized restored-queue baseline remains
intact; no per-event database comparison was introduced. That queue revision has
passed all five hosted checks, but #59 remains unmerged pending phone acceptance.

Deletion affects local files and persisted state, so the limited classification
change still requires preservation regressions, complete hosted emulator checks
and the dedicated offline S22 flow. Run both complete default and voice gates on
the combined revision; the historical default-only pass and old APKs do not
satisfy those gates. Build and verify new package identities, instrumentation
target, signatures and immutable hashes before any phone install. No current
phone acceptance has run, and required failures or unavailable checks block merge.

At 470ceed (identical reviewed tree to 27dcd10), both local default/voice gates
passed 99 core and 528 app tests each, lint, APK builds, 87 Python tests and all
security/data checks. The S22 run reached the final allowed deletion, but failed
because its confirmation button was absent from the semantics tree at the next
immediate query. The fixture now awaits that dialog under the existing 10-second
bound, then requires its title and enabled confirmation before one click; actual
file deletion and metadata/transcript preservation assertions remain unchanged.
This is synchronization/diagnostic improvement, not a confirmed product fix or a
passing phone result. The separate ADB cleanup connection failed with Winsock
10055; no additional device attempt or server restart is implicit in this change.
