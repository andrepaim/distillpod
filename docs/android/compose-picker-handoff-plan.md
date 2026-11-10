Resuming #74 as the one active implementation issue; #75/PR79 is waiting on this test-harness blocker. Current577996f hosted run42688318922 ran92ordinary cases and failed only ManagedBackupsDeviceTest.olderJsonWithAnUnexpectedMimeTypeIsVisibleAndImportsFromTheNewScreen at its first legacy-export picker wait (line160). Active package remained the app; no picker appeared. All queue cases passed. The same backup case passed earlier, so no production backup defect or exact original cause is established.

Plan before implementation: use a new branch/worktree from current origin/main; preserve existing worktrees and the reviewed queued dispatcher. Add a deterministic emulator regression that completes a suspended Compose UI effect from a background thread after the first idle synchronization, then waits for the actual system document picker. First reproduce the single-drain handoff failure; then drive queued app work while awaiting external UI rather than adding a settling sleep. Make legacy button visibility/enabled and asynchronous preparation explicit; retain original picker/import/data assertions and uncaught failures. No production code, schema, release metadata or phone settings changes. Emulator-only picker fixtures must not run on the owner phone.

Validate relevant unit/lint/APK checks and affected Compose/backup cases; integrate the test-only follow-up into PR79 as a separate reviewed commit so its exact combined head gets all5hosted checks. #74 and #75 remain open until normal integration and successful combined-main CI. #75 current-runtime S22 five-case acceptance passed at577996f, ownpair removed and productionmetadata unchanged; reassess evidence after integration. No release authorization.

Deterministic red established on API35: complete the background gate only after
the first idle drain; the single-drain implementation never launched DocumentsUI
and failed its original picker assertion (16.453seconds, receipt60a66d75fa36).
The correction polls for the real picker while driving queued app work each time,
with the existing15second launch bound. Original location, save, import and data
assertions remain. The older-JSON case also verifies its button is visible/enabled
and observes successful asynchronous snapshot preparation before entering the
system-picker boundary. These are test-only changes; no production backup change
or assertion that this is the sole cause of the historical CI failure.

Green evidence: focused ComposeMainThread unit regression, lint and both APK
builds passed. The API35 group passed7cases (4real Compose clock/lifecycle
checks, listening UI, and both managed-backup scenarios), receipt5e9af091df7a.
The actual picker launched once on Android main after the controlled late
completion. No save/import/data assertion was removed. Final combined-head
hosted checks and main CI remain outstanding; this follow-up is not complete
merely because its branch checks pass.
