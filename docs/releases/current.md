DistillPod 0.12.0-alpha.1 brings the existing podcast library to Android Auto.

- Open DistillPod from the car launcher and browse Continue listening, Queue, Downloads and Podcasts, with cover thumbnails and navigation through large libraries.
- Play or resume an episode with the existing queue and listening progress. The expanded player supports play/pause, 10-second rewind, 30-second forward and Distill. Button placement depends on the head unit.
- Distill saves the preceding minute at the actual playback position, including while paused. Saving is durable before transcription or AI preparation begins; pending preparation can recover after restart.
- Media search supports show and episode requests without case or accent sensitivity. Speech recognition and app-name routing depend on the phone's Gemini/Assistant setup.
- Cached browse covers remain available offline. Local downloads play without connectivity. Connecting or browsing alone does not force playback.
- Preserves the phone lock-screen controls, autonomous social sharing, optional online transcript lookup, existing library sync and local saved content.

The owner verified the Android Auto preview in their car. Development validation also covered modern and legacy browser connections, folder refresh, paused capture, actual seek commands and playback restoration across force-stop. The integrated release is additionally gated by GitHub's Kotlin, lint, native, Firebase and isolated Android acceptance workflows. Broader car models, wireless connections and assistant configurations remain hardware-dependent.

This personal-alpha release retains the existing signing certificate and Firebase client configuration. Paid content sync remains disabled and transcripts stay local. Install as an update over the compatible alpha app; do not uninstall. Android 10+; ARM64 and x86_64. Version code: 21.

See the [Android Auto guide](https://github.com/andrepaim/distillpod/blob/main/docs/android/android-auto.md).
