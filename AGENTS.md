# Working on DistillPod

## Default issue-handling loop

- When asked to handle repository issues, work through the authorized queue autonomously: select one eligible issue, complete it, refresh the queue, and repeat until no eligible issues remain. Do not ask for confirmation between issues already within the user's authorized scope. A request to edit this policy alone does not start an issue-processing run.
- Select the highest-priority ready issue using the repository's established priority and readiness signals; prefer work that unblocks other issues and use oldest-ready first when priorities are equal. An eligible issue has clear scope and acceptance criteria, satisfied dependencies, the necessary authorization, and no active owner in another session. Do not treat every open issue as approved work or invent product decisions to make it ready.
- Claim the issue in the tracker and record the plan before implementation. Keep at most one active issue per session and respect other sessions' ownership. Split oversized issues into small, independently verifiable increments without closing the parent before all its acceptance criteria are met.
- Carry each issue through the applicable engineering workflow below. Features and bug fixes require planning, a separate branch/worktree, TDD for testable behavior, relevant automated checks, emulator acceptance, and non-destructive USB-phone validation. Documentation-only issues follow the documentation validation exception under Evidence.
- Completion includes review, integration with current `origin/main`, required validation of the combined code, a merged pull request, and successful CI on the combined commit. A local implementation, passing branch checks, or a pushed branch alone does not complete an issue. Link the PR to the issue and record the tested revision, checks, results, and limitations; close the issue only when its acceptance criteria and required validation are satisfied.
- If an issue needs clarification, an unavailable device, separate authorization, or another unmet dependency, record the concrete blocker and the next action needed in the tracker. Leave it open, release ownership when appropriate, and continue with independent eligible work. Do not repeatedly retry an unchanged blocker, bypass checks, or silently abandon unfinished work.
- Refresh priorities, dependencies, and ownership after each issue. Stop when the user asks, the authorized scope is exhausted, or no eligible issues remain; report completed issues and remaining blockers. Queue exhaustion does not mean all open issues are resolved. Never create a release, bump release versions, tag, or publish an APK as part of the loop without a separate explicit release request.

## Default feature workflow

- Treat every feature request as authorization to carry the feature through planning, implementation, validation, and merging into `main`. Follow this workflow unless the user explicitly changes its scope; release publication always requires a separate explicit request.
- Complete a plan before implementation. Define the intended behavior, acceptance criteria, affected components, compatibility and data risks, implementation steps, and automated/emulator/phone validation. Scale the detail to the change, resolve material ambiguities before dependent work, and update the plan when implementation reveals new information.
- Implement on a short-lived feature branch in its own Git worktree, following the parallel-development rules below. Split larger features into small, independently verifiable increments.
- Use test-driven development for testable behavior: write a meaningful test, run it and confirm it fails for the expected reason, implement the behavior, then refactor with tests passing. Cover relevant failure cases and regressions. For visual-only or exploratory work where an automated test would add little value, explain the limitation and define concrete acceptance checks; do not add tests that merely mirror the implementation.
- Before merging each feature, pass the relevant automated checks and emulator acceptance, and validate the affected user flows on the owner's USB-connected physical phone. Record the tested revision, device, scenarios, results, and limitations. Historical phone results do not satisfy this requirement. If a required device or check is unavailable, report the blocker and leave the feature unmerged.
- A feature request authorizes non-destructive installation and validation on the USB-connected phone. Use a compatible development build that preserves the installed app's signing identity, upgrade compatibility, and local data; this is not release publication. Preserve accounts, models, queue, downloads, and listening state. Never uninstall, clear app data, run emulator-only destructive fixtures on the phone, or bypass signing/upgrade safeguards to complete validation. Destructive tests, live-account changes, paid services, and backend/schema deployment still require explicit authorization.
- Once acceptance criteria, review, and required checks pass, integrate current `origin/main`, review and validate the combined code as described below, and merge through a pull request. Verify CI on the combined commit. Do not stop at implementation or an unmerged feature branch when the authorized workflow can be completed.

## Parallel development

- Use a separate short-lived branch and Git worktree for each independent change, starting from current `origin/main`. Never switch branches, alter unrelated edits, or run concurrent Gradle builds in another session's worktree.
- Keep changes small and merge completed work frequently. Split larger features into safe increments; use temporary feature flags when unfinished behavior needs to land disabled.
- Before merging, fetch current `main`, integrate intervening changes, review the combined diff, and rerun checks relevant to the resulting code. Separately passing feature branches do not establish that their combination works.
- Keep `main` releasable. Follow [development and validation instructions](docs/development.md), and verify CI on the combined commit. Fix or coordinate a revert of regressions promptly; do not publish a failing revision.
- Commit only the intended task files. Prefer pull requests for integration; an explicit instruction to commit and push to `main` authorizes that route. Never force-push `main` to resolve concurrent changes.

## Release ownership and authorization

- Release creation and APK publication require an explicit user request; never trigger them automatically after feature completion or a merge/push to `main`. Feature work must not independently bump release versions, create release tags, or publish assets.
- Designate one session as the release owner. It coordinates version fields in `gradle.properties`, `docs/releases/current.md`, release tags, and publication. Feature sessions document behavior and validation without independently changing release metadata unless assigned that work.
- An explicit request to publish a new APK authorizes the release owner to complete the [release checklist](docs/releases.md), including pushing the release commit/tag and publishing verified assets, without asking for the same authorization again. Report concrete blockers instead of bypassing checks.
- Every APK publication must follow `docs/releases.md`, the authoritative release procedure. Review all changes since the last published APK, including features, fixes, compatibility changes, and known limitations. Do not use stale release notes or treat an unpublished tag as a published release.
- Preserve in-place upgrade compatibility: application ID, retained signing identity, increasing version codes, local data migrations, backup formats, and sync behavior with older installed clients.
- Publication does not implicitly authorize installing on the owner's phone, destructive device tests, live-account actions, paid services, or backend/schema deployment. Follow existing task authorization and the relevant device/cloud instructions.

## Evidence

- Distinguish implemented behavior, automated checks, emulator checks, and physical-device validation. Record the revision and limitations of evidence; historical device results do not validate every later change.
- For documentation-only changes, review accuracy, local links, and `git diff --check`; a full local Android build is unnecessary unless build behavior also changes. CI still runs according to the repository workflows.
- Keep credentials, signing keys, personal content, and device state out of commits, logs, and CI artifacts.
