# Firebase sync protocol feasibility

This directory contains the isolated protocol/security test harness. It does not yet provide end-user account sync. The Kotlin Android adapter, full merge policies, cursor/bootstrap recovery and account UI remain implementation tasks in the [plan](../docs/android/firebase-sync-telemetry-plan.md).

## Local tests

Use Node 22 and Java 21. The Android Gradle build still uses Java 17. Install the locked tooling and run:

```sh
npm ci --prefix firebase --ignore-scripts --no-audit --no-fund
npm --prefix firebase run test:emulator
```

The command uses `demo-distillpod-sync` and Firestore on localhost:8085. Tests refuse to run without that emulator endpoint. No Google credentials or real project are needed. Security tests seed synthetic account control records using the rules-testing admin context; no production credentials are used.

The commit helper is a test driver for the proposed wire contract, not a deployed backend. It atomically writes a portable entity, an immutable change, an immutable receipt and the per-account sequence. Repeated operations return the same receipt; stale versions conflict. Rules require the companion writes and prohibit editing receipts/change history or changing account generation through the client.

Concurrent transactions exposed an emulator behavior where rules see a competing head update before the SDK returns a retriable transaction-precondition error. The driver retries a permission denial only after a server read proves that the active account's head advanced in the same generation. Retries are bounded; a denial with no such advancement propagates. The Android transport must implement and test this distinction before integration.

## Cloud setup status

The owner authorized a dedicated project, `distillpod-native-sync`, and Android app registration for `dev.distillpod.android`. Billing has not been enabled by this work. No production rules, user library, telemetry or app integration have been deployed. No region/database was provisioned yet.

The CLI login and downloaded client configuration are outside the repository. Service-account keys and OAuth credentials must never be committed. Client configuration is not an authorization boundary; rules and account lifecycle validation provide access control. Future staging/production separation, certificate registration, Google provider setup and App Check remain required.

These rules are a feasibility baseline. Do not deploy them as the completed feature: the final typed entity schema, size validation, lifecycle/retention policies and expanded adversarial tests remain open.
