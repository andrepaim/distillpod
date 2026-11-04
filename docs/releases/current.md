DistillPod 0.7.1-alpha.1 fixes transcript preparation being interrupted between audio chunks while the phone is locked or the app is in the background.

- User-started transcription runs in a dedicated foreground service, with a notification and Pause action.
- A bounded CPU wake lock keeps processing active with the display off. Each completed two-minute window is saved, so interruption resumes from durable coverage.
- Android 15+ uses the media-processing service type directly. Android 16 preparation no longer needs another JobScheduler slot after each saved window during an active foreground session.
- Charging-only, battery and network requirements still apply. Queued recovery and deferred work use WorkManager when a direct service cannot start.
- Fix a race where cleanup from an earlier backup operation could incorrectly interrupt the next backup or restore-preview action.

Completed chunks survive a restart. Explicitly paused work stays paused. Android's processing time limits and force-stop can still interrupt a session; preparation can resume from the saved chunks.

Install over the compatible personal-alpha app; do not uninstall as an update step. Android 10+; ARM64 and x86_64. See docs/android/background-transcription.md for the execution model, comparison with existing projects, and regression checks.
