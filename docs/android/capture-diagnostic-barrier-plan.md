# Capture diagnostic fixture barriers (#97)

Owner: delegated issue-handling agent `issue90`, coordinated by the root session.
Branch: `test/97-capture-barrier-progress`; worktree:
`/home/andrepaim/src/distillpod-issue97-capture`. Base: current `origin/main`,
`c35904c1a051e39d3cf819b9ae8162e12f32bdcd`. No PR yet.

## Evidence and scope

The voice gate at playback ownership revision `b652996` failed one of 640 app
tests: CaptureContextEvidenceTest's first Diagnostics.awaitIdle barrier at line
73 timed out after five seconds. Its default gate passed. The original failure
and pending-main warning are retained privately; there is no failure-time worker
snapshot. These facts do not prove a main-looper deadlock, a production defect,
or the cause of historical ContextSummaryOverloadTest/ContextWaitProducerTest
failures recorded in #97. The older observation-only increment, PR #100, remains
valid; this follow-up must not close the parent without its remaining evidence.

## Plan before implementation

1. Install the existing bounded DiagnosticBarrierProbe before this fixture can
   create its worker; preserve producer behavior, privacy/content/truth assertions,
   original exception, and the original five-second executor barrier.
2. Run one bounded focused or original-profile reproduction with that observation.
   Capture and interpret real evidence if it fails; a passing reproduction is a
   limitation, not a repair. Do not repeat unchanged to obtain green.
3. Establish any proposed capture-specific defect with a controlled failing
   regression before changing fixture behavior. If supported, share the existing
   main-progress strategy without claiming it explains historical #97 failures.
   Preserve asynchronous producer execution and bounded waits; no retries or
   timeout inflation. Keep diagnostics snapshots safe and preserve original errors.
4. Review the resulting diff, run relevant focused regressions and the default and
   voice local gates, and record exact revisions/results/limitations. Coordinate
   current-main integration, hosted checks, normal merge and merged-main checks
   with root. Keep #97 open if original remaining acceptance is not established.

## Acceptance and risks

Only JVM fixtures/helpers and documentation may change. Required evidence covers
successful asynchronous barriers, controlled queued-main dependencies, original
failure preservation, bounded diagnostic snapshots, and unchanged privacy and
context-selection assertions. A structural fixture fix must be distinguished
from an unproven explanation of the historical failures.

Risk: test progress could hide a real executor problem or leak a waiting worker.
Retain the real five-second barrier and evidence, propagate failures unchanged,
and join/cancel helper work before fixture teardown. Serialize Gradle with
`flock /tmp/distillpod-dot-full-build.lock` and `--max-workers=2`.
No emulator/phone acceptance is necessary while scope remains strictly JVM test
infrastructure; hosted repository checks still apply. No production behavior,
data, settings, backend, credentials, release metadata or publication changes.

## Observation-only checkpoint

At `f90ce6f6eb5643168a39bb6c7b7bd3cafebbb9a0`, the existing probe was installed
immediately after Diagnostics construction, before this fixture's worker can be
created. Both direct five-second waits now retain bounded failure evidence. No
producer, assertion, timeout or progress behavior changed.

One focused voice-profile reproduction ran CaptureContextEvidenceTest (nine
tests) and DiagnosticBarrierProbeTest (one test): ten passed, none skipped. No
natural failure recurred, so no real failing worker snapshot or supported cause
was obtained. The earlier failure remains failed evidence. Do not infer a timing
repair from this later pass, repeat unchanged to obtain another outcome, or mark
parent #97 complete. Private original and reproduction reports are retained by
the coordinating session. Full default/voice and hosted checks remain pending.
