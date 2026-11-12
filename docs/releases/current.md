DistillPod 0.23.3-alpha.1 is a maintenance release for the development and validation pipeline. App behavior is unchanged from 0.23.2-alpha.1.

- **Lighter draft previews.** Work-in-progress code pull requests defer expensive builds and emulator checks until they are marked ready for review. These draft previews are explicitly incomplete and cannot satisfy the merge gate. Ready code pull requests, main and release builds still require full validation; documentation-only pull requests retain their validated documentation route.
- **Clearer acceptance-test timing.** CI records elapsed time and exit status for startup, ordinary, cloud and voice emulator phases. Existing test selection, failure handling and required checks are retained; this does not claim an app performance improvement.

Install the signed APK as an update over your existing DistillPod installation. Version code **44** retains the application ID and signing identity. Database schema 12, portable backup schema 7, Firebase protocol, rules and distribution configuration are unchanged. The voice preview remains enabled, and automatic cleanup remains disabled. No backend deployment is required.

CI-routing and timing regression tests and documentation checks passed locally. Publication is gated on full hosted Android, native, Firebase and isolated-emulator validation of the release candidate and exact merged source, followed by release-workflow validation. No new physical-device run, signed upgrade on the owner's installation or live cross-device test was performed for this pipeline-only maintenance release.

Bulk audio cleanup, automatic retention, local transcript deletion and the ChatGPT integration remain unfinished and are not included.

[View all changes since 0.23.2-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.23.2-alpha.1...v0.23.3-alpha.1).
