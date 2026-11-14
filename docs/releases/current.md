DistillPod 0.27.0-alpha.1 completes the Ember redesign for the screens you browse every day: Library, Queue, Search and Saved.

- **Library.**
  - **Continue listening:** scrolls sideways through up to five in-progress episodes. With only one in progress, it keeps the single full-width card.
  - **Filters:** Latest · In progress · Downloaded stays pinned at the top while you scroll through long libraries.
  - **Episode rows:** lighter rows separated by thin dividers, with saved moments shown as ✦ N next to the date and time left.
  - **Refresh:** pull down to refresh Library or a show page. Refresh is also in the ⋮ menu.
- **Queue.**
  - **Reorder:** use the drag handle on Up next rows.
  - **Remove:** swipe a row left to remove it, then use **Undo** to put it back where it was.
  - **Layout:** Now playing sits at the top, and the Up next heading shows how many episodes remain and how long they last.
  - **Clear:** asks for confirmation in a sheet.
  - **TalkBack:** Move up, Move down and Remove from queue are still available as actions.
- **Search.** The tab is titled Search and the field submits from the keyboard, with no separate arrow button. All / Audio / Video is a segmented control, and recent searches are a plain list.
- **Saved.**
  - **Cards:** each card leads with the takeaway or quote over one source line (artwork, episode and time). The redundant "Saved" label is gone; pending and failed states still show.
  - **Filters:** filter by show when your moments come from more than one show.
  - **Your posts:** now in the ⋮ menu.
- **Top bar.** The settings gear appears on Library. Other tabs reach Settings from the ⋮ menu.

This personal alpha keeps the application ID and signing identity and raises the version code to **50**. The update changes presentation only. It does not change database or backup schemas, playback routing, cloud sync protocols, Android permissions or dependencies. Voice remains enabled for distribution and unfinished cloud compaction remains disabled. Install the signed APK over the existing app to retain local data; do not uninstall first.

**Known limits:**
- **Pull to refresh:** has no accessibility action, so Refresh stays in the ⋮ menu.
- **Dragging:** a queue row does not auto-scroll at the top or bottom edge of the list.
- **Undo after a swipe:** re-adds the episode through the existing queue actions, so the usual "Added to queue" message appears briefly.
- **The player's Up next sheet:** has no swipe-to-remove, because it has no Undo; use the row menu there.

**Validation:**
- **CI:** the change passed full CI on its pull request (default and voice builds, lint, unit tests, Firebase rules, native bridge checks and isolated API 35 acceptance), followed by green Android CI on `main`.
- **Isolated emulator:** 22 device tests passed on an isolated emulator with synthetic data, covering search, saved moments, queue controls, library search, show organisation, the product flow and large-text layouts.
- **Not done:** no full TalkBack review of the new Library, Queue, Search or Saved screens. No owner-phone install or live-account smoke test is claimed.
- **Publication:** the release workflow separately validates and signs the tagged candidate.

[View changes since 0.26.0-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.26.0-alpha.1...v0.27.0-alpha.1).
