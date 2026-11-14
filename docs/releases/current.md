DistillPod 0.26.0-alpha.1 introduces the Ember look and puts the controls you use most closer to the content.

- **New look.** The Ember Drop launcher icon, warm theme, and bundled Inter and Newsreader fonts update the app and widget. Dark reading remains available.
- **Now Playing.** Play, seek, speed and sleep controls are together. The playback bar marks available chapters and saved moments; the action row puts Distill and Transcript within reach. Open the lower sheet for Up next, transcript preparation and insights. Speed, effects and the sleep timer now have dedicated sheets.
- **Episode details and transcripts.** Episode tools are grouped by task. Transcript text is arranged into readable paragraphs while preserving the segment timing used for seeking, highlights and saved moments.
- **Show pages.** Episode lists come before preparation details. One attention action opens failed preparations even when episode filters hide them. Retry is available on the affected episode or in preparation details. Unfollowing a show asks for confirmation.
- **Settings and insights.** Settings show current values where available, while Learn more sheets retain the fuller explanations. The insights empty state explains the summary, chapters and ad markers it can generate.

This personal alpha keeps the application ID and signing identity and raises the version code to **49**. The update does not change database or backup schemas, playback routing, or cloud sync protocols; voice remains enabled for distribution and unfinished cloud compaction remains disabled. No backend changes or new Android permissions are required. Install the signed APK over the existing app to retain local data; do not uninstall first.

The show preparation flow passed real TalkBack navigation on an isolated Android emulator before integration; that result is scoped to the validated show flow, not a full accessibility audit of the new player or settings. The release workflow separately validates and signs the tagged candidate. No owner-phone install or live-account smoke test is claimed.

[View changes since 0.25.0-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.25.0-alpha.1...v0.26.0-alpha.1).
