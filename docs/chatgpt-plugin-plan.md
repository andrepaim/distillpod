# DistillPod plugin for ChatGPT Work, Codex and dots

Status: implementation in progress on PR #93; CLI/plugin code is present.
Live authentication, host installation and Android acceptance remain unverified.
Prepared November 10, 2026 against `origin/main` at
`6a7f8b3328841d737bd54757aeb923d8dc426676`.

The owner subsequently authorized implementation on the same PR. This permits
implementation and isolated validation, while cloud configuration, live-account
actions and publication retain the authorization boundaries below. Follow
[AGENTS.md](../AGENTS.md) and recheck the baseline before each increment.

## 1. Outcome and scope

Build a personal DistillPod plugin containing a skill and a command-line client.
ChatGPT Work and Codex execute the CLI on a configured computer; the owner's dot
delegates to that connected computer. The CLI authenticates as the library owner
and reads or mutates the existing Firestore sync data. There is no MCP server,
new hosted application API, vector database or full-content sync in this version.

The first complete version must support:

- Read approved subscriptions, known episode metadata, saved moments/distills,
  recorded progress/completion, the shared queue and portable preferences.
- Search the synced library and saved excerpts, quotes and insights.
- Find public RSS shows and episodes, then subscribe to a selected show.
- Add episodes to the shared queue, move them, replace their ordering and clear
  the shared queue, without duplicating entries or silently overwriting edits.
- Explain exclusions, partial results, conflicts and cloud-versus-phone state.
- Use the same commands directly, through the plugin in Work/Codex, and through
  a dot task on the connected computer.

Examples of successful requests:

1. “Show my subscriptions and unfinished episodes known to cloud sync.”
2. “Find my saved insights about learning and show their episode timestamps.”
3. “Find this podcast and subscribe to the show by this author.”
4. “Add the latest two episodes of that show to the end of my queue.”
5. “Move this episode before that one, keeping the others in the same order.”
6. “Clear my synced queue.”

Do not promise complete listening history, total listening time, exact last-played
dates, all episodes of every subscription, full-text transcript search or access
to phone-only notes. A manually marked-complete episode is not proof it was heard.
Reading a saved distill exposes its published content, not its complete generation
history. Results describe available records rather than inventing missing data.

Out of scope: playback start/stop/seek, downloads, model execution, new AI requests,
editing saved moments, unfollow/delete operations, writing preferences or progress,
private feeds, YouTube subscriptions, full-content storage, automatic schedules,
public plugin-directory publication and unattended cloud-only dot execution.

## 2. Current implementation and constraints

| Area | Existing behavior | Consequence for this feature |
| --- | --- | --- |
| Account | A Firebase user owns a generation-scoped library | Authenticate to the existing UID and bind local state to project/UID/generation |
| Sync transport | Root sequence, versioned entities, ordered changes and operation receipts | Every write must participate in this protocol |
| Protocol 1 | Subscriptions, progress/completion and moments | Read supported records and subscribe; queue writes are unavailable |
| Protocol 2/3 | Episode identities, shared queue and portable preferences; protocol 3 adds contextual moments | Require protocol >= 2 for queue operations; preserve all supported moment formats |
| Shared queue | Ordered, distinct public episode IDs, maximum 200 | Validate the complete proposed queue before any metadata write |
| Private entries | Kept on the phone and retained during cloud projection | “Clear” means clear the shared queue, not erase every local entry |
| Active edits | Android can retain a local candidate and require conflict resolution | A successful cloud commit is not proof of phone application |
| Subscription restoration | Restores metadata; refresh depends on phone settings and worker execution | Do not promise immediate feed refresh or automatic playback |
| Content | Full transcripts, analyses and conversations remain local | No upload or additional collection is required |
| Environment | Documented deployment uses Spark with billing disabled | No paid infrastructure or production configuration changes are implicit |

Primary code to use as the compatibility contract:

- [SyncProtocol](../core/src/main/kotlin/dev/distillpod/core/sync/SyncProtocol.kt),
  [CollectionRecord](../core/src/main/kotlin/dev/distillpod/core/sync/CollectionRecord.kt)
  and [Firestore rules](../firebase/firestore.rules).
- [Cloud transactions](../app/src/main/java/dev/distillpod/android/sync/FirebaseSubscriptionCloud.kt),
  [subscription identity and writes](../app/src/main/java/dev/distillpod/android/sync/SubscriptionSyncStore.kt),
  [collection projection](../app/src/main/java/dev/distillpod/android/sync/CollectionSync.kt)
  and [portable records](../app/src/main/java/dev/distillpod/android/sync/PortableLibrarySync.kt).
- [Podcast discovery/import](../app/src/main/java/dev/distillpod/android/data/PodcastRepository.kt),
  [stable IDs](../core/src/main/kotlin/dev/distillpod/core/Queue.kt) and
  [streaming feed parsing](../core/src/main/kotlin/dev/distillpod/core/StreamingFeed.kt).

The [sync guide](android/subscription-sync.md), [security requirements](security.md)
and [Firebase harness](../firebase/README.md) remain authoritative. This plan
does not assume historical documentation schema numbers describe current code.

## 3. Architecture and execution environments

```mermaid
flowchart LR
    Work[ChatGPT Work / Codex] --> Skill[DistillPod skill]
    Dot[Owner's dot] -->|Connected computer task| Skill
    Skill --> CLI[DistillPod CLI]
    CLI -->|Firebase user token| Firestore[Existing Firestore library]
    Phone[DistillPod Android] <-->|Existing sync protocol| Firestore
    CLI -->|Public discovery only| Feeds[Podcast directory / RSS feeds]
    CLI --> Private[Local credential store / operation journal]
```

Implemented repository layout (compiled output is ignored):

```text
integrations/chatgpt/
  package.json                 # private Node package; locked dependencies
  package-lock.json
  src/                         # CLI, auth, Firestore, protocol, discovery
  test/                        # unit/contract/CLI tests and synthetic fixtures
  plugin/
    plugin.json
    skills/distillpod/SKILL.md
    skills/distillpod/references/
  README.md                    # installation, sign-in, operations, troubleshooting
```

Use TypeScript with Node 22 initially, matching the existing Firebase test tooling.
Use a user-authenticated Firestore REST adapter rather than an Admin SDK or ADC.
Keep protocol logic independent of CLI parsing and networking. Expose a stable
`distillpod` executable and machine-readable JSON; the plugin calls that executable.
The package remains private and is installed from a reviewed local checkout or
artifact. No install hook signs in, fetches a library or modifies cloud state.

Supported first environment: the owner's current workstation, including a tested
Windows/WSL boundary if that is where Work/Codex runs. macOS/native Windows support
is claimed only after installation, credential storage and browser callback tests.
Detect the actual execution host before using credentials or assuming paths.

OpenAI documents plugins with skills and optional MCP, and local-source plugin
testing. Package a skills-only plugin and verify the chosen manifest against the
current host; compatibility manifests may be added when a tested host needs them.
Do not include empty MCP configuration or claim plugin installation deploys a CLI.
See [plugin packaging](https://developers.openai.com/plugins/build/plugins) and
[connection testing](https://developers.openai.com/plugins/deploy/connect-chatgpt).

The dot's first supported route is a local task on an explicitly connected
computer with ChatGPT open and the computer online. Its cloud browser does not
inherit workstation credentials. If the host is offline, report unavailable and
offer to resume; do not silently run against another identity or stale snapshot.
Direct cloud execution is a later option requiring its own runtime, sign-in and
secret handling. See [dot computers and apps](https://learn.chatgpt.com/docs/dots/computers-and-apps).

## 4. Authentication and local state

Authentication is the first feasibility gate, before a production write path.

Proposed flow: a registered desktop Google OAuth client uses the system browser,
PKCE, state/nonce checks and a short-lived loopback callback. Exchange the provider
credential through Firebase Auth for a Firebase user token and refresh token.
Verify in staging that the desktop client is accepted by the configured Google
provider and resolves to the same existing Firebase UID. Do not infer compatibility
from Android's Google web client ID. References: [desktop OAuth](https://developers.google.com/identity/protocols/oauth2/native-app)
and [Firebase Auth REST](https://firebase.google.com/docs/reference/rest/auth).

The existing API key is restricted to Android's package/signing identity. A
compatible desktop client configuration and API restrictions need a separately
reviewed setup; never spoof Android headers, loosen the production Android key,
request broad cloud IAM access, or fall back to service-account credentials.
If provider audience/key restrictions cannot support the proposed flow, record
the exact gap and revise authentication before continuing dependent work.

Use Firebase ID tokens for Firestore, so existing owner Security Rules apply.
Admin/server credentials bypass that boundary and are not acceptable for this
client. [Firestore REST authentication](https://firebase.google.com/docs/firestore/use-rest-api)
documents this distinction. A CLI read-only mode is an application restriction,
not a claim that the Firebase token itself has a narrower authorization scope.

Credential behavior:

- Prefer an OS credential store. If unavailable in WSL, require an explicitly
  selected private storage backend; document its protection and threat model.
  Never silently put refresh tokens in a plugin folder or repository.
- Bind profiles to project, database and UID; generation changes invalidate
  prepared operations and cached state. A different login must not adopt a profile.
- Serialize refresh-token rotation across processes. Protect files with owner-only
  access and atomic replacement, and do not print tokens, codes or auth headers.
- `auth status` reports a safe account label and expiry/reconnect state. `auth logout`
  removes local credentials, journals and private cache; it does not delete the
  cloud library or sign out the Android installation. Explain that removing local
  credentials is not revocation of every other session.
- Never ask the user to paste a token/password into chat. Use the browser/private
  sign-in flow, including MFA where required. Test cancellation and expired login.
- Forbid production tokens with emulator endpoints and demo tokens with production.
  Emulator mode requires an explicit demo project and loopback endpoints.

No production project/client registration, API-key edit or live sign-in is performed
by this plan. Those setup actions need their own concrete authorization under the
repository policy. Normal plugin use needs no OpenAI API key or model API calls.

## 5. Command and output contract

Commands below define the proposed interface, not commands currently available.
All data commands support `--json`; interactive text is a rendering of the same
result. Use argument arrays, never shell interpolation of titles or feed content.

| Command family | Intended behavior |
| --- | --- |
| `auth login`, `auth status`, `auth logout` | Explicit profile setup and local credential lifecycle |
| `doctor` | Validate runtime, configuration, auth, account/protocol and plugin prerequisites; no mutation |
| `library status` | Cloud scope, observation time, supported protocol and completeness |
| `subscriptions list` | Current non-deleted approved subscriptions, paginated |
| `episodes list`, `episodes get` | Known cloud episode metadata and available progress/completion |
| `moments search`, `moments get` | Bounded text search and selected published saved content |
| `preferences get` | Read supported portable preference values |
| `shows search`, `shows inspect`, `shows episodes` | Public discovery and RSS metadata without following |
| `subscriptions add` | Prepare a subscription addition for a selected public source |
| `queue get` | Shared order, resolved episode identities, version and missing metadata |
| `queue add`, `queue move`, `queue reorder`, `queue clear` | Prepare an explicit shared-queue change |
| `operations apply`, `operations status` | Apply/recover a previously prepared operation using its durable identity |

Structured output includes `schemaVersion`, `ok`, `data`, `warnings`, and applicable
`nextCursor`, `observedAt`, `scope`, `complete`, `cloudVersion` and `operationId`.
Scope is `cloud_synced`, with public-discovery results identified separately.
Dates absent from the protocol are absent/null, never synthesized from read time.
Phone state is `unknown` unless independently verified; the current protocol has
no per-device application acknowledgement the CLI can rely on.

Errors have a stable code, retryability and next action. Define codes for login,
configuration, unsupported protocol, account mismatch, stale version, operation
identity mismatch, unavailable network, quota, incomplete read, invalid feed and
unavailable episode metadata. Use nonzero exit codes for failed/incomplete commands,
distinguishing usage, authentication, conflicts, transient service failures and
invalid data. Diagnostics go to stderr; JSON stdout remains parseable.

## 6. Reading, search and freshness

Read only `/users/{authenticatedUid}` and its supported entity/change/receipt
collections. Check active state, account generation and protocol before data use.
An absent library is an onboarding error; the CLI must not create or upgrade an
account as a side effect of reading it. Queue support requires the owner to have
enabled extended sync in Android; no automatic protocol upgrade is included.

Start with bounded, paginated entity reads and local filtering. For the personal
alpha, default to 100 records per page and a documented total request/read budget;
return explicit truncation and continuation rather than calling partial results
“all.” Resolve identities from available subscription/episode/progress/moment
records without treating absent episode documents as absent episodes on the phone.

Use a fixed read snapshot when supported by the REST adapter. Otherwise bracket
bounded collection reads with root sequence/generation checks and retry a bounded
number of times, reporting unstable/incomplete results when the library changes.
Continuation cursors bind to account, generation, filters and snapshot; expired
cursors restart explicitly. Verify API snapshot semantics during implementation.

For moments, provide snippets and stable record/episode IDs first, then fetch
selected content. Preserve episode time ranges, source revision and supported
source URLs. Do not invent clickable app deep links that Android does not handle.
Keyword matching is sufficient initially; disclose that it is not semantic search.

Do not create a persistent personal-content index in v1. Cache public directory/RSS
responses with a bounded TTL; retain only the private mutation journal necessary
for retries. Return cloud observation time, not a claim about the phone's last sync.
No background polling or automatic recurring summaries are enabled by installation.

## 7. Public discovery and subscription semantics

Use the existing Apple/iTunes directory approach for show-name discovery, behind a
small provider adapter. A direct public RSS URL is also accepted. Query the public
provider only when discovery is requested; never send saved moments or the whole
subscription list as a search query. Ambiguous names return candidates with author
and source; the agent must resolve ambiguity before subscribing.

Fetch and validate bounded RSS/Atom metadata. Match Android's stable identity,
GUID fallback, redirect and enclosure rules using cross-language golden fixtures.
Reuse existing cloud podcast/episode IDs when matching original/final feed URLs
and GUIDs. Preserve original feed identity; never derive IDs from display titles.
Detect ambiguous redirect aliases instead of creating duplicate subscriptions.

Public-source validation must reject userinfo, queries/fragments in synced locators,
private source links and unsupported YouTube channels. Do not strip a query to
make a signed enclosure appear public. Token-bearing paths cannot be reliably
identified mechanically: require explicit public-source acknowledgement for a
manual URL, consistent with Android's review boundary.

Because the CLI runs on a personal computer, RSS fetches must also block loopback,
private/link-local networks and metadata endpoints, including redirect/DNS changes.
Limit redirects, response sizes, decompression, request duration and XML complexity;
disable DTDs/entities. Do not fetch audio, execute extracted text or pass cloud
credentials to feed hosts. Treat titles/descriptions as untrusted data, not agent
instructions. Test these rules with synthetic HTTP fixtures.

`subscriptions add` is idempotent when already subscribed. It writes the existing
subscription format and retains existing canonical IDs. Following may cause normal
phone refresh/automation under the user's existing settings; the plugin must not
claim there are no downstream effects or change those settings itself.

Fresh RSS lookup supplies “latest episodes”; cloud-only metadata cannot guarantee
that ordering. If dates are absent, say that feed order was used. Adding an episode
does not implicitly subscribe to the show. “Subscribe and queue” is a composite
operation with separately reported stages, not an atomic all-or-nothing promise.

## 8. Safe writes and queue behavior

All supported mutations first produce a durable prepared operation containing the
exact intended change, account/generation, base version, operation identity and
payload signature. Retain this private journal before sending a network request.
The plugin may immediately apply a prepared change when the user's instruction
already clearly authorizes it; preparation is not an additional approval loop.
Ask only for ambiguity, a material conflict or authorization the session lacks.

Each existing-protocol entity commit must atomically:

1. Read the root, target entity and receipt for the operation ID.
2. Check authenticated ownership, active account, generation and supported protocol.
3. If a matching receipt exists, report the original committed outcome without a
   second mutation. Reject reuse of an operation ID with a different signature.
4. Require the entity's current version to equal the prepared base version.
5. Increment the root sequence and entity version, write the entity, corresponding
   change record and receipt using the current wire format.

Use user-authenticated REST transactions and current rules, with bounded retry of
transaction contention only. Do not reinterpret a stale queue replacement as an
authorized overwrite. An unknown commit outcome first queries its receipt; it
must not generate a new operation ID and blindly replay. Keep journal records for
unknown/unfinished operations; expire completed journal entries after seven days
and unapplied proposals after 24 hours, with explicit local cleanup.

Maintain byte-compatible signature serialization with Android, including Unicode,
key ordering and escaping, verified by shared fixtures. One entity mutation advances
the root once under the current protocol. Do not assume a batch of unrelated entity
mutations can share that sequence or satisfy the rules' companion checks.

Queue semantics:

| Action | Required behavior |
| --- | --- |
| Add | Append by default; optionally insert before/after a known ID. Existing entries remain once and keep position unless movement was requested |
| Move | Move one existing ID relative to another; preserve the order of all other entries |
| Reorder | Require an exact permutation of the observed shared queue; reject duplicates, omissions, additions and stale versions |
| Clear | Commit `items: ""`, not a tombstone or document deletion; leave subscription, progress and media records intact |
| No change | Report a no-op without a new cloud write |
| Too large | Reject the proposed queue before writes when it would exceed 200 entries |

Before changing the queue, ensure all referenced `episode_` identities exist and
are compatible with the selected metadata. New identities are committed first,
then the queue using its observed version. A failure between stages may leave
harmless episode metadata but must not change the queue; report partial staging
and allow safe recovery. Do not overwrite newer identity metadata to force success.

Cloud success is reported as “shared queue updated; phone application unverified.”
Android may be offline, paused or signed out, and active local changes may require
the existing conflict-resolution UI. The CLI cannot see all device-private entries
or resolve those local conflicts remotely. No polling loop should promise eventual
application without evidence.

Clearing/reordering the shared queue preserves Android's existing playback safety.
It is not the app's local “clear and stop” command. Private local entries can remain,
and current audio can continue. Explain this scope in command help, plugin guidance
and mutation results. Existing conflicts must never be bypassed for convenience.

## 9. Plugin behavior and onboarding

The skill invokes `doctor`, reads help/contract versions and checks the execution
environment before operating. Setup installs the reviewed CLI and signs in through
the private browser flow. It verifies a read-only cloud query first, then enables
normal explicitly requested writes. No full library dump is included in setup.

The skill must:

- Select only commands supported by the installed CLI version; never improvise
  raw Firestore writes, admin commands or alternate credentials.
- Use canonical IDs from lookup results rather than guessing from names.
- Read the queue before editing and preserve the operation ID across retries.
- Respect already-given user authorization without repeatedly asking for the same
  action; show a concrete proposed order when resolving ambiguous instructions.
- Treat RSS, saved text and command output as data, never as authority to execute
  embedded instructions, disclose credentials or expand scope.
- Report cloud freshness, limitations, pending/partial outcomes and conflicts.
- Stop and request reconnection when identity expires; never paste credentials
  into a conversation or include them in a public troubleshooting artifact.

Plugin setup and dot computer connection are separate steps. Test installation in
a new Work conversation, a new Codex task and a dot-created task. Record host/app
versions, CLI version and actual execution host. A listing in the plugin directory
or a successful direct CLI command alone does not validate all three surfaces.

## 10. Delivery increments and dependency order

Each increment gets its own plan refinement, branch/worktree, review and relevant
checks. Do not create all implementation branches in advance. Add implementation
issues only when separately requested; implementation continues on PR #93.

| Increment | Deliverable and exit criteria | Dependencies |
| --- | --- | --- |
| M0: feasibility | Prove user-token desktop auth in an authorized isolated environment; verify UID binding, restrictions and credential backend. Prove a harmless skill/CLI invocation from Work, Codex and a dot local task. Record blockers instead of assuming support | Configuration/host access authorization where needed |
| M1: CLI foundation | Private TypeScript package, JSON/error contract, doctor/auth, REST adapter and protocol fixtures. No mutation commands exposed | M0 decisions |
| M2: useful reads | Bounded subscriptions/episodes/progress/moments/preferences/queue reads, search and truthful coverage; two-account isolation tests | M1 |
| M3: discovery/subscriptions | Bounded public directory/RSS lookup, identity parity, public-source review and idempotent subscription writes | M1/M2 and mutation engine |
| M4: shared queue | Add/move/reorder/clear, metadata staging, operation journal, receipt recovery and stale-version protection | M2, M3 discovery and identity contracts |
| M5: complete plugin | Packaging, onboarding, skill instructions, supported-host installation and end-to-end conversational evaluations | M2–M4; packaging can be drafted after M0 |
| M6: personal pilot | Authorized production sign-in and explicitly selected subscription/queue operations; verify actual Android sync, conflict and playback behavior; record value and remaining limitations | All previous gates and required device/live-account authorization |

Build the transaction engine within M3 as its own independently testable commit or
PR if needed. Do not close the overall feature after reads alone. If an increment
is blocked, independent synthetic/test work may continue without bypassing its gate.

## 11. Risk-based validation

| Change | Main risk | Required evidence |
| --- | --- | --- |
| This documentation | Incorrect scope or broken references | Source review, local links and `git diff --check`; applicable CI only |
| Auth/credentials | Cross-account access, token disclosure, unusable callback | Mocked OAuth failure cases, two-user Auth/Firestore emulator checks, staging desktop login/refresh/logout and workstation callback tests |
| Read/search | Incomplete results presented as exhaustive; private output leakage | Pagination, stable snapshot, tombstone, unsupported protocol, budget/error and redaction tests |
| Discovery/identity | Wrong show, duplicate episode, unsafe network access | Kotlin/TypeScript golden fixtures, redirects/GUID edge cases, bounded parser and local-network rejection tests |
| Writes | Lost queue updates, duplicate requests, broken sync stream | Actual CLI REST requests against Auth/Firestore emulators with existing rules; contention, receipts and process-kill recovery |
| Android integration | Queue corruption, stale playback writes, background delivery assumptions | CLI-to-Android and Android-to-CLI isolated acceptance, both default/voice build compatibility as applicable; physical tests below |
| Plugin/dot | Wrong executable/identity, unintended actions or unavailable host | Real installed-plugin scenarios in each supported surface plus malicious-content and offline-host cases |

Use TDD for testable behavior: run a meaningful failing test, implement, then
refactor with checks passing. Emulator Admin access may seed synthetic fixtures,
but the CLI under test must use ordinary user tokens and pass deployed-equivalent
rules. Never validate the product client through a privileged emulator shortcut.

Minimum automated scenarios:

1. User A cannot read/write B; wrong project/generation and inactive account fail.
2. Protocol 1 reads work and queue writes fail with an upgrade explanation; protocol
   2/3 work without automatic upgrades, and unknown protocols fail safely.
3. Expired/revoked tokens, refresh races and cancelled browser login preserve state.
4. Pagination, deletion, missing identities and changing snapshots produce truthful
   completeness; search never fabricates transcript or historical listening data.
5. Duplicate subscription/add is a no-op; redirects and existing IDs do not fork
   the same show/episode; unsupported sources remain excluded.
6. Lost acknowledgement and killed processes recover via the same receipt;
   mismatched operation IDs fail, and malformed companion writes are denied.
7. Concurrent CLI processes and Android queue edits cause explicit conflicts;
   stale reorder/clear never drops newly added entries.
8. New episode metadata is staged before queue reference; failure mid-stage leaves
   the original queue intact; retry is safe.
9. Clear, reorder, boundaries, empty queue and the 200/201-item boundary behave
   correctly; private phone entries and active playback state are preserved.
10. Malicious feed content, redirects, decompression/XML bombs and shell metacharacters
    cannot change command authority or access private hosts.

Physical-device validation is required for the end-to-end write feature because
it interacts with background sync and active playback. Use a dedicated isolated
validation app/demo account and the existing non-destructive device protocols.
Exercise foreground/background sync, screen lock/network loss and reconnection,
active playback during queue changes, and explicit conflict handling. Preserve
the owner's production library, current playback, signing identity and local data.
Never run destructive emulator fixtures against the personal installation.

An authorized live-account pilot is additional evidence, not a substitute for
isolated tests. Use individually approved small mutations. “Clear the owner's
queue to test it” is not implicit authorization. Restoration is itself a versioned
operation and must not overwrite intervening edits. Missing device/account access
blocks the corresponding validation and completion claim.

Record exact CLI/Android revisions, environment/device, configuration, scenarios,
results and limitations. Integrate current main and rerun relevant combined checks
before PR merge; verify CI on the combined commit. No historical phone result
validates a new revision automatically. Use [development guidance](development.md).

## 12. Rollout, operations and completion

Pilot with the owner only. Keep the CLI/package install private, preserve existing
Firestore rules and schema, and retain Spark. Add CI for TypeScript/unit/contract
and Auth/Firestore emulator tests before enabling write commands. Integrate test
selection with the actual CI workflow at implementation time; do not depend on
unmerged CI optimization issues or skip cross-client acceptance to save time.

Default diagnostics are content-free: command family, outcome, elapsed time and
read/write counts. No feed URL, excerpt, UID, token, operation journal or raw
response is uploaded as a CI artifact. Detailed troubleshooting output is private
and opt-in. Record Firestore read/write consumption during synthetic benchmarks;
no claim of zero cost or unlimited Spark capacity is made.

Rollback stops CLI use, removes/updates the plugin and clears local credentials.
It does not erase cloud data. Valid completed subscription/queue changes remain
visible to Android. Any requested undo is a new operation against the latest
version, with conflicts surfaced. Do not purge change records or receipts during
rollback, disable rules, or downgrade the account protocol.

Release tags, public package publication, plugin-directory submission, APK release,
backend/schema deployment, paid services and production OAuth/key changes remain
separate authorized actions. If Android changes prove necessary, reassess scope,
upgrade safety, physical gates and release ownership before shipping them.

The feature is complete when all requested reads and subscription/queue operations
pass the tests above; the owner can use the installed plugin in Work, Codex and a
dot local task; actual phone effects and conflict limitations are documented; all
required PRs are merged with combined CI green; and a dated acceptance record
identifies what was validated. A cloud commit alone is not end-to-end completion.

For the pilot, record whether the owner can find a useful saved moment and build
or rearrange a desired queue using natural-language requests without manual
Firestore edits. Track lookup accuracy, unnecessary clarification, command latency,
conflicts and failed tasks using synthetic or owner-private evidence. These results
decide whether to invest in broader content access or cloud-only dot execution.

## 13. Decisions and gates to resolve during implementation

| Decision/gate | Proposed default | Evidence needed before dependent work |
| --- | --- | --- |
| Runtime and package | TypeScript/Node 22, private local install | Work/Codex/dot host can execute it reliably |
| Authentication | Desktop OAuth to Firebase user identity | Accepted provider audience/client config, same UID, refresh and logout behavior |
| Credential storage | OS credential store; reviewed private fallback if unavailable | Actual Windows/WSL or other host protection and callback path |
| Existing account scope | Preserve protocol; require enabled queue sync | Owner account protocol checked privately during authorized setup |
| Phone delivery | Existing sync/conflict semantics | Fresh emulator and physical evidence; no unsupported delivery promise |
| Discovery | Public RSS and current directory approach | Provider availability/terms checked before implementation; no paid provider |
| Plugin manifest | Current portable skills-only manifest | Installed-host validation; compatibility overlay only if necessary |
| Dot availability | Connected workstation | Explicit computer connection, app open, identity available and offline behavior tested |
| Production setup/pilot | Separate, specific authorization | Reviewed client configuration and exact live actions |

These gates are not permission requests to perform the current documentation task.
No secrets, personal-library exports or live installation identifiers belong in this
document or its eventual public issues/PRs.

## Implementation checkpoint — November 10, 2026

The TypeScript client and skills-only package now exist under
[integrations/chatgpt](../integrations/chatgpt/README.md). A separate workflow
executes unit and Auth/Firestore emulator tests without changing Android CI.
The client uses ordinary Firebase user tokens and existing rules, supports bounded
reads, public RSS discovery, prepared subscription/queue operations, receipt-based
retry and explicit conflicts. The plugin skill passed its frontmatter validator.

Current evidence: Node 22/TypeScript compilation, local unit/contract tests, real
Auth/Firestore emulator REST transactions, access-denial tests and a CLI subprocess
queue round trip. This is not Android cross-client or physical-device evidence.
A transient emulator failure with transaction IDs in GET query parameters led to
using the documented batchGet request-body transaction form; the affected tests
then passed. Raw emulator logs stay local and are ignored.

Remaining completion gates: desktop OAuth client/provider/key compatibility and
real refresh/logout, actual Work/Codex/dot plugin installation, broader Kotlin/CLI
identity/signature fixtures, CLI-to-Android bidirectional acceptance and physical
background/playback/conflict tests. No production OAuth configuration has been
identified or changed. PR #93 must remain unmerged until required evidence exists.

Deliberate initial limitations to reconcile before full acceptance: explicit POSIX
private-file credential storage instead of an OS vault, a 5,000-record maximum
read budget without continuation cursors, journals retained until logout rather
than automatic seven-day cleanup, and POSIX-only host support. Failure exit codes
currently share a nonzero status while JSON codes distinguish causes. These are
visible limitations, not claims that every planned milestone has passed.
