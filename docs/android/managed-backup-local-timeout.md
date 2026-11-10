# Managed backup local timeout (#86)

At #57 integration eff4dcc/main013ddc6, a full voice gate ran564app tests
with one15-second timeout awaiting initial created(uri), before disconnect.
The other nine backup tests passed. Raw XML/logs are retained privately. No
production failure, data loss or specific cause is established. The default,
lint and APK phases were not reached. Do not retry unchanged code for green.

Review finds that the unit fixture cancels its scope and immediately closes its
Room database and deletes files, without awaiting owned coroutine finalizers.
That is a concrete lifetime concern, not proof of this historical timeout.

1. Preserve the failure and add a controlled regression that holds owned
   coroutine cleanup while checking the fixture keeps its database/files alive
   and does not return. Use a test dispatcher and explicit gates, not sleeps.
2. Confirm the regression fails against existing teardown. If supported, await
   bounded cancellation/join before releasing fixture resources. Preserve body
   failures and add cleanup errors as suppressed evidence, not replacements.
3. Add bounded phase/state timeout diagnostics retaining the original timeout,
   its15-second limit and all backup/permission/data assertions. Do not print
   backup content, URIs, paths or personal identities.
4. Run focused backup tests then relevant complete default/voice checks, lint,
   APK/data/security and all five exact-head hosted checks. Review current main
   and combined code, merge normally only after required checks pass, and verify
   merged-mainCI. Keep #57 and historical failures accurately documented.

Risk assessment: intended changes are confined to synthetic unit-test fixture
lifetime/diagnostics. They do not alter application backup, permissions, cloud,
file or database behavior. Applicable hosted/emulator backup acceptance remains
required; no physical test is required for test-only teardown, while #57/#58 keep
their own physical gates. A runtime/data-handling discovery would change this
assessment and require fresh non-destructive device acceptance. No release or
production app/data/settings actions. Passing later tests alone does not prove
which asynchronous operation caused the original timeout; document that gap.

## Controlled result

The new explicit-gate regression failed before the fixture change with
"Fixture returned before its owned cleanup finished". The test uses an owned
worker's suspended non-cancellable finalizer and verifies that fixture return,
Room close and file deletion wait for that finalizer. Teardown now cancels and
joins the owned scope under a five-second cleanup bound before resource release.
A cleanup failure is suppressed onto an existing body failure, preserving the
original exception; resources are not closed while owned cleanup remains active.
The original operation wait remains15seconds with content-free phase/state
suppressed diagnostics. All11focused backup tests then passed.

This confirms the fixture lifetime defect and its correction. It does not
uniquely attribute the original creation timeout to that defect. Complete and
hosted validation are still pending; a future timeout should retain the new
operation phase and state for follow-up. Application runtime code is unchanged.
