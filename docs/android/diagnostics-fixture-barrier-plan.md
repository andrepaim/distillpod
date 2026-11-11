# DiagnosticsTest barrier observation (#97)

State: active bounded observation increment. Root owns issue #97; delegated
implementation owner is issue90. Branch: `test/97-diagnostics-barrier-observation`;
worktree: `/home/andrepaim/src/distillpod-issue97-diagnostics`. Base: refreshed
`origin/main` at `ee72a0b4538b2f44ee517c1d123ce1cd0361ca3c`. No PR yet.

## Facts and limits

The bulk-audio voice gate at `de3f859` failed DiagnosticsTest's routine exhaustion
case at line 36, within the 1005-event loop, on Diagnostics.awaitIdle's original
five-second Future.get. One of 673 app tests failed. No loop iteration was
recorded. The suppressed queued-main-runnables warning does not prove a main
dependency or explain historical #97 failures. A late external snapshot found
60 diagnostic workers idle after failure, not at the timeout boundary. An
accidental attach to a non-Java process is excluded. The run's conservative
sourceUnchanged=false marker remains invalidated evidence; later clean source
inspection does not convert it into a successful gate. Original reports stay
private. No actual failing-worker snapshot or supported root cause is known.

## Plan before implementation

1. Install the existing DiagnosticBarrierProbe in DiagnosticsTest immediately
   after each barrier-bearing Diagnostics construction (11 of 12 instances),
   before consent/event calls can create its executor worker. Delegate each
   existing barrier through that same probe. The remaining instance has no
   awaitIdle call and is unchanged.
2. Preserve the original five-second direct barrier, producer ordering and
   count, assertions, privacy and original failure. Change no production code,
   main-looper progress, timeouts, retries, worker scheduling or teardown.
3. Run one bounded focused voice reproduction of DiagnosticsTest plus the
   existing controlled DiagnosticBarrierProbeTest, serialized under the shared
   Gradle lock with --max-workers=2. If it fails, retain original XML and bounded
   actual-worker snapshot before proposing a deterministic behavioral repair.
   If it passes, report no reproduction and no repair; do not repeat unchanged.
4. Review exact diff and record focused results/limitations, then hand off to
   root for any required integration/full gates/hosted validation. Do not merge,
   push, use devices or close parent #97 in this increment.

## Acceptance and risk

This is JVM-only test observation and documentation. Existing probe coverage
checks original-error preservation, worker/queue evidence and privacy. Its
observation takes effect only after a genuine barrier failure; existing success
behavior remains a single direct wait. Source review must confirm installation
precedes worker creation in this fixture, and all pre-existing assertions and
wait counts remain. Emulator/phone evidence is inapplicable to this test-only
change; repository hosted checks are still required before eventual integration.
No runtime, data, account, settings, network, backend, security, release or
production telemetry change. A passing focused run is not historical-cause or
timeout-repair evidence.

## Observation checkpoint

At clean `d78cfed6ea71539701e0a60f7f56a3b2edf6b668`, the existing probe covered
all 17 original DiagnosticsTest await call sites across 11 fixture instances.
Source reconstruction after removing probe declarations/delegations preserved
all prior statements, assertions and producer ordering. No main-loop progress,
retry, timeout, producer, teardown or production changes were made.

One focused voice run passed DiagnosticsTest's 14 cases and the existing
DiagnosticBarrierProbeTest's controlled privacy/original-error case: 15 tests,
zero failures/errors/skips, source unchanged. No natural timeout recurred and
no failing-worker snapshot was obtained. The narrower run does not reproduce
the full suite's fixture lifetimes/resource pressure. No root cause or timeout
repair is claimed; the original failed gate stays failed evidence, and parent
#97 remains open. Private XML/logs and a receipt are retained outside Git.

This increment awaits root review and coordination. Full default/voice, hosted
checks, current-main integration and merged-main verification have not run on
this branch; no PR, push, merge or device action was performed. Do not repeat
an unchanged run merely to force another outcome.
