DistillPod 0.38.0-alpha.1 keeps the links from show notes in About and makes Find new voices order and explain its suggestions by what each podcast says about itself, with cover art.

- **Links in About work.** A link in an episode's show notes used to lose its address when the feed was read, so text such as "(link para compra)" appeared as ordinary text that could not be opened ([#308](https://github.com/andrepaim/distillpod/issues/308)). Links from the source are now kept and shown underlined in the accent colour, in the episode's **About** and in **About this show**. Tapping one opens the address exactly as the show wrote it. Plain `http://` and `https://` addresses in a description are links too, including in YouTube descriptions.
- **Episodes already in your library gain their links after one refresh.** The first feed refresh after updating downloads every followed feed in full once, because an unchanged feed would otherwise keep its old text. Listening position, downloads, queue and subscriptions are untouched.
- **Find new voices puts relevant shows first.** Suggestions used to appear in an order unrelated to relevance, so a show found through a single episode could sit above an obvious match ([#307](https://github.com/andrepaim/distillpod/issues/307)). They are now ordered by how well each podcast's own title, category, author and description match your topics. A show with no match in those comes last.
- **Each suggestion explains its own match, or says nothing.** Every card used to repeat one sentence written for the search that found it. A card now names the words from your topics that the podcast's own details contain, for example "Title mentions “dog” and “breeds”." When nothing matches, the card has no explanation.
- **Suggestions show cover art.** Each card shows the podcast's artwork, with the same initials placeholder that search results use when there is none.

Install the signed APK over the existing app; do not uninstall first. This personal alpha keeps application ID `dev.distillpod.android` and the existing signing identity, and raises the version code to **62**. Database schema, backup file format, diagnostics journal format and Firebase rules/protocol are unchanged from 0.37.0-alpha.1. Downgrading the APK is not supported.

**Known limits:**

- **Only web links become links.** Addresses that are not `http` or `https` (mail, app links, relative paths) keep their text and cannot be opened. A feed whose short description has no links still shows that description, even when its longer notes have them.
- **Paragraph breaks in show notes are still lost.** About shows the notes as one running text.
- **A backup made by this version shows link markup in older versions.** Restored into 0.37.0-alpha.1 or earlier, a description with links reads `[label](<address>)`.
- **Suggestion matching is by words.** A relevant podcast that describes itself in other words, or in another language than your topics and the searches, is listed last without an explanation. Directory categories arrive in Portuguese, so they only match Portuguese topics.
- **Earlier suggestions keep their earlier sentence** until a new request returns the same podcast.
- **Not checked on a phone:** a live Find new voices request (ordering and explanations are verified by tests; the cards were checked with stored example rows), TalkBack's reading of links, and the message shown when no app can open a link.
- **Still open from earlier releases:** the limits listed in the [0.37.0-alpha.1](https://github.com/andrepaim/distillpod/releases/tag/v0.37.0-alpha.1) notes and earlier, including long background downloads that wait until DistillPod is opened ([#292](https://github.com/andrepaim/distillpod/issues/292)).

**Validation:**

- **Hosted CI:** [#310](https://github.com/andrepaim/distillpod/pull/310) and [#309](https://github.com/andrepaim/distillpod/pull/309) each passed full CI: builds, unit and Robolectric tests, lint, Firebase rules, native checks and isolated emulator acceptance. #309 passed on a head that already contained #310. An earlier #309 run failed one emulator test in code neither change touches; it passed on the next run.
- **Phone (Galaxy S22, Android 16):** checked with separately installed test builds of the same code, not with this APK.
  - Links: a build of 0.37.0-alpha.1 showed the reported episode's "(link para compra)" as plain text. After updating that installation in place and refreshing feeds once, About showed it and a second source link as links, collapsed and expanded, and tapping the first opened the browser on the source's address.
  - Find new voices: cards with stored example rows showed real cover art, the initials placeholder, and no explanation or author line when those were empty. No ChatGPT request was made.
- **Publication:** the release workflow separately reruns full validation, release lint/assembly and signing. The downloaded assets are then checked for checksums, source identity, package/version, non-debuggable state, retained certificate and alignment.

[View changes since 0.37.0-alpha.1](https://github.com/andrepaim/distillpod/compare/v0.37.0-alpha.1...v0.38.0-alpha.1).
