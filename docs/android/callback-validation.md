# Loopback callback listener correction

> Historical validation report. Results and commands below apply to the stated
> revision and device. For current behavior, use the [usage guide](../getting-started.md),
> [build instructions](../development.md) and [implementation status](implementation-status.md).

Date: November 1, 2026. Branch: `feat/native-android`.

The owner received a ChatGPT permission confirmation email on the S22 Ultra, but the app had no saved credential envelope. Browser diagnostics showed a navigation to the local `/auth/callback` ending in `ERR_CONNECTION_TIMED_OUT`. The email established a grant, not a completed connection in this installation.

An isolated HTTP listener under the application's UID successfully served a diagnostic page to the same Chrome browser. General browser-to-app loopback connectivity therefore worked. Inspection also found a fragile one-connection listener: an idle connection or unrelated request could terminate the attempt, and the attempt expired after three minutes. The exact first request in the failed attempt was not captured, so the triggering cause is not conclusively established.

## Correction

`LoopbackCallbackServer` now owns the local listener for one bounded attempt:

- Accept multiple connections until a validated callback arrives, cancellation occurs, or the ten-minute attempt deadline expires.
- Close idle or incomplete browser connections after a short read timeout and continue waiting.
- Reject unrelated paths and invalid callback state without consuming the active attempt.
- Preserve strict callback address, state, registration and consent checks in `OAuthAttempt`.
- Consume bounded HTTP headers, send a non-cacheable response and close the browser socket before token exchange.
- Show “Connection received. Return to DistillPod for the final result.” only after callback validation. Token exchange, ID-token validation and encrypted credential persistence still happen before the app reports connected.
- Close both the listener and an active connection when the Activity destroys the pending attempt.

This follows the [official registration and sign-in protocol](https://developers.openai.com/siwc/token-sharing-open-source/sign-in): receive and validate the loopback callback, exchange its code with PKCE and the exact redirect URI, validate identity and plan scopes, then save credentials. Consent alone does not complete those steps. No API key or fallback provider was introduced.

## Verification

- 54 JVM tests pass: 38 core and 16 Android/Robolectric tests. Five new tests cover idle preconnections, unrelated paths and invalid state, consent denial, closing a pending listener, and deadline expiration.
- Android lint and the updated debug APK build pass.
- The new browser callback test passes on the physical S22 Ultra: Chrome sends a synthetic callback behind an idle TCP preconnection, the app validates it, and the browser displays the completed response. The synthetic callback contains public probe values; no real OAuth code or token is logged or exchanged.
- App reentry regression also passes. The two focused device tests completed in 2.865 seconds; [runner output](evidence/s22-callback-tests.txt) is saved.

The updated APK was installed and a fresh real authorization attempt started. Live phone token exchange and completed inference remain pending owner interaction at this point. An existing grant from the earlier failed attempt is not treated as a successful connection, and emulator credentials are not copied to the phone.

Updated debug APK SHA-256: `fb7fed58fa4446353fc88bb6f1a75cc836bcbc371f849886631bed657c6d44d1`.

## Token-exchange network recovery

The next real attempt reached the callback and displayed its confirmation page, establishing that the loopback correction worked on the phone. The native app then reported that it could not resolve `auth.openai.com`, so no credentials were saved. Subsequent DNS probes under the app UID resolved both OpenAI hosts in foreground and background. A transient DNS failure is observed; no persistent DNS misconfiguration or specific background policy has been established.

The continuation waits for the Activity to resume before exchanging the callback code. Recoverable failures expose **Retry account connection**, retaining the validated callback only in private process memory. A DNS failure before the token request can be retried without restarting browser authorization. If tokens were already received but public signing-key retrieval fails, retry reuses the received token response and repeats verification rather than exchanging the consumed code again. An ambiguous transport failure during the token POST requires fresh authorization. Terminal scope/identity failures clear pending state. No unverified credentials are written to the vault, and pending state is lost on process death.

Token expiration is anchored to the time the token response was received; delayed verification does not extend its lifetime. Disconnect clears the pending state as well as the saved connection. No automatic text-inference retry or billing fallback was added.

- All **60 JVM tests pass**: 38 core and 22 Android/Robolectric tests. Added tests exercise DNS retry, received-token reuse, unchanged expiry across verification retry, ambiguous token failure, scope denial, disconnect and the retry control.
- Lint, APK build and test APK build pass.
- Three focused checks pass on the S22 Ultra in **2.936 seconds**: native DNS/HTTPS access to OpenAI's public JWKS endpoint, the real Chrome callback and app reentry. [Runner evidence](evidence/s22-network-recovery-tests.txt) is saved. The public-network diagnostic uses no account credentials and is explicitly excluded from the normal emulator/CI suite.

Updated APK SHA-256: `8abe72abd970238e921e6decf5b5b510bd85a0d824f40ddaf01431011aa9e91f`. It is installed on the phone. On November 2, the owner completed sign-in and the connection survived a full process restart; a further live AI request completed afterward. See [the phone validation receipt](s22-validation.md#live-account-and-restart-verification--november-2). Live refresh-token rotation remains open.
