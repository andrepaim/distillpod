# Settings switch accessibility (#21)

The diagnostics, usage-statistics and rewind controls must expose one named
switch each, with correct role, checked/disabled state and a full-row touch
target. The visual thumb must not create a second focus or toggle action. Keep
diagnostics and usage consent independent and preserve the rewind preference.

The owner handed over the unfinished branch at 41b59f2. The tracked patch and
both new files were backed up privately before review. Retain the small shared
LabeledSettingSwitch and narrow call-site changes; integrate current main without
restoring obsolete voice-context limits or replacing unrelated settings code.

1. Run the handoff tests against current main in a separate test-only worktree
   and confirm failures for unnamed toggle semantics.
2. Review the helper and callbacks, commit the preserved change, integrate main
   and run the same focused tests. Extend only meaningful gaps in independent
   state, large text, checked/disabled state or duplicate focus targets.
3. Run full relevant default/voice local checks, security/data guards and all
   five hosted checks. Add emulator acceptance for the actual settings controls.
4. Validate all three controls with TalkBack navigation and large font on an
   isolated offline S22 app, preserving the production app, data and settings.
   Record exact build/device/scenarios; semantics assertions alone do not prove
   spoken announcements. Do not change phone settings without coordination.
5. Refresh main, review and validate combined code, merge normally and verify
   combined-main CI before closing the issue. No release work.

This is a localized UI semantics change with no schema or consent-policy change.
The principal risks are duplicate/missing accessibility actions, changed disabled
behavior, accidental shared consent state and clipped labels at 200% font size.
Relevant automated and emulator checks cover these contracts; actual TalkBack
acceptance remains mandatory under the issue's criteria. Recurrent Windows
socket-buffer errors currently block physical validation; leave the PR unmerged
until that evidence is available.

## Handoff regression evidence

All three handoff tests failed on current main 9f56e74 in a separate test-only
worktree because the named toggle nodes were missing. The preserved fix was
committed and merged with main without conflict; only the three intended row
call sites changed, retaining current full-transcript voice copy. The same three
tests passed on the combined tree. The two actual-settings emulator scenarios
compile after correcting a test bounds assertion to the supported API. They
cover role/state, one target per diagnostics switch, 200% text, 48dp minimum
targets and rewind persistence. The opt-in offline accessibility21 build removes
network/microphone permissions from both packages. Full gates, hosted execution
and manual TalkBack acceptance are pending; none is replaced by these results.

The first hosted device run rejected the new diagnostics fixture method before
execution because its expression body inferred a non-Unit return. Both device
methods now explicitly use runBlocking<Unit>, preserving the same assertions.
The earlier full local default/voice gates passed 99 core and 523 app tests each,
lint/APKs, 87 Python tests and security/data checks; those results did not execute
the device cases. The updated compiled method signatures and full hosted suite
must pass before counting emulator acceptance.

## Current-main integration and acceptance

The preserved change was integrated with main `013ddc6`, including the queue,
transcript-lifetime and Shows-reset corrections. The three focused semantics
regressions passed after the runtime integration. Diagnostics and usage still
invoke their independent consent callbacks; rewind retains its existing local
preference. No production behavior beyond the intended switch semantics changed.

The device rewind scenario now checks its label and help text at 200% font size,
alongside the existing diagnostics large-text cases, before checking toggling and
persistence. This extends acceptance coverage without changing runtime code.
Run both full configurations, relevant emulator scenarios and all five hosted
checks on the final combined head. Earlier test/installer results remain scoped
to their recorded revisions; a fresh verified offline build is required.

Actual TalkBack navigation remains a separate manual gate. Prepare the isolated
build and concrete checks before requesting it; do not enable accessibility or
change owner display settings automatically. Automated semantics and enlarged
test layouts do not prove spoken output. Preserve all production data and leave
the issue unmerged until required acceptance and merged-main CI pass. No release.
