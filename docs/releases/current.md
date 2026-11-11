DistillPod 0.23.1-alpha.1 better protects downloaded audio while playback and transcription are using it.

- **Keep audio available during playback.** A selected local copy is protected before it enters the playback queue, and remains protected while a player reader is closing after removal. If a selection becomes unavailable before playback starts, the app uses the remote source when available. Playback progress, video selection and queue ordering are preserved.
- **Keep audio available during transcription.** Pausing a transcript no longer allows its downloaded file to be removed while the reader is still finishing. Failed setup and cancellation release ownership so a file does not stay unnecessarily blocked.
- **Improve validation.** Notification tests avoid unrelated application startup, test database cleanup waits for its readers, and CI preserves the default debug APK before validating voice. Diagnostic test probes collect more evidence for an intermittent timeout; its cause remains under investigation.

Install the signed APK as an update over your existing DistillPod installation. Version code **42** retains the application ID and signing identity. Local database schema 12, portable backup schema 7, Firebase protocol and rules are unchanged from 0.23.0-alpha.1. The distribution build retains the voice preview and existing Firebase configuration. No backend deployment is required.

The included code passed both default and voice unit-test, lint and build checks, full hosted emulator acceptance, and isolated offline Galaxy S22 tests for the audio-ownership changes. These synthetic tests cover controlled file and service lifetimes; they do not establish every OEM scheduling or audio-routing condition. A signed upgrade on the owner's installation and live cross-device delivery were not tested for this release.

Bulk cleanup controls, automatic retention, local transcript deletion and the ChatGPT integration remain unfinished and are not included. Automatic cleanup stays disabled. Existing queue writes remain asynchronous and can be lost if Android kills the process before they finish.

[View all changes since 0.23.0-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.23.0-alpha.1...v0.23.1-alpha.1).
