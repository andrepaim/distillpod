# Diagnostic wall-clock evidence oracle (#119)

State: active implementation. Delegated owner: issue90, with root coordinating
full validation and integration. Branch: `fix/119-diagnostic-clock-evidence`;
worktree: `/home/andrepaim/src/distillpod-issue119-clock`. Base: refreshed
`origin/main` `ee72a0b4538b2f44ee517c1d123ce1cd0361ca3c`. No PR yet.

## Facts and scope

The full voice gate at `93c6d84` failed CaptureContextEvidenceTest's callback
scenario timestamp-order assertion: observation was 516 ms later than emission.
The existing context-readiness contract explicitly permits wall-clock ordering
changes. DistillationSummaryTest contains the same invalid inequality;
ContextObservationTimeTest also compares separate emission wall-clock samples. No host
clock mechanism, producer execution-order violation or production defect is
proven. This is distinct from #97: DiagnosticsTest passed its 14 cases in that
run; no timeout root cause or repair is claimed. Original failure/envelope
evidence stays private.

## Plan before implementation

1. Retain original reports privately. Normally merge the authorized published
   `93c6d84` observation branch into this branch, preserving PR #118 ancestry
   for one eventual coordinated PR. Keep #97 open. No push or main merge.
2. Extract the existing wall-time oracle into a shared src/test-only helper.
   Add actual contextSummary and attemptSummary producer regressions passing
   an explicit future observedAt through existing APIs, without machine-clock
   changes, runtime injection, timestamp clamping or retry. Run focused red
   and retain failures at the old relative-order assertion.
3. Correct the shared oracle to require both timestamp fields, numeric positive
   wall-clock samples, exact supplied observation preservation where known, and
   separately sampled emission. Require no relative wall-clock ordering. Use it
   in all three affected existing fixtures, retaining exact observations and
   elapsed/coalescing/count semantics as well as all privacy, context, budget,
   correlation, outcomes and persistence assertions. Verify invalid/missing
   evidence still fails.
4. Run focused green plus all three affected fixtures and existing probe coverage
   under the shared Gradle lock with --max-workers=2; retain exact revisions,
   red/green XML and limitations. Review/guard and hand off to root for both
   full profiles, required hosted checks, current-main and merged-main gates.

## Risk and validation

Strictly JVM-test oracle/helper/regression and plan changes. Weakening the
wrong ordering constraint must not erase evidence validation or content/privacy
assertions; deterministic producer tests and source review establish this.
Existing synthetic producer fields preserve runtime behavior. No production
clock, event, schema, API, data, settings, security/network, backend or release
change. No emulator or phone check applies to this test-only increment. Full
repository checks remain required before root integrates. Do not repeat an
unchanged full gate merely to obtain green, or close parent #97.

## Focused red/green checkpoint

The two new actual-producer regressions first failed at the extracted original
wall-order assertion, using a supplied observation in 2100 and ordinary
separately sampled emission. Neither a machine clock nor a runtime clock was
modified. The expected original-oracle failures and exact test-only source
snapshots are retained privately; they are not application timestamp failures.

After the correction, one focused voice run passed 43 tests: new wall-clock
regressions/invalid-evidence checks (3), CaptureContextEvidenceTest (9),
DistillationSummaryTest (15), DiagnosticsTest (14), DiagnosticBarrierProbeTest
(1), and ContextObservationTimeTest (1). Zero failures/errors/skips; source
was unchanged during each red/green run. The shared oracle now rejects missing,
non-numeric, non-finite and non-positive evidence. Exact supplied observations
and separately sampled emission remain checked. No ordering or inequality of
separate emission samples is required. All prior privacy, context, outcome,
correlation, budget, coalescing, elapsed and persistence checks remain.

Only JVM test files and this plan changed beyond the retained #97 observation
ancestry. The original 516 ms reversal remains valid evidence of the invalid
oracle; its clock mechanism is unknown. No #97 timeout cause or repair is
claimed. New fixtures retain the existing consent-revocation/Sentry-close
teardown pattern; executor-lifetime pressure is an unproven separate #97
hypothesis and this change does not repair it. No unchanged full retry,
production change, phone action, push or merge was performed. Root coordinates
required full profiles, hosted checks, integration and merged-main verification.
