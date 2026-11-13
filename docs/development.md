# Build and test DistillPod

The native Gradle project lives at the repository root. Historical reports under `docs/android` retain commands and paths from the earlier `android/` subdirectory; use the root-level paths below for current work.

## Choose validation for the change

Follow the [risk-based validation policy](../AGENTS.md#risk-based-validation) for
each change. Record a short assessment in the plan and PR: affected behavior,
hardware/platform and upgrade/data risks, selected checks and their results,
tested revision, environment/device, and remaining limitations. Explain why
physical-device validation is or is not required; revisit the assessment when
the scope or integrated code changes.

Relevant automated checks, review, integration with current `origin/main`, and
applicable emulator acceptance remain merge requirements. Select tests for the
affected behavior and regressions using the commands below; existing CI still
runs unchanged. Pure business logic, low-risk UI organization, and content-free
reporting can use suitable automated/emulator evidence when the assessment
supports it. These examples are not blanket exemptions for an issue or area.

Physical-device checks are required for affected behavior that depends on real
hardware, OEM/platform differences, or conditions an emulator cannot reliably
establish: Bluetooth/headset/audio routing, background execution and
battery/power behavior, runtime permissions and notification/system Settings,
manufacturer-specific behavior, and relevant in-place upgrade/data-preservation
risks. Keep feature-specific physical acceptance protocols. Synthetic key
events do not prove actual accessory gestures. Emulator or mocked permission
fixtures do not establish manufacturer-specific phone behavior; real-phone
tests establish only the exact platform flows exercised, with their limitations
recorded.
A reporting change that affects background work or persisted data needs those
risks assessed too.

Use compatible, non-destructive owner-phone validation only within task
authorization. Preserve signing/upgrade compatibility and owner data; never run
emulator-only fixtures on the phone. Required failed or unavailable checks are
blockers, not waivers: record the next action and leave affected work unmerged.
No security, privacy, live-account, production-data, deployment, or release
protection is relaxed by this choice of validation.

Documentation-only changes use the existing exception: review accuracy and local
links and run `git diff --check`; no local Android build or device run is needed
unless build or app behavior also changes. Record that limitation explicitly.
CI and combined-commit verification still follow repository workflows.

## Bounded issue overlap

Follow the [issue loop](../AGENTS.md#default-issue-handling-loop) and
[parallel-development safeguards](../AGENTS.md#parallel-development). Editing
this policy does not authorize a new issue run. Sequential work remains the
fallback whenever safe overlap cannot be established.

A session may own at most two issues: one under
active implementation and one independent issue awaiting remote CI. Do not
start another issue merely because work is blocked, ready to merge, or handed
to a worker. Claim before implementation and refresh dependencies and other
sessions' ownership before selecting work. Separate branches/worktrees start
from current `origin/main`; dependent work cannot borrow stale validation from
another branch or be treated as independent.

For each owned issue, keep a tracker checkpoint with owner, state,
branch/worktree, exact tested revision, PR link (or no PR yet), completed and
pending validation, and the next action. Use these states:

- **Active implementation:** planning, editing, reviewing or local validation.
  Only one issue may occupy this state per session.
- **Awaiting CI:** remote checks are pending on the recorded PR head or merged
  revision. A second independent eligible issue may be implemented while waiting.
- **Ready to merge:** required branch checks and applicable device evidence have
  passed. Enter the coordinated integration lane before starting further work;
  passing an old base is not permission to merge.
- **Blocked:** a concrete dependency, permission, device or failed validation gate
  prevents progress. Preserve edits and evidence, state the next action, and
  release ownership explicitly before replacing this issue with other work.
- **Complete:** acceptance criteria, review, current-main integration, applicable
  device validation, PR merge and successful combined-commit CI are recorded.
  Neither awaiting CI nor a merged PR awaiting its checks is complete.

Serialize shared local Gradle, emulator and phone resources across sessions,
recording the current holder and handoff. Do not run builds in another session's
worktree or interrupt its tests. One session at a time holds the merge/integration
lane from current-main reconciliation through required combined-main CI. Before
merging, fetch main, integrate intervening changes, review the combined diff and
rerun the checks relevant to that exact result. Respect normal branch protection;
this policy does not enable a GitHub merge queue or bypass any gate.

Failed CI or a main regression takes priority. Park the second issue with its
worktree and edits intact, record its revision and next step, and focus on the
failure or coordinate with its owner. Preserve failed evidence; do not retry
unchanged to obtain green or weaken assertions. If repair needs a separate issue,
release a parked claim first so the two-issue limit is retained. Resume only after
rechecking ownership, dependencies, main and exact-revision validation. On session
interruption, leave both checkpoints and resource ownership clear; the resuming
session must verify current claims and CI rather than trusting prior status.
Report incomplete issues and blockers when stopping.

### Policy walkthroughs

1. **Independent issues:** A awaits PR CI on revision A1. Claim independent B in a
   separate current-main worktree and implement B while resources are available.
   When A becomes ready, checkpoint B and integrate A in the single lane. Keep A
   awaiting CI until combined-main checks pass; B must then reconcile that main.
2. **Dependent issues:** B requires A's behavior or measurements. B is not an
   independent second issue. Complete A and recheck B's readiness before claiming
   it under this policy; a branch based on unmerged A does not satisfy this rule.
3. **Main advances:** Another session merges C while A's branch checks run. A1's
   green checks do not validate A plus C. Integrate current main, reassess risk,
   review and rerun relevant checks on the combined revision before merging A.
4. **CI fails:** A fails while B is being implemented. Preserve B's changes and
   checkpoint it, then diagnose A using the failed evidence. Do not start C or
   integrate B around an unresolved main regression. Release a parked claim before
   claiming a separate repair issue, and retain the original acceptance gates.
5. **Device unavailable:** A requires physical validation that cannot succeed.
   Mark A blocked, record the device/scenario and next action, preserve its branch
   and release ownership when handing it off. Do not mark it awaiting CI or merge
   with emulator-only evidence. Continue only with eligible independent work.
6. **Session interruption:** A is merged but its CI is pending, and B has edits.
   Record A's merged revision/check link and B's worktree/revision/remaining work.
   On resume, check actual CI and ownership, coordinate any held resources, and
   refresh main before proceeding. A is not complete merely because it merged.

All release authorization, data-preservation, security/privacy and physical-device
requirements remain unchanged. Documentation-only policy changes use accuracy,
local-link and diff checks; they do not require Android or phone execution.

## Tools

- JDK 17
- Android SDK platform 35 and Build Tools 35.0.0
- NDK 27.2.12479018
- Android SDK CMake 3.22.1
- Python 3 for test-result and release validation scripts
- A development emulator for device tests

Clone recursively, or run `git submodule update --init --recursive` after cloning. The whisper.cpp revision is recorded in Git; do not replace it with an arbitrary newer checkout. Point `ANDROID_HOME` to your SDK, or create an ignored `local.properties` with `sdk.dir=/path/to/sdk`.

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Each developer/CI runner normally has its own debug key. Use signed release assets for upgrades to the personal-alpha installation.

## Native bridge checks

With a host C++ toolchain and CMake 3.22.1 or later:

```sh
cmake -S app/src/main/cpp -B /tmp/distillpod-native -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/distillpod-native --parallel 2
ctest --test-dir /tmp/distillpod-native --output-on-failure
```

These validate native inputs and lifecycle. Public-speech inference is covered by the emulator suite, using verified model fixtures. For deliberate host inference after preparing the fixtures below:

```sh
/tmp/distillpod-native/transcriber_test /tmp/distillpod-fixtures/ggml-tiny.en.bin /tmp/distillpod-fixtures/jfk.wav
```

## Isolated emulator acceptance

Start and unlock a development x86_64 emulator. The script installs fixture/main test APKs and manipulates test clips. Do not point it at the owner's daily-use phone.

```sh
scripts/prepare-fixtures.sh /tmp/distillpod-fixtures
DISTILLPOD_SERIAL=emulator-5554 scripts/emulator-tests.sh /tmp/distillpod-fixtures
```

Fixtures are fetched from the upstream public model repository and verified by SHA-256 before use; model/audio binaries are excluded from Git. The script also copies samples from the pinned Whisper submodule. Set `DISTILLPOD_ADB` and `DISTILLPOD_ADB_PORT` when using Windows ADB from WSL.

Ordinary CI excludes live-account, opt-in public-directory, and long public-podcast/battery/hour tests. Deliberate live tests require their explicit instrumentation arguments and task authorization. Never upload tokens, generated account content, personal recordings, subscription lists or owner screenshots as CI artifacts.

On disposable GitHub-hosted emulators only, the fixture script supplies Chromium's test command-line flags to bypass first-run onboarding before the real browser callback check. It verifies that the target is an emulator and removes its flags/debug-app setting on exit. This setup is never applied to an owner's phone. Lazy-list UI checks scroll the containing list before accessing rows that may not yet be composed on smaller screens. Where a row's lazy-list key is known, scroll with `performScrollToKey` rather than `performScrollToNode`: the latter scrolls by deltas, which schedule lazy-list prefetch; prefetch work deferred to a later frame escapes Compose's idle synchronization, and the next semantics read on the instrumentation thread can race that main-thread composition (#197, Compose foundation 1.7.x). Older tests still use `performScrollToNode` and carry this exposure.

Disposable GitHub-hosted x86_64 acceptance opts into `emulator-abi.init.gradle`
for startup, ordinary and voice builds. It builds/packages only the emulator's
native ABI, avoiding repeated ARM compilation and installation payload there.
The separate default/voice Android build validation and distribution build retain
both `arm64-v8a` and `x86_64`. The init script is inert without the explicit CI
opt-in and rejects other ABIs, non-hosted/physical targets, release tasks and
unexpected default filters. With the shared build resource held, run
`python3 scripts/verify_emulator_abi.py` for isolated Gradle-model regressions.
Cloud acceptance continues using the ordinary phase's compatible APK pair;
startup has a separate offline identity and voice has different resources and
manifest settings, so those pairs cannot be substituted for one another.

CI also waits up to 30 seconds for user storage to become RUNNING_UNLOCKED after the emulator reports boot completion. A development phone still requires its owner to unlock it; the script does not dismiss a secure keyguard.

The browser integration check validates receipt of a real browser's callback and its state/client identity. A separate Android TCP check reads the complete HTTP response while the listener scope remains open. This tests callback delivery and response completion independently of Chrome's accessibility representation of a plain-text page.

CI validates both the default-disabled and opt-in voice build configurations. After ordinary/cloud emulator acceptance, `scripts/voice-device-tests.sh /tmp/distillpod-fixtures` builds with `distillpodVoiceEnabled=true` and runs only the synthetic headphone-key/foreground-service and isolated Keystore tests. The runner refuses physical phones; it uses no live provider credential or paid request. The voice-only device test is excluded from default-disabled acceptance.

## Firebase acceptance

The optional sync implementation on `main` has isolated Firebase protocol/security and Storage suites. Use Node 22 and Java 21 for these suites (keep Java 17 for Android):

```sh
npm ci --prefix firebase --ignore-scripts --no-audit --no-fund
npm --prefix firebase run test:emulator
npm --prefix firebase run test:storage
```

They use `demo-distillpod-sync`, with Firestore on localhost:8085 and Storage on localhost:9199, and need no cloud credentials. See [Firebase test setup](../firebase/README.md) and the [implementation plan](android/firebase-sync-telemetry-plan.md). Do not run concurrent Gradle builds against the same checkout.

The [library sync guide](android/subscription-sync.md) documents optional client configuration, Auth/Firestore/Storage Android acceptance, and the two-phase force-stop test. `scripts/sync-device-tests.sh` requires running cloud emulators and a verified Android emulator; it refuses physical-phone targets. The default build intentionally contains no production Firebase configuration. The sync runner uses ADB reverse for localhost Auth/Firestore/Storage endpoints, so acceptance does not depend on the emulator’s Wi-Fi route; it removes those forwards on exit. CI runs the ordinary device suite first and the explicit cloud acceptance afterward. `IsolatedTestRunner` disables automatic production cloud activity and deferred fixture intents; SDK cases inject demo clients explicitly.

## Reducing repeated CI work

Keep related implementation corrections, tests and documentation in one coherent
PR. Push work in progress as a draft and consolidate changes before requesting
full hosted validation. Before marking ready, finish relevant local checks,
reconcile current main, review the combined diff, and coordinate the integration
lane with other sessions. This does not reserve the lane during exploratory
implementation or change the two-issue overlap limit. Record validation results
in the tracker/PR; add a separate documentation commit only when repository
content actually needs updating.

Direct draft code PRs run routing regressions and repository security, omitting
Android/Firebase/native/emulator jobs. Their aggregate deliberately fails with
"Draft preview is incomplete"; this is expected deferred validation, not a test
failure or merge evidence. Marking ready for review triggers full CI without a
new commit. Further ready PR pushes run full checks. Converting back to draft
starts a preview and cancels superseded CI through the existing concurrency
group. Documentation-only drafts still run their validated documentation route.
Manual and reusable/release runs retain full validation. Main merge pushes may
reuse verified identical-tree evidence as described below. Never use a
draft result or an older ready result to satisfy final-revision acceptance.

The emulator job records startup, ordinary (including teardown, Android Auto and
notification), cloud and voice phase durations and exit codes in
`/tmp/distillpod-ci-phase-timings.jsonl`, uploaded with emulator results. Child
output streams normally; the timing file contains no command arguments or app
content. Each phase includes its existing builds/setup/cleanup. Failures retain
the command status and stop later phases; no automatic retry is added. Abrupt
runner termination may leave the current phase unrecorded. Compare phase and
whole-workflow time across comparable revisions/cache states before claiming a
speedup; parallel runner minutes and elapsed waiting time measure different costs.

On a failed phase, explicitly enabled GitHub-hosted emulator CI also writes
`/tmp/distillpod-ci-failure-state.jsonl`. It contains only the fixed phase name,
allowlisted numeric host memory/disk/aggregate OOM counters, emulator/ADB process
counts (including zombies), and a bounded ADB transport-state category. It never
records process arguments, raw ADB output, logcat, app content or target serials.
The ADB read is restricted to the configured `emulator-PORT` target and times out
after two seconds; process inspection is capped at 4,096 entries. No physical
phone or non-hosted run enables these probes. Diagnostic failure does not replace
the phase's original failed exit status. Runner termination may prevent collection.
Host counters are cumulative and a single failure snapshot cannot establish OOM,
crash causality, or a shared cause with an earlier packaging failure.

## Identical merged-tree validation

A direct single-merge push to main may reuse a successful full PR run created
within the preceding 24 hours. The proof must match both merge parents, the
whole Git tree (including workflow/scripts/submodule identities), tested synthetic
merge checkout and workflow blob. Read-only GitHub API evidence must identify
the same repository and PR workflow, its latest matching run and successful
latest attempt and every
expected successful job. Only full direct PR validation emits the bounded
`ci-validation-proof-ATTEMPT` artifact; docs, drafts and reused runs never do.
Artifacts expire after seven days; the shorter 24-hour run freshness limit also
applies. Fork results, squash/rebase merges, batched pushes and ambiguous or
missing evidence conservatively run the full suite.

The main classifier and aggregate independently read the evidence. A lookup
failure in selection falls back to full validation; a verification failure after
jobs have been omitted fails the aggregate rather than passing. Lookups are
bounded to five matching runs, one complete page of up to 100 jobs/artifacts per
candidate, and 45 seconds per lookup. Reuse also requires a successful full main
Android-build and emulator execution within 24 hours among the last five
successful main runs. Otherwise main performs full validation and refreshes its
shared Gradle caches; repeated lightweight merges cannot indefinitely starve cache
writes. This is a bounded eligibility rule, not a new scheduled workflow. Truncated evidence cannot authorize reuse.
Existing required context names and independent main security remain. Main CI
logs the source proof run; the combined-main result must still pass before an
issue is complete. Reusable release callers explicitly retain read-only Actions
permission and always perform full validation, including distribution builds.

This avoids duplicate execution of equivalent code; it does not rerun tests on
the new main VM or establish that intermittent failures vanished. The 24-hour
bound limits environmental drift, but hosted images and external infrastructure
can still change. Use a manual full run when fresh environment evidence is needed
and investigate genuine failures rather than reusing or retrying them as green.


## Workflows

`security.yml` scans fetched Git history for secrets and checks current/new
commits for personal data containers and unreviewed binary media. See
[repository security](security.md) for local checks and asset-review requirements.

`ci.yml` runs Kotlin tests, lint/APK assembly, release-tool guards, host native validation and emulator acceptance on main pushes and pull requests. Its reusable form also gates the release workflow.

Main pushes that change only Markdown, documentation screenshots/videos under
`docs/`, historical JSON/JSONL/TXT reports under `docs/android/evidence/`, or
the Manrope font license do not start Android CI. Mixed pushes still run when
GitHub evaluates any non-documentation path. The filter deliberately does not
ignore entire directories: Kotlin, Java, C++, scripts, HTML/CSS/JavaScript,
workflow YAML, Gradle files, app resources, Firebase rules/configuration, and
submodule changes remain eligible, including code added under `docs/`.
Pull requests always start the workflow, then `scripts/ci_validation.py` reads
its complete Git base/head diff. A narrow allowlist recognizes known root
Markdown, Markdown/PNG/MP4 under `docs/`, historical JSON/JSONL/TXT evidence,
the Firebase/investigation README files and the Manrope license. Other paths,
including plugin/integration content, app resources, code under `docs/`, scripts,
workflows, executable files, symlinks and submodules select full validation for
ready PRs; direct draft code PRs use the incomplete preview described above.
Both sides of renames/deletions are inspected without GitHub's 300-file API
limit. Missing, shallow or ambiguous Git evidence fails visibly.

Documentation-only PRs run the routing/gate regressions, incremental local-link
and Markdown-anchor checks, whitespace checks and repository security. Link
checks cover changed Markdown sources and unchanged inbound links to changed or
deleted targets; existing unrelated historical link failures are outside that
incremental scope. Conventional inline/reference/image and HTML links are
supported; external URLs are not fetched and non-Markdown fragments are not
validated. Targets cannot escape the repository. Android/Firebase/native/
emulator jobs are intentionally omitted only for this validated route.

The existing required `Kotlin, lint and APK` context is now an always-running
aggregate gate. Actual full Android builds run as `Android build validation`.
The gate requires the classifier, PR security scan and every selected full job
to succeed; failed/cancelled or unexpectedly skipped dependencies do not pass.
Existing required context names and branch-protection settings are preserved.
PR security uses the reusable security workflow so the gate verifies its result
without a duplicate PR scan. Standalone main-push, scheduled and manual security
scans remain; direct pushes retain their independent security result, which must
also pass. Manual and reusable validation always remain full, including a
reusable PR caller with default/false inputs. The release validation/artifact
contract and all default/voice/startup/cloud/teardown acceptance phases remain.

See [the routing plan and evidence](android/ci-docs-validation-plan.md).
The main-push native filter is unchanged and retains the limitation below; a
lightweight PR route is not permission to omit validation for unknown changes.

**GitHub limitation:** native path filters evaluate at most 300 changed files.
In a larger mixed push, a code file outside that set can be missed. Do not rely
on this filter for that case: use manual Android CI dispatch or split the push.
GitHub runs workflows unconditionally when a push has more than 1,000 commits
or diff generation times out. See
[GitHub path-filter limits](https://docs.github.com/en/actions/writing-workflows/workflow-syntax-for-github-actions#git-diff-comparisons).
A full-diff gate job would avoid the file limit but would itself run on every
push, including documentation-only pushes.

Actions are pinned to reviewed commit SHAs; Dependabot checks those pins and Gradle dependencies weekly.

`release.yml` accepts version tags from main history, requests the additional unsigned release build/lint, and signs only after all validation jobs pass. Signing secrets are available only in the protected alpha-release environment. The publishing job has repository-content write permission; test jobs have read permission. See [releases](releases.md).

Starting with 0.16.0-alpha.1, the signed personal-alpha distribution enables the voice preview through `-PdistillpodVoiceEnabled=true` in its release build step. Ordinary source/debug builds remain default-disabled; CI checks both configurations. Headphone voice remains off until the user configures a key, permissions and the shortcut.

## Deliberate device checks

Documentation screenshots and videos have a separate opt-in fixture on an
empty emulator. Follow [media reproduction](demos/README.md#reproduce-the-captures).
`DocumentationMediaDeviceTest` is excluded from ordinary acceptance and requires
`allowDocumentationCapture=true` when invoked directly; it must never target a
personal installation.

Ordinary emulator acceptance uses synthetic data and public speech fixtures. Live account and long-running public-network checks require deliberate opt-in and should run only when authorized for that task:

- `LiveCaptureDeviceTest`, `LiveUnderstandingDeviceTest` and `LiveExploreDeviceTest`: instrumentation argument `allowLiveAccount=true`; `refreshLiveAccount=true` also exercises real token renewal.
- `PodcastBenchmarkDeviceTest`: `allowBenchmarks=true` for public podcast/model benchmarking.
- `LibrarySearchBenchmarkDeviceTest`: `librarySearchBenchmark=true` measures library search on a separate synthetic 3,000-episode database, sized with `librarySearchTranscripts` and `librarySearchSegments`. Run it on an emulator only.
- `FullEpisodeDeviceTest`: `allowPublicEpisode=true` for a complete public enclosure.
- `PlaybackReliabilityDeviceTest`: follow the [locked-playback protocol](android/background-playback.md), including owner-state restoration.

Select the class explicitly with Android instrumentation; do not add these opt-ins to ordinary PR runs. Preserve the owner's account, models, queue, downloads and listening state. Device and historical evidence lives in [implementation status](android/implementation-status.md); an earlier phone result does not validate every later code revision.
