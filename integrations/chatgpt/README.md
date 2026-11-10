# DistillPod for ChatGPT Work, Codex and dots

Implementation on PR #93. The Node client and skills-only plugin use the current
Firestore protocol. Live desktop Google sign-in, installed-host plugin execution
and Android/device interoperability remain acceptance gates; see the
[plan](../../docs/chatgpt-plugin-plan.md).

## Install on the execution computer

Requires Node 22 and a POSIX workstation (including Linux/WSL). Native Windows and
macOS credential-store integration are not yet validated. From this directory:

```sh
npm ci --ignore-scripts
npm test
npm link --ignore-scripts
```

`distillpod --help` works before sign-in. The package is private; npm link installs
only the executable from this checkout. The plugin contains no credentials or MCP
server. Install `plugin/` from a local plugin source following the current
[OpenAI packaging instructions](https://developers.openai.com/plugins/build/plugins).
Installation does not run login. In a new local Work/Codex task, invoke the
DistillPod skill and verify the command's help before signing in.

For a dot, explicitly connect this computer in the dot's profile and keep the
ChatGPT app open and computer online. The skill runs through a local task. A
cloud-only dot has no access to these local credentials. Host/plugin installation
must be verified on the actual host; a CLI test alone does not establish it.

## Private configuration and login

Create a configuration file outside the checkout with owner-only permissions.
Fields: `projectId`, `apiKey`, `clientId`, optional `clientSecret`. The client ID
must be a registered Google Desktop OAuth client accepted by this Firebase Google
provider. Use a separately reviewed desktop-compatible API key configuration;
the Android-restricted key cannot simply be reused. Do not change production
restrictions or create cloud clients as an installation side effect.

Pass absolute configuration and private state paths with every invocation:

```sh
distillpod --config /private/client.json --private-state /private/distillpod auth login
```

The command prints a Google authorization URL to stderr. Open it in the system
browser on the same computer. A loopback listener validates the callback and a
verified Google ID token; Firebase sign-in uses `autoCreate:false` to avoid creating
an unrelated account. Provider/client compatibility still needs live validation.
Never paste tokens or passwords into chat. A private sign-in handoff may be needed
when the agent cannot safely launch the user's browser.

This first client requires an explicitly selected private state directory (0700,
owned by the current POSIX user). Files use 0600. This is a filesystem protection
backend, not an encrypted OS credential vault. It stores refresh credentials and
operation payloads; choose a directory outside repositories, shared drives, cloud
backup and agent artifact directories. A local exclusive lock serializes token
refresh and journal writes. Following a process crash, verify no client is running
before manually removing its `client.lock`; retry the original operation ID.

`auth status` does not expose tokens. `auth logout` deletes local credentials and
operation journals; it does not delete Firestore or sign out the phone. Do not log
out with unresolved operations unless you accept losing their local recovery IDs.
Completed journals currently remain until logout; no automatic retention policy
has yet been implemented. Configuration and profile identity are checked per call.

## Use

All data commands return a JSON envelope. `--json` is accepted for agent callers;
JSON is also the default. Errors return nonzero with a code and safe message.

- `doctor` and `library status` check account scope/protocol.
- `subscriptions list`, `episodes list`, `episodes get ID`, `moments search WORDS`,
  `moments get ID`, `preferences get`, `queue get` read synced information.
- `shows search WORDS`, `shows inspect URL`, `shows episodes URL` use public directory
  and RSS metadata, without subscribing. Dates missing from RSS remain unknown.
- `subscriptions add URL --public-source` prepares a subscription.
- `queue add ID...`, `queue move ID BEFORE_ID`, `queue reorder ID...`, `queue clear`
  prepare queue changes. Reorder must preserve every current shared entry once.
- `queue add-feed URL GUID... --public-source` stages identities for public RSS
  episodes not already in Firestore, then updates the queue. It does not subscribe.
- `operations apply ID` applies the prepared change. User authorization for that
  exact change allows the skill to prepare and apply without asking again.
- `operations status ID` reports locally observed stages. If acknowledgement was
  lost, repeat apply with the same ID; the cloud receipt prevents duplicate writes.

A mutation's result is cloud-committed, with phone application unknown. Queue sync
requires account protocol 2/3, enabled in Android. Active phone edits can conflict
and require Android's conflict UI. Private phone entries can remain after clearing;
current playback is not stopped. No full transcript/analysis/history is provided.
Subscription refresh follows the phone's existing automation settings.

Reads default to a 1,000-record limit, configurable with `--limit` up to 5,000. A
budget limit or changing snapshot produces an explicit error rather than a complete
result. Large-library cursors/indexing remain future work. Search is keyword-based.

## Test with isolated services

From the repository root, install the locked Firebase harness dependencies and
use Java 21 for Firebase (Android still uses Java 17):

```sh
npm ci --prefix firebase --ignore-scripts
firebase/node_modules/.bin/firebase --config firebase/firebase.json emulators:exec --only auth,firestore --project demo-distillpod-sync 'npm run test:emulator --prefix integrations/chatgpt'
```

Tests seed only a demo project and exercise actual REST user tokens and the
existing rules. Emulator CLI configuration requires `emulator:true`, a `demo-`
project ID, and explicit loopback `firestoreHost`/`authHost`. The disposable
`auth login --emulator-email person@example.test` creates an emulator account;
there is no production equivalent. It does not create the library root: seed that
in the isolated fixture. Never point fixtures at owner data.

Unit tests cover ordering, stable identity, private destinations, bounded feed
parsing, journal recovery, private filesystem state and account mismatch. Emulator
tests cover transactions, receipts, stale edits, access denial and cloud reads.
Full Android cross-client and physical playback/background acceptance remains
required before declaring the feature complete or merging PR #93.
