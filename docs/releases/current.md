DistillPod 0.15.0-alpha.1 lets you save the preceding minute from a headphone gesture while playback continues.

- Enable **Settings → Playback & headphones → Distill with previous-track button**. This option is off by default, including after an update, and stays on this device.
- In the Soundcore app, assign your Liberty 4 NC gesture, such as triple tap, to **Previous track**, if your firmware offers it. While DistillPod handles playback, gestures and other accessories sending that command save a moment instead of skipping. Single-earbud controls may need separate setup. Turning the option off restores the existing media-button behavior.
- Repeated button events are suppressed. A successful new save uses the existing pen-scribble confirmation through the current media output; its independent sound switch is also available on the new Playback & headphones page. Playback and the queue stay in place. Direct phone and Android Auto transport controls retain their behavior.
- Saved moments persist before transcript preparation or AI generation. Automatic transcripts, contextual distills, Android Auto and social sharing from earlier releases remain available. No new permission, account or microphone access is required for headphone capture.
- This update introduces no database, backup-format or sync-protocol migration. The headphone setting is excluded from portable backups and cloud sync. Existing protocol-3 upgrade rules still apply: update all installations before explicitly enabling contextual-moment sync. Paid content sync stays disabled and transcripts remain local.

The integrated feature source `95c28aa` passed Android CI: Kotlin tests, lint/APK assembly, native checks, Firebase rules and isolated Android acceptance. Emulator checks cover platform/system media keys, screen-off capture, repeat suppression, preserved playback/queue, settings changes and confirmation playback. Publication additionally requires CI on the exact release commit and configured release lint/assembly.

Physical Liberty 4 NC gesture mapping, Bluetooth confirmation routing, single-earbud and multipoint/call behavior remain unverified. This feature targets an existing playback session; it does not promise to start a force-stopped app or take control from another app or a call. Broader battery/device testing and contextual-distill live-model/human assessment remain outstanding. No owner-phone installation is included in this release validation.

Install the signed APK as an update over the compatible alpha app; do not uninstall. The application ID, signing certificate and required Firebase client configuration are retained. Android 10+; ARM64 and x86_64. Version code: 24.

See [headphone setup and validation](https://github.com/andrepaim/distillpod/blob/v0.15.0-alpha.1/docs/android/headphone-distill.md) and the [getting-started guide](https://github.com/andrepaim/distillpod/blob/v0.15.0-alpha.1/docs/getting-started.md).
