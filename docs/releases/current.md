DistillPod 0.28.0-alpha.1 turns a saved moment into a share card you can post on LinkedIn or Instagram: an image of the idea plus a short caption, ready in one tap.

- **Create post.** Open a saved moment, choose **Create post** from its ⋮ menu, and the post starts right away. There is no format to pick first. You can leave the screen while it is written.
- **The card.**
  - **Styles:** swipe between **Quote** (the speaker's exact words, with a few highlighted), **Big idea** (the lesson as a headline, backed by a short quote) and **Takeaways** (three ideas you can use).
  - **Looks:** tap a swatch for **Cover**, in colours taken from the podcast artwork, **Ember** or **Signal**.
  - Changing the style or look is instant, uses no AI and is remembered for that post. Cards carry the show, the episode and a small DistillPod mark.
- **The caption.** A short post of 40–120 words. It opens with a hook, gives the key point from the moment and says why it matters for work. The show, the episode, a link when one is available and 3–5 hashtags are added for you.
- **Share.**
  - **Share** sends the card as an image with the caption, and also copies the caption, because Instagram and LinkedIn often drop shared text. Paste it if it is missing.
  - The post's ⋮ menu also has **Copy caption**, **Save image**, **Share as Story (9:16)**, **Edit caption**, **Undo last change** and **Delete post**.
  - **Change** rewrites the words from one instruction, such as "make it shorter", and keeps your style and look.
- **Replaced formats.** Quote & discussion and Infographic are gone, along with web research and AI-drawn illustrations. Posts you already made with them stay readable and shareable as they are, but cannot be changed. Retrying a failed earlier post makes a card.
- **Under the hood.** Jetpack Compose and Material 3 are updated (Compose BOM 2026.06.01). The Ember look of buttons, chips and the bottom bar is kept as before.

This personal alpha keeps the application ID and signing identity and raises the version code to **52**. Version code 51 went to a private phone build of the Compose update. The database schema is unchanged: posts keep their existing storage, and a small cover thumbnail is now saved with each card and included in backups. Playback, transcription, cloud sync, permissions and voice are unchanged. Voice stays enabled for distribution and unfinished cloud compaction stays disabled. Install the signed APK over the existing app to keep your data; do not uninstall first.

**Known limits:**
- **Instagram and LinkedIn:** how their current apps handle the shared image and caption has not been checked. If the caption does not appear, paste it; it is already copied.
- **Backups with card posts:** these need 0.28.0-alpha.1 or later to restore. Older versions reject them because of the cover thumbnails.
- **Quotes:** cards still sometimes pick a quote that needs context, and the words are only as good as the transcript.
- **Stories:** Story export goes through the normal share sheet, not Instagram's dedicated Story screen.

**Validation:**
- **CI:** both changes passed full CI on their pull requests (default and voice builds, lint, unit tests, Firebase rules, native bridge checks and isolated API 35 acceptance), followed by green Android CI on `main`.
- **Unit tests:** cover the card renderer for every style, look and size, plus long text, missing artwork and Portuguese. They also cover the caption checks and repair, the post screen at large text size, and sharing and saving.
- **Compose update:** 19 of 22 screens were pixel-identical before and after in an emulator screenshot comparison. The remaining three differ by small spacing details.
- **Phone:** posts were generated with a live ChatGPT account on a Galaxy S22 using a separate debug build. That test drove the prompt and colour fixes in this release. The signed update itself has not been installed on a phone, and nothing was posted to Instagram or LinkedIn.
- **Publication:** the release workflow separately validates and signs the tagged candidate.

[View changes since 0.27.0-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.27.0-alpha.1...v0.28.0-alpha.1).
