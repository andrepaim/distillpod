# #84 controlled analysis lifecycle investigation harness

State: active investigation only, root owns #84 while #97 awaits exact merged-main
CI. Delegated harness owner issue90. Fresh branch
`test/84-analysis-lifecycle-investigation`, worktree
`/home/andrepaim/src/distillpod-issue84-investigation`, refreshed main
`c4fc9c1dd676e19c756fa18a10a303a52ac3af67` (tree identical to tested d3394bf).
No PR/push/merge lane, device or product fix is delegated.

The incident window is confirmed November 10 09:15–09:20 UTC. Root's bounded
Sentry reads found six playback-related logs, zero errors/spans, stalled
ingestion with completeThrough=null, and no supported join to the reported
summary attempt. Those observations do not establish a generation result or
original installed version. Source mapping and the exact published-code40
comparison remain private in source-map.md/source-comparison.json.

## Plan before harness code

1. Use only src/test investigation code and this plan. No production fixes,
   runtime injection API, network/account/settings changes or paid requests.
   Follow the existing isolated AppGraph pattern: cancelled graph startup scope,
   test-only context graph$delegate binding, synthetic Room/transcript rows,
   test-only understanding$delegate bound to a real EpisodeUnderstanding with
   held cancellable fake AiGateway and real AppJobs callbacks, then graph.ready.
2. Use actual AppJobs/UnderstandingWorker and WorkManager/TestDriver with real
   CONNECTED constraints; WorkerFactory constructs the actual worker and rejects
   unexpected worker classes. Never substitute a fake worker/provider fallback.
   A test dispatcher delegates unchanged to Dispatchers.Default and records the
   actual worker coroutine Jobs so cancellation cleanup can fully finish. Pinned
   WorkManager 2.10.1 creates a fresh Job in CoroutineWorker.startWork, so merely
   joining an injected parent scope is insufficient. No worker internals mutate.
3. Hold a synthetic one-part response while actual work and AnalysisEntity are
   running. Use real AppViewModel open/back, repeat the request, clear its
   ViewModelStore and await collector unwind while the worker remains pending.
   Release the provider and observe persisted success/summary/chapters, no
   duplicate provider request, and a replacement ViewModel observing the result.
4. Explicit pause is a negative control: while held, vm.pauseAnalysis must
   produce cancellation and a failed/manual-retry persisted state. Record actual
   provider cancellation and work/analysis states; no late successful result.
5. A controlled provider exception should separately classify WorkInfo versus
   persisted analysis outcome. If the repository stores failed while the worker
   succeeds, record it as source-consistent behavior, not a new screen defect.
   Optionally seed running/queued analysis rows to observe recoverInterrupted
   policy without killing a process or claiming OEM restart validation.
6. Each first execution classifies behavior, not TDD for a product fix. Preserve
   failed harness results; make routine fixture corrections only with the cause
   and changed evidence explicit. If a real product defect is exposed, stop at
   evidence and report for a separately authorized fix/ticket. No unchanged
   full reruns to obtain green. Run only this focused harness under the shared
   Gradle flock, JDK17/SDK and --max-workers=2.
7. Bound every wait; release fake gates before cleanup cancellation; await all
   actual worker coroutine Jobs, fake calls and ViewModel collector completion
   before closing Room/WorkManager databases. Restore test application binding;
   preserve primary errors with suppressed cleanup errors. Commit local test/plan
   only for revision-bound evidence; no push or PR. Hand off private results.

## Risk / interpretation

This is JVM controlled investigation code only, not a production behavior change.
Fake public-content data/providers and isolated startup exclude live accounts,
paid requests, production phone data and settings changes. No emulator/phone
action is delegated. Configuration delegates worker scheduling to its ordinary
Default dispatcher; observation of Jobs is test-only. Navigation/ViewModel
clearing does not itself establish Activity recreation, process death, real
background quota, locking or OEM behavior. UI Create-button/account-gating is
not exercised by a ViewModel-level accepted-request experiment. Never infer the
user's original outcome from these controls or missing telemetry. Root retains
required investigation acceptance and any later validation/integration decision.

## Initial observation and fixture correction

Initial local revision `b488062` ran four controls successfully. It recorded
real worker/provider/persisted-state behavior, but the configured worker Job
recorder saw zero Jobs. Its all-completed check was therefore vacuous and is
not accepted as full worker-unwind evidence. Provider Jobs were captured and
completed; original observations/XML/receipt remain private with this limit.

Pinned WorkManager 2.10.1 inspection showed legacy test initialization clones
Configuration when its task executor is default; the pinned copy constructor
does not copy workerCoroutineContext. The corrected harness explicitly selects
SynchronousExecutor/task executor and PRESERVE_EXECUTORS, asserts the configured
worker dispatcher survives initialization, and requires nonempty actual-worker
Job capture before closing databases. The dispatcher still delegates worker
scheduling to Default. An added control holds graph.ready before provider entry
and cancels the actual worker, proving completion observation does not depend
on entering the fake provider. This is a routine investigation-fixture change,
not a product behavior fix or unchanged retry.

## Corrected controlled findings

One corrected focused default-profile run at clean
`7ce7ee51ffa27c91215d89d7025494ae075ac273` passed five cases, zero
failures/errors/skips, source unchanged. Actual worker Jobs were non-empty
in every case (navigation 3, explicit pause 2, provider failure 2, seeded
recovery 1, pre-provider cancellation 1), and all recorded worker/provider
Jobs and ViewModel collectors completed before either database closed.

- During the held one-part provider call, real ViewModel open/back navigation,
  two additional analyze requests and clearing the original ViewModelStore
  left accepted actual UnderstandingWorker work running. After release, the
  summary/chapter result persisted and a replacement ViewModel observed it.
  Three work requests completed, but only one provider call occurred. This
  tests the entry point, not literal taps on an account-gated Compose button.
- Explicit pause cancelled the real worker and provider, persisted failed
  manual-retry state and retained the ready transcript. A separate control
  cancelled an actual worker held on graph.ready before any provider entry;
  its worker Job was observed/completed despite zero provider Jobs/calls.
- A synthetic provider IOException produced WorkInfo SUCCEEDED while the
  AnalysisEntity was failed with an error and no summary. This confirms the
  source distinction; it is not a newly established navigation defect.
- Seeded running-analysis recovery marked it failed, retained its checkpoint
  and enqueued no automatic provider work. A seeded queued analysis scheduled
  and completed. This is persisted-state policy, not an actual process kill.

No product defect was reproduced in these controlled cases, and no production
behavior was changed. The original attempt remains unjoined to available
telemetry. Activity recreation, real background/lock/OEM constraints, actual
process death, a live provider and UI-button/account gating were not tested.
A successful controlled run is not proof the reported incident succeeded or
that every lifecycle condition is supported. Root owns the remaining #84
investigation and any evidence-based follow-up ticket; no product fix is
authorized by this investigation. Original and corrected receipts/observations
remain private. No push, PR, merge, release or device action was performed.
