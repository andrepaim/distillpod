# Settings navigation

Settings groups related tasks without changing stored preference values or defaults.

- **Your library:** Backup & Restore, Library sync, and Library (subscription import/export and refresh).
- **Playback & transcripts:** Playback & headphones, optional Voice, Transcript preparation, ChatGPT, and Downloads & storage.
- **App access & privacy:** Notification access, Privacy & diagnostics, and About DistillPod.

The overview starts directly with these groups under the Settings top bar; the
former "Make it yours" header was removed (#188). Group labels are announced as
headings, and every destination, including About, is a full-width row.

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
emulator before merging, and apply the per-change physical-device requirements
in the risk assessment below.

## Risk assessment for issue 22

This change reorganizes existing Compose destinations and moves existing controls.
It does not change stored keys, defaults, migrations, download/transcription
policy, notification permission logic, playback, or background work. The main
risks are wrong destinations, duplicate editors, inaccessible large-font layouts,
and broken Back behavior. Automated ownership tests, unchanged-value tests,
review, and emulator navigation at 160% and 200% font scale (including repeated
visits and cross-links) are appropriate evidence for this UI-only scope.

Notification recovery remains a platform-sensitive prerequisite from issue 20.
The canonical NotificationControls wrapper calls its unchanged NotificationAccess
component. Issue 20 retains its own permission/OEM validation requirements;
its physical-device evidence does not prove new Settings navigation. The new
entry point and retained-screen behavior must pass emulator acceptance. No new
phone validation is required solely for this UI organization under the current
risk policy. This does not waive failed checks or hardware-specific gates if
later integration changes permission behavior.

The latest main integration includes issue 33's queued transcript network-policy
fix. That repository implementation and its tests are unchanged from main:
revision-tagged requests retain identity and scheduling metadata, while legacy
requests are reconciled under the existing processing/control locks. Its
background/upgrade behavior has separate, higher-risk validation requirements;
the Settings-only assessment above does not waive them. Issue 33's scoped
physical evidence belongs to that unchanged implementation.

DownloadNetworkPolicyDeviceTest still opens settings:storage and exercises the
same canonical Wi-Fi policy switch, including legacy recovery and retained
progress. Settings navigation also reaches that page through the preparation
cross-link. Full combined automated checks and emulator acceptance remain
required after this integration. NotificationAccess is unchanged. Subsequent
sync/backup explanation work retains backup and settings:sync routes and owns
their callbacks separately.
