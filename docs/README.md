# DistillPod documentation

Start with the [two short demos](demos/README.md) and [Getting started](getting-started.md).
DistillPod is a personal-alpha Android app. Current guides describe `main`;
[implementation status](android/implementation-status.md) records the source
revision, downloadable APK and separate device evidence. A source version or
release-note file alone does not mean an APK has been published.

## Use the app

| Task | Guide |
| --- | --- |
| Install, follow a show, listen and save your first moment | [Getting started](getting-started.md) |
| Watch headphone capture or on-screen distillation | [Videos, screenshots and text walkthroughs](demos/README.md) |
| Save a moment without taking out your phone | [Headphone controls](android/headphone-distill.md#setup-and-expected-experience) |
| Use the player on a compatible car display | [Android Auto](android/android-auto.md) |
| Understand source selection, review and previous versions | [Contextual distills](android/contextual-distills.md) |
| Find an existing transcript before local Whisper | [Online transcripts](android/online-transcripts.md) |
| Save or transfer a complete portable library | [Backup and restore](android/drive-backup.md) |
| Sync supported data between installations | [Library sync and protocol compatibility](android/subscription-sync.md) |
| Make a text post or infographic from a moment | [Create a social post](getting-started.md#create-a-social-post) |
| Resolve common waiting, connection and update states | [Troubleshooting](troubleshooting.md) |
| Understand what stays local or leaves the phone | [Privacy and data handling](privacy.md) |

ChatGPT powers generated text. Google sign-in is for optional library sync.
Drive backup uses Android's file picker. These are three separate connections;
none substitutes for the others.

## Build and maintain

| Reference | Purpose |
| --- | --- |
| [Development](development.md) | Pinned tools, builds, CI and isolated emulator checks |
| [Release procedure](releases.md) | Review, validation, retained signing identity and publication |
| [Repository security](security.md) | Secret scanning, personal-data guards and reviewed media |
| [Firebase harness](../firebase/README.md) | Demo-project rule/protocol tests and deployment boundaries |
| [App Check rollout](android/app-check-rollout.md) | Planned client integration and staged enforcement; not enabled |
| [Implementation status](android/implementation-status.md) | Delivered behavior, current distribution and evidence gaps |
| [Documentation review](documentation-review.md) | Coverage, corrections, media provenance and maintenance checklist |
| [Agent instructions](../AGENTS.md) | Worktree, integration, release and device-authorization rules |
| [Repository orientation](../CLAUDE.md) | Short architecture and development constraints |

[Current release-note input](releases/current.md) and [unreleased notes](releases/unreleased.md)
are maintained by the release owner. Use [published GitHub Releases](https://github.com/andrepaim/distillpod/releases)
to choose an installable APK.

## Design and implementation records

These documents explain decisions and remaining acceptance gates. Historical
branch names, commands, versions and unchecked plan items are not a second set
of current installation instructions.

- [Original Android plan](android-implementation-plan.md), [Firebase sync/telemetry plan](android/firebase-sync-telemetry-plan.md), [Android Auto plan](android/android-auto-plan.md).
- [ChatGPT plugin plan](chatgpt-plugin-plan.md): proposed CLI and skills-only integration for Work, Codex and dots, using the existing Firestore scope.
- [YouTube sources and videocasts](android/videocasts.md): feature-branch behavior, limitations and validation.
- [Listening UI proposal](android/ui-improvement-proposal.md), [search proposal](android/search-ui-proposal.md), [full design review](android/full-design-review.md).
- [Social-sharing plan index](android/social-sharing-plan.md), [accepted two-format design](android/social-sharing-redesign.md), [superseded first design](android/social-sharing-design.md).
- [Confirmation-sound record](audio/distill-scribble.md), [icon design source](../design/icon-proposal/README.md), [third-party notices](../THIRD_PARTY_NOTICES.md).
- [Retired web archive](legacy-web.md); its hosting instructions and features do not describe Android.

## Historical validation

Each record applies to its stated revision, device and conditions. It does not
certify a later APK or every physical accessory. Use [current status](android/implementation-status.md)
for the combined evidence and remaining gaps.

| Area | Records |
| --- | --- |
| Initial feasibility and sign-in | [Receipt](android/validation-receipt.md), [emulator](android/emulator-validation.md), [ChatGPT](android/chatgpt-validation.md), [browser callback](android/callback-validation.md) |
| Physical alpha | [S22 checks](android/s22-validation.md), [personal-alpha guide](android/personal-alpha.md), [build progress](android/personal-build-progress.md) |
| Audio and transcripts | [Decoding](android/audio-validation.md), [transcription progress](android/transcription-progress.md), [background transcription](android/background-transcription.md), [podcast benchmark](android/podcast-benchmark.md), [locked playback](android/background-playback.md) |
| Android Auto | [TDD](android/evidence/android-auto-tdd.md), [validation](android/evidence/android-auto-validation.md) |
| Sync and social sharing | [Firebase TDD](android/evidence/firebase-sync-tdd.md), [social redesign](android/evidence/social-redesign/validation.md) |

The older [Android guide entry point](android/guide.md) remains available for existing links.
