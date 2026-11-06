# Documentation review — November 6, 2026

Baseline: `bad8488`, after repository security guards merged. The review covers
all 49 first-party Markdown documents tracked at that baseline, their local
references and associated dated evidence. Vendored whisper.cpp documentation
belongs to its pinned upstream revision. The published distribution was checked
against GitHub Releases: 0.15.0-alpha.1, version code 24, published November 6.

## Corrections and additions

| Finding | Result |
| --- | --- |
| The introduction described the app without showing its main actions. | Added two short captioned demonstrations and current screenshots, with synthetic content and text walkthroughs. |
| There was no complete documentation entry point. | Added [the index](README.md), separating user guides, developer references, plans and historical evidence. |
| The status page still listed 0.14 as the latest published APK and 0.15 as a candidate. | Updated the distribution row from the actual published release; retained the separate, dated phone-installation evidence. |
| Some implemented features were still described as branch previews. | Clarified Android Auto and social-sharing status, while preserving the historical design and device checkpoints. |
| The README's installation steps implied transcript preparation was always manual. | Explained automatic preparation and the manual alternative. |
| Headphone capture was difficult to discover from the main overview. | Added the setup link, introductory demo and settings screenshot. |
| User-facing sync documentation did not explain contextual-moment protocol 3. | Added upgrade ordering, pending-moment behavior, older-client boundaries and local-only history. |
| Firebase setup could imply certificate registration alone restricts a key. | Linked API/package/certificate restrictions and explained the separate debug configuration boundary. |
| Common waiting/failure/recovery states were scattered across implementation docs. | Added [troubleshooting](troubleshooting.md) for capture, transcripts, accounts, headphones, sync, backups and APK updates. |
| Historical feasibility reports contained statements such as CI not having run and old build paths. | Added prominent historical labels and current-guide links instead of rewriting recorded test results. |
| The web-archive guide made an overbroad claim about unpublished personal data. | Clarified retirement and that retaining Git ancestry does not sanitize earlier commits. |
| The Firebase plan still contains the original paid-content architecture. | Added an explicit reading guide: the owner-selected Spark scope governs; cancelled deployment milestones are not authorization. |

## Coverage

| Document group | Review and disposition |
| --- | --- |
| Root README, getting started, Android guide | Updated introduction, screenshots, navigation, automatic transcription, headphones and version/sync boundaries. |
| Privacy, backup, transcript lookup, contextual distills, Android Auto, headphone controls | Cross-checked user-visible controls and behavior against the current Compose screens, settings, capture/transcription repositories, backup and sync code. Added targeted navigation/compatibility details. |
| Development, repository security, releases, Firebase README | Checked commands, workflow triggers, pinned build tools, configuration and isolation rules. Release metadata/tag/note ownership remains with the release owner. |
| Current/unreleased release notes | Reviewed as release-owner records; no version fields, tags or release-note inputs changed. The published release is the distribution authority. |
| Original Android, Firebase, Android Auto and App Check plans | Distinguished implemented scope, cancelled paid deployment and remaining gates. App Check remains planned/off; no cloud setting was changed by this review. |
| UI/search/full-design and social-sharing plans | Marked historical or superseded records; pointed current behavior to the accepted user flow. |
| Audio, callback, ChatGPT, emulator, S22, personal-alpha/build, benchmark and transcription reports | Preserved dates, revisions, evidence and limitations; added current-guide links where the historical boundary was not prominent. |
| Android Auto/Firebase/social evidence Markdown and linked receipts | Kept evidence tied to its original revision and test environment; did not claim those checks were rerun for current hardware. |
| Legacy archive, icon design, sound asset and third-party notices | Checked lifecycle, provenance, references and current identity. Historical design assets remain distinct from new documentation captures. |
| AGENTS.md and CLAUDE.md | Reviewed repository, worktree, release and device constraints; no policy changes. |

All 105 unique external references from the baseline were checked with HTTP
HEAD, falling back to GET where needed, and responded successfully. Local file
targets, Markdown fragments and new media references are checked on the final
change. A successful link request establishes availability, not independent
verification of every third-party claim. Historical research citations remain
dated references.

## Media and evidence boundary

Eight current screenshots and two walkthroughs use an empty Android 16 / API 36
emulator at 1080 × 1920. The production app code matches `bad8488`; the opt-in
instrumentation fixture supplies fictional library/transcript content and
example AI text. It exercises real capture, uninterrupted playback and source
replay. It neither calls AI services nor accesses a real account. Headphone
capture uses a simulated Android media key, not a physical earbud gesture.

The [media record](demos/README.md) describes reproduction, caption transcripts
and limitations. Media files are reviewed individually and bound to exact
checksums in the repository asset manifest. The capture test is skipped in
ordinary CI; explicit capture runs are separate evidence.

## Keep documentation current

When behavior changes, update the relevant current guide and troubleshooting
entry alongside the code. Record published versions from Releases, rather than
copying a source version into the distribution row. Keep historical validation
dated; add newer evidence instead of making an old receipt claim a new result.

Recapture affected screens after visible UI changes. Use synthetic content,
verify actions and captions, review the final media and update its checksum.
Document any simulation or supplied AI result. Check local links/fragments,
external references, `git diff --check` and the repository data/secret guards
before opening the PR. CI remains the integration gate.
