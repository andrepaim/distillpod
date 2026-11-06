# ChatGPT account validation and connection screen correction

> Historical validation report. Results and commands below apply to the stated
> revision and device. For current behavior, use the [usage guide](../getting-started.md),
> [build instructions](../development.md) and [implementation status](implementation-status.md).

Date: November 1, 2026. Branch: `feat/native-android`.

The owner approved DistillPod's identity and ChatGPT plan permissions in the Android emulator. An OpenAI confirmation email corroborated the grant. The app then completed real Responses requests before and after the APK upgrade using that saved OAuth connection and displayed an insight for a short, non-sensitive sample about habits and routines. No API key or separately billed fallback was used.

The browser appeared to remain on the approval screen, while inspecting the original Android Activity showed **ChatGPT connected** with **Run AI check** enabled. A second Activity instance displayed stale connection state. The grant and inference access had worked; the apparent disconnected state was misleading.

## Correction

- The top MainActivity instance is reused on app reentry through `singleTop`.
- Connection availability is refreshed from the encrypted vault on resume and on a new Intent.
- The navigation device regression first reproduced two separate Activity instances, then verifies reuse after the correction.
- Credential device tests now use dedicated preference namespaces and Keystore aliases. They do not overwrite the owner's saved account during test reruns.
- The launch test supports both an already connected development installation and a disconnected installation; it no longer assumes every emulator starts signed out. The disconnected UI case remains covered explicitly by the JVM screen test.

Account names, email contents, credentials, callback URLs and personal screenshots are not included in repository evidence.

## Limits

This establishes one account's live authorization and text inference on the emulator. It does not establish all plans/models, long-term refresh rotation, physical-phone performance or full application readiness. A consent grant alone is not a guarantee of unlimited usage; requests consume the account's existing allowance.

## Verification after correction

All 10 emulator instrumentation tests pass, including the navigation regression and isolated credential-vault tests. The 44 Kotlin/Android JVM tests and lint also pass. [Device runner output](evidence/emulator-auth-tests.txt) records the executed suite.

Updated development APK SHA-256: `128ddde451e6f789f6ec4f2b45fa7e6112d801128e02bc06cdc1e3a7afd63919`.

## Physical-phone follow-up — November 2

Live authorization and plan-backed inference are now verified on the Samsung Galaxy S22 Ultra SM-S908E, Android 16/ARM64. The connection survives a full app process restart, and a further request using a public sample completed afterward. See [the phone validation receipt](s22-validation.md#live-account-and-restart-verification--november-2) and its sanitized metadata. The phone uses its own encrypted OAuth connection; emulator credentials were not copied. Live refresh-token rotation and account-switch behavior remain unverified.

## Production-alpha follow-up — November 2

The same phone connection now completes the production capture, summary and grounded chat workers after APK upgrades. A deliberate refresh-token renewal succeeded, followed by completed capture inference. [Renewal/restore evidence](evidence/s22-renewal-restore-tests.txt) and [summary/chat evidence](evidence/s22-understanding-tests.txt) record these runs. This verifies a real renewal; it does not assert that the server changed the refresh-token value or that every account-switch/revocation case was exercised live.

A separate isolated-library run completed a transcript explanation and resolved six public podcast suggestions, with owner history excluded. [Exploration metadata](evidence/s22-exploration-integration.json) stores counts rather than prompts, credentials or result text.
