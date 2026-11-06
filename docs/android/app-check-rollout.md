# App Check rollout plan

Status: planned; no client SDK integration or cloud enforcement is enabled by
this change. The November 6 configuration review found default/off App Check
service configurations. Firestore/Storage Security Rules continue to enforce
account ownership independently. This plan preserves the owner's Spark/no-paid-
services constraint and the existing signing identity.

## 1. Establish distribution and service compatibility

DistillPod publishes sideloaded APKs on GitHub. Use the
[Play Integrity provider's outside-Google-Play guidance](https://firebase.google.com/docs/app-check/android/play-integrity-provider)
rather than assuming Play-installed licensing/app-recognition verdicts. Confirm
the registered package/certificate and supported verdict settings on the
actual distribution path. Do not replace the retained key or require a
Play-only verdict that existing GitHub installations cannot obtain.

Confirm current App Check availability, quotas and billing requirements for
Firestore, Storage and Authentication separately. The active product uses
Firestore on Spark; finished-content Storage is disabled in application
builds. Do not enable billing, provision paid services, activate content sync,
or change Authentication service tiers as part of this rollout.

## 2. Add client support without enforcement

- Add the Firebase BOM-compatible App Check Play Integrity dependency.
- Install the provider on the named `distillpod-sync` FirebaseApp in
  `SubscriptionSyncAccount` before constructing Auth, Firestore or Storage
  clients. The default FirebaseApp is not the app used for synchronization.
- Keep automatic initialization out of unconfigured builds and the isolated
  instrumentation runner. Use demo cloud emulators for synthetic tests; never
  register a debug token against production from CI or log one in artifacts.
- Keep enforcement off while adding support. Attestation failure must leave
  local playback/library data intact and offer a bounded retry state, without
  spinning, signing the user out or discarding the sync outbox.
- Add focused tests for initialization order, unconfigured/emulator builds,
  delayed/unavailable attestation, retry behavior and preservation of pending
  local changes. Run the existing Firebase and Android sync acceptance suites.

## 3. Validate a compatible candidate and monitor

An APK release and owner-device/live-account testing require their own task
authorization under [the release procedure](../releases.md). The repository
security task does not publish or install a new APK.

For an authorized candidate, verify a GitHub-installed build on a supported
device: Google sign-in, token refresh, reconnect after process death, offline
outbox replay, foreground/background sync and network loss. Include a prior
released client to measure the compatibility boundary. Do not upload a
synthetic library to the live project or manipulate the owner's library merely
to create test traffic.

After client support works, move only supported/in-scope services to monitoring
mode (`UNENFORCED`) and examine valid/invalid/missing-token metrics. A policy
configuration GET returning a default Play Integrity configuration is not
evidence that a real installed client successfully attested.

## 4. Enforce after installed clients are ready

Record the minimum compatible release, the installed-client coverage evidence,
the expected handling of older clients, and the service-specific rollback
configuration before enforcement. Switch one supported service at a time and
check authentication, synchronization and permission-error rates. Preserve
account-isolation rules throughout.

If valid installed clients fail, return that service to `UNENFORCED`, preserve
client state and diagnose the attestation issue. Do not weaken data rules,
enable public access or rotate the signing identity to bypass the failure.

Evidence for this plan is source/configuration review. No Play Integrity token
exchange, App Check-enabled device test or enforcement trial has been performed.
See [enforcement modes and compatibility cautions](https://firebase.google.com/docs/reference/appcheck/rest/v1/EnforcementMode).
