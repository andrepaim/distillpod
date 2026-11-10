# Documentation PR validation (#88)

Base: origin/main8b1a20a. The pipeline session confirms88–92are unclaimed;
PR93/plugin work and its separate workflow remain owned by that session.

## Intended behavior and evidence boundaries

A documentation-only PR should validate documentation, whitespace and repository
security without Android/Firebase/native/emulator execution. Mixed or unknown
changes retain every existing full validation phase. Manual and reusable calls,
including distribution validation, remain full. Preserve release artifacts,
permissions, pinned actions, configuration/fixture flags and source isolation.
Do not publish a release or change branch protection/security settings.

Read-only branch-protection inspection found three required Actions contexts:
Kotlin, lint and APK; Native bridge validation; Isolated Android acceptance.
Repository policy additionally requires Firebase and repository security results.
Keep these existing names. Replace the Kotlin context with an always-running
aggregate gate; move its current full-build steps to an internal job. The gate
must verify classifier success and all selected results, rejecting failure,
cancellation or unexpected skips. Documentation routing permits only intentional
omissions; it must not turn a failed dependency into a green gate. Security stays
in its independent, unchanged workflow.

Sources: [required status checks](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks)
and [workflow contexts](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts).
Conditional jobs can report success when skipped, while skipped workflows can
leave required checks pending. Use job routing and an always/needs gate, not
PR workflow-level path filtering. Reusable calls have an inputs context: even
build_release=false must force full validation when invoked from a PR caller.
Treat any nonempty/unknown input contract conservatively; verify actual hosted
context behavior, and default to full on ambiguity.

## Implementation plan

1. Add meaningful red tests before behavior: complete Git diff classification,
   narrow allowlist, deletion/rename/mode changes, unknown/code/resources,
   more than300paths, shallow/missing/ambiguous Git evidence, manual/reusable
   selection and aggregate outcomes for success/failure/cancellation/skips.
   Use real temporary Git repositories and deterministic result fixtures.
2. Implement a standard-library Python classifier. On direct PRs, fetch full
   history and inspect the exact base/head comparison without API pagination or
   the native300-file limit. Validate commit identities, unique merge base,
   checkout identity and regular-file modes. Inspect both rename sides via
   no-renames additions/deletions. Unknown paths select full; incomplete reads
   fail visibly. Empty/unsupported inputs cannot select the fast path.
3. Use a reviewed documentation allowlist: known root Markdown files, docs
   Markdown/PNG/MP4 and historical evidence JSON/JSONL/TXT, existing Firebase
   and investigation README files, and the existing Manrope license path.
   New integration/plugin paths, executable files, symlinks, submodules, code
   under docs, workflows, configuration, scripts and app resources select full.
4. Validate local Markdown links/anchors and repository-bound targets, including
   reference/image links and fenced-code handling, plus git diff --check. Catch
   broken references after deletion/rename; do not fetch external URLs or read
   paths escaping the repository. Test meaningful malformed/broken examples.
5. Route the existing four full jobs conditionally, preserving their steps and
   full release/default/voice/startup/ordinary/cloud/notification/teardown phases.
   The stable required Kotlin gate always runs after classifier and full jobs;
   docs mode requires successful documentation/whitespace validation and only
   intentional skips, full mode requires all four full jobs successful. Retain
   the current main-push native filter and document its existing300-file limit;
   changing that separately is outside this focused PR-routing increment.
6. Review workflow/release contract, run all Python/data/security checks and
   relevant workflow syntax checks. Full hosted acceptance on this mixed code
   PR proves the real full route. Integrate current main before normal merge,
   then verify all selected checks on combined main. No protection bypass.
7. Keep88open for a legitimate documentation-only follow-up PR recording the
   measured result. Its diff against the merged classifier demonstrates the
   real hosted docs route and stable required contexts. Confirm exact source,
   routing decisions, selected/omitted jobs, security and main-push behavior.
   Compare with the equivalent full-source baseline, reporting sample size,
   elapsed/job time and runner usage where available, without claiming a
   universal speedup. A failed or unexpectedly full docs run needs diagnosis;
   manual dispatch alone does not prove PR-required-check eligibility.

## Risk-based validation

Routing mistakes can admit untested code. TDD classifier/gate/docs tests, exact
source checks, full hosted default/voice/emulator scenarios, a hosted docs PR,
normal integration and combined CI are required. Application/runtime/signing/
upgrade behavior is unchanged, so no physical phone or local Android rebuild is
required for this CI-only change. The hosted pipeline still runs its complete
Android builds and emulator checks. Reassess if scope changes. Existing main
native-filter limitations and source-comparison uncertainty remain explicit.
No caches, runners, retries, timeouts, assertion relaxations, account changes,
backend deployment, releases or paid services are introduced.
