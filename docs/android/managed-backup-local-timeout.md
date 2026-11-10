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
