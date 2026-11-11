DistillPod 0.23.2-alpha.1 is a maintenance update focused on diagnostic validation and build-failure reporting. It adds no new user-facing features.

- **More reliable diagnostic tests.** Producer tests can isolate physical disk-flush latency while retaining checks for file contents, publication order and failures. Normal app construction still uses the same native disk synchronization at the same persistence boundaries.
- **Correct timestamp checks.** Diagnostic tests validate observation and emission timestamps independently instead of assuming wall-clock samples always increase. Production timestamps and diagnostic content are unchanged.
- **Better failure evidence.** Additional test probes preserve bounded worker evidence, and CI retains Gradle exception stacks to help investigate packaging failures. The earlier packaging failure's underlying cause remains unconfirmed; current-source packaging passed.

Install the signed APK as an update over your existing DistillPod installation. Version code **43** retains the application ID and signing identity. Database schema 12, portable backup schema 7, Firebase protocol, rules and distribution configuration are unchanged. The voice preview remains enabled, and automatic cleanup remains disabled. No backend deployment is required.

Validation includes both default and voice unit-test, lint and build checks, native/default persistence regression tests, and full hosted emulator acceptance. Injected test fixtures do not establish physical durability or a disk-flush performance guarantee. No new physical-device run, signed upgrade on the owner's installation or live cross-device test was performed for this maintenance release.

Bulk audio cleanup, automatic retention, local transcript deletion and the ChatGPT integration remain unfinished and are not included.

[View all changes since 0.23.1-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.23.1-alpha.1...v0.23.2-alpha.1).
