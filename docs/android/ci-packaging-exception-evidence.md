# APK packaging exception evidence (#121)

The Android build job in run43022464598 at6bb4055 failed packageDebug inside
AGP IncrementalSplitterRunnable without its nested exception. This is distinct
from the local same-head diagnostic executor timeout: that local run reached
its app tests and retained a native journal-force stack. The hosted run stopped
at packaging before any app JVM result. No common cause, cache/resource defect
or application regression is established.

Root records this plan before the observation-only workflow edit. Add normal
Gradle --stacktrace output to the existing three Android build-validation
invocations. Keep tasks, profiles, cache settings, release-validation conditions,
artifact staging and all gates identical. Do not use broad info/debug logging.
Validate workflow syntax, existing routing/staging guards and whitespace, then
require all selected checks on the actual final combined commit. Preserve the
original failure; a later package pass verifies that head, not the historical
cause. Keep #121 open unless supported causal evidence and any needed repair
are established.

This small CI observation increment is folded into the active #97 validation
repair, with #119/118 ancestry retained, under one coordinated integration lane.
It does not create a separate active issue slot, waive any failed result, change
credentials/network/security settings, trigger publication, or authorize a new
release. Stack traces may reveal paths and public code frames; keep raw exports
private and retain the existing CI secret masking and artifact boundaries.
