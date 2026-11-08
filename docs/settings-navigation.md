# Settings navigation

Settings groups related tasks without changing stored preference values or defaults.

- **Your library:** Backup & Restore, Library sync, and Library (subscription import/export and refresh).
- **Playback & transcripts:** Playback & headphones, optional Voice, Transcript preparation, ChatGPT, and Downloads & storage.
- **App access & privacy:** Notification access and Privacy & diagnostics.

**Moment saved sound** has one editable control, under Playback & headphones.
**Only transcribe while charging** is under Transcript preparation. Its **Download
network policy** link opens Downloads & storage; Back returns to preparation.
Library links to Library sync and Backup & Restore without duplicating their controls.

## Stable route contract

Existing routes retain their destinations: `backup`, `settings:sync`,
`settings:library`, `settings:playback`, `settings:voice`,
`settings:transcription`, `settings:ai`, `settings:storage`,
`settings:diagnostics`, and `settings:about`.
The former Import & notifications page (`settings:library`) now contains library
tasks. Notification access is separately discoverable at `settings:notifications`.
Navigation uses the existing push/back stack, including cross-links.

## Validation

The source ownership regression tests guard moved controls and the single sound
editor. Device navigation tests cover 160% and 200% font scale, scrolling,
cross-links, Back, and absence of unrelated controls. Run them on an isolated
emulator and perform fresh non-destructive phone acceptance before merging.
