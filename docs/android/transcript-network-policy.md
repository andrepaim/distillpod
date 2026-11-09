# Queued transcript network policy

## Scope and acceptance

A Wi-Fi-only toggle must update every unfinished recovery request for the current
transcript revision, even when a capture or completed checkpoint changes the next
chunk. It must not rewrite another revision's input, revive cancelled work, lose
saved text/coverage, or interrupt an active transcript chunk. No release metadata,
Room schema, foreground scheduling policy, or phone installation is changed.

Implementation plan: reproduce the priority regression, identify requests by an
explicit revision tag, migrate unidentifiable legacy requests safely, and validate
checkpoint, cancellation, revision-isolation and active-writer races. Use public
WorkManager APIs only. Finish with local checks, review and isolated-emulator CI;
physical-device acceptance remains required before merge. The resumed issue
workflow authorizes non-destructive checks using the isolated validation app.

## Risk assessment

This changes queued WorkManager constraints and cancellation/recovery ordering,
including migration of pre-existing requests. Platform scheduling and saved
transcript/checkpoint preservation make physical-device validation necessary;
this is not a low-risk settings-layout change. No Room schema or installed-app
identity changes are introduced. The one-time legacy request-identity tradeoff
below remains an explicit compatibility risk.

Required evidence is the full relevant unit suite (including pause and revision
races after integration), lint/build checks, isolated emulator acceptance, and
the three synthetic network-policy cases on the owner's phone using a separate
validation package. The phone checks cover actual WorkManager records, Settings
updates and one loopback transfer while preserving the production installation.
They do not establish carrier billing, long-duration power/OEM scheduling, live
model downloads or a release-signed in-place upgrade. Those flows are not changed
or claimed as validated by this fix. Reassess if integration changes these risks.

## Identity and upgrade behavior

New recovery requests carry both the existing episode tag and a revision tag.
Policy reconciliation updates all unfinished requests with the current revision
tag in place, retaining their IDs, initial delay and non-network constraints.
WorkManager applies updated constraints to subsequent attempts; a running attempt
finishes under its original constraints. Superseded tagged requests are never
rewritten as the current revision.

Legacy requests have only the episode tag. Public `WorkInfo` does not expose
original input data or unique names, and enumerating chunk names from today's
duration misses requests created before duration shrinkage. Consequently the
first policy reconciliation with unfinished legacy work waits for the repository's
active chunk to finish, then cancels legacy requests and, if needed, enqueues one
recovery request from the current Room revision and saved checkpoints.

This is a deliberate one-time compatibility tradeoff: pending legacy request IDs,
retry/backoff history and initial delays are replaced, using current charging,
battery and network policy. Saved transcript text and coverage are unchanged.
Already-cancelled requests do not trigger recovery. If modern recovery already
exists, migration does not duplicate it. A Settings operation can wait for an
active chunk during this first migration; because AppJobs serializes policy
changes, new audio/model enqueue calls can also wait. Modern-only updates do not
wait on transcript processing.

## Lock order and cancellation

The legacy barrier acquires `processing` before `controls` before `scheduling`.
`process` already holds `processing` while taking `controls` or `scheduling`.
Pause takes `controls` and cancels the active job without waiting for `processing`.
Preparation takes `controls` then `scheduling`. Modern policy reconciliation takes
`controls` then `scheduling`, without acquiring `processing`.

The barrier prevents a worker from becoming a repository writer between the
WorkInfo snapshot and cancellation, including a WorkManager RUNNING worker still
waiting for processing. Cancellation operations complete before recovery enqueue.
Rows are read under controls after the barrier, so a pause while waiting remains
paused. Processing's final automation kick only enqueues work asynchronously; it
does not await AppJobs' download-controls mutex. Transcription downloads call
MediaRepository directly, so holding AppJobs download controls while waiting for
the chunk does not create an inverse lock dependency.

## Validation

The original capture-priority regression failed on unchanged production code:
expected UNMETERED, actual CONNECTED. Regression coverage includes priority and
checkpoint movement, both policy directions, current-revision isolation,
cancelled work, duration-shrunk legacy requests, checkpoint/text preservation,
an active chunk finishing before migration, and pause with a RUNNING worker
waiting behind processing. Device acceptance separates modern identity/delay
preservation from legacy migration expectations. Exact run results and commit
are recorded in the draft PR. Physical-phone results must be recorded against the integrated revision before merge.
