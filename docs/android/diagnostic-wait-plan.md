# Diagnostics executor wait investigation (#97)

Main base: `02f124b9535602a9e3914fdaa84b8f84267ca5a2`. #89 is parked with its
unpublished cache work preserved. This is the session's only active issue.

## Confirmed evidence and remaining gap

The uncached Android benchmark failed ContextSummaryOverloadTest and
ContextWaitProducerTest at their first five-second diagnostics executor barriers.
The flood case already had the main-progress helper from PR #96. Pending-main
warnings and nearby CloseGuard warnings do not establish the cause. The original
attempt has no worker/queue snapshot. Its logs/XML remain private.

With new test-only evidence installed, default and voice local gates each passed
99 core and 578 app tests, lint and APK assembly. A fresh experimental clone then
matched the original public source/resource markers, fixture assets, disabled
cache, fresh outputs and --info logging; that reproduction passed too. These are
observations of later successful runs, not proof the historical cause was fixed.
Do not repeat unchanged runs merely to obtain a failure or a green result.

## Increment 1: bounded observation, not a repair

Track each fixture's own diagnostics worker by delegating its existing thread
factory and retaining the returned Thread. Preserve the existing executor,
producer calls, progress behavior, five-second barrier and assertions. If the
barrier fails, attach a suppressed bounded snapshot: executor/queue counts,
actual worker/main state, lock-owner links and at most 12 threads/32 frames each.
Use public code frames and JVM-local monitor identities only; never emit thread
names, queued content, URLs, messages, credentials or operation/installation IDs.
Preserve the original exception even if snapshot collection fails. JVM management
inspection stays in src/test and is reflected because Android's compile
bootclasspath omits java.management; no production hook/dependency is introduced.

A controlled gated-worker test first failed without attached evidence, then
passed with the expected worker/queue state and the original exception unchanged.
The actual failure remains unreproduced with this observation installed.

Review and validate this independently useful increment through exact-head local
checks, complete hosted acceptance, normal current-main integration/merge and
combined-main CI. No phone action is applicable to JVM-only fixtures. Keep #97
open after this increment: the next actual barrier failure should supply the
snapshot needed to select a deterministic regression and smallest supported
repair. No blanket retry, timeout increase, assertion removal, runtime telemetry,
account/settings/protection, backend, paid-service or release change.
