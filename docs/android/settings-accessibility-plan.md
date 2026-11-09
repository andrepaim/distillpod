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
