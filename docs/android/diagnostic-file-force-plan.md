# Diagnostic producer fixtures and durable file-force isolation (#97)

State: active implementation; delegated owner issue90, root coordinating full
validation/integration. Branch `fix/97-producer-file-force`; worktree
`/home/andrepaim/src/distillpod-issue97-file-force`. Base refreshed main
`ee72a0b4538b2f44ee517c1d123ce1cd0361ca3c`. No PR yet. Retain `6bb4055`
clock-oracle and `93c6d84` observation ancestry by normal local integration.

## Confirmed evidence and boundaries

The source-unchanged default full gate at `6bb4055` failed ContextWaitProducerTest
line 49 during repeat(200): original five-second Future.get timeout. Its actual
instance worker was in native FileDescriptor.sync through OperationJournal
save/acknowledge and Diagnostics.flushSummaries. Executor active=1, queue=2,
tasks=140, completed=137, one queued Future not done/cancelled. The barrier was
pending behind durable persistence at that boundary. No full-queue/current
monitor-cycle diagnosis follows. A single sample does not establish how long
that sync took, host cause, or the cause of all historical #97 failures.
Original XML/snapshot/logs stay private; failed gates remain failed. #119's
clock fix is distinct and its focused pass does not waive this failure.

## Design and implementation plan, before edits

1. Retain exact failing evidence and normally integrate authorized prior
   ancestry into this fresh branch. Add a narrow per-instance internal
   DiagnosticFileForce dependency shared by the journal and outbox. The only
   production implementation invokes native fd.sync. Ordinary public
   Context/optional-DSN Diagnostics construction always selects it; explicit
   internal construction is the test seam. No mutable singleton setting,
   environment/build switch or no-op implementation in main.
2. Keep real files, exact serialization/schema/bytes, flush, close, rename,
   reopen, prune, outbox-before-ack ordering and directory errors. Preserve
   existing store-constructor call/trailing-lambda semantics. Add deterministic
   runtime-red regressions for force-boundary invocation, fully flushed bytes
   before rename, original failure/non-publication, both-store injection and
   controlled blocked-force/pending-barrier behavior before wiring the seam.
3. Wire only the exact existing fd.sync boundaries through the dependency.
   Test-only recording/no-physical-force implementations opt in explicitly at
   ContextWaitProducerTest, ContextSummaryOverloadTest, CaptureContextEvidenceTest
   and DiagnosticsTest's 11 barrier-bearing instances. Preserve producer
   iteration counts, all privacy/budget/context/correlation/outcome/persistence
   assertions, actual asynchronous execution, the five-second barriers and
   bounded probes. Do not broadly change unrelated tests or executor lifetimes.
4. Validate native default dispatch (including an invalid-descriptor sync
   failure), real default file/reopen/malformed/failed-commit behavior and
   force-before-rename order. A controlled gated force must show a pending
   barrier and original failure evidence, then release and completion with
   failure-preserving bounded cleanup. It is an expected synthetic timeout,
   not a claimed natural reproduction or historical fsync-duration measurement.
5. Run focused red then focused green/related producer/store/correlation/privacy
   checks under flock /tmp/distillpod-dot-full-build.lock, --max-workers=2, JDK17
   and the established SDK. Review final source, preserve exact receipts and
   hand a clean commit to root. No push, PR, main integration, devices or full
   retry by this delegation. Root owns full default/voice, hosted gates,
   current-main integration and successful merged-main verification.

## Risk and acceptance

This is an internal PRODUCTION-SOURCE testability refactor plus explicit JVM
fixture isolation, not a test-only label. Persistence ordering/durability must
remain unchanged for every ordinary app construction. No codec/schema or
existing file-format/migration change, lazy/background force, timeout inflation,
blanket retry, event-count reduction, main-idling assumption, runtime force
weakening, account/security/network/backend or release change. Injected fixtures
still exercise file content, atomic publication and error semantics, but do not
establish physical-media durability or a host-latency SLA. Real default storage
coverage stays enabled. Root reassesses final risk and applicable hosted/emulator
evidence; no physical-device protocol is introduced when native defaults and
platform behavior remain unchanged. No phone action is delegated.

Do not close #97 merely because a later run passes. State exactly which
producer-fixture dependency is isolated, which actual snapshot supports it, and
which historical/host-cause questions remain unproven. Unrelated CI packaging
IncrementalSplitterRunnable evidence remains separate and does not establish a
shared cause or justify a retry.

## Focused red/green checkpoint

The per-instance API scaffold was first deliberately left unwired. Seven new
regressions ran: five failed at missing journal/outbox/Diagnostics routing,
missing force exception, or the unreached acknowledgement gate; two native
constructor/real-storage controls passed. Original runtime-red reports, source
snapshots and receipts are retained privately. This was not a compile-only red.

After routing the existing two force boundaries and explicit fixture opt-ins,
one focused voice run passed 72 tests, zero failures/errors/skips, with source
unchanged. It included seven force/order/failure/default/gated regressions,
eight real-default journal tests, eleven real-default correlation tests, all
four known producer fixtures, the bounded probe and #119 timestamp regressions.
The controlled gate test preserved the original five-second TimeoutException,
queued Future and bounded snapshot while old committed bytes stayed unchanged
and the complete acknowledged temp record was blocked; release then allowed
publication and completion. Its passing duration is not a natural fsync timing
measurement. The gate is released before consent teardown, cleanup preserves
primary failures, and only its own executor is joined.

Source review independently confirmed all four existing producer fixture files
change only explicit per-instance force construction (1/1/1/11 opt-ins). Their
producer calls, loop counts, five-second barriers, progress behavior, probes
and assertions are byte-for-byte preserved. Both original store constructor
parameter orders/defaults and trailing-lambda bindings remain intact; named
internal factories carry the new dependency. The sole main implementation
remains native fd.sync, with no no-op/main switch. Ordinary public Context/DSN
construction always uses it. File formats, flush/force/close/rename ordering,
real reopen and failed-publication behavior remain covered.

The test-only recording force isolates physical media force for these semantic
producer fixtures; it does not remove all host I/O or prove every historical
#97 timeout has this cause. Existing real storage coverage remains on native
force. Root coordinates exact-head full default/voice, hosted checks and normal
integration/merged-main verification, including retained #119 and independently
owned CI observability ancestry. No full retry, device, push, PR, release or
main merge was performed by this delegation. Parent #97 remains open pending
its required evidence and validation.
