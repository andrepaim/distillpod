# Repository and client security

Keep account data, credentials and device captures out of Git, build logs and
uploaded artifacts. `.gitignore` reduces accidental additions; it does not
remove already tracked files or historical commits.

## Repository checks

The **Repository security** workflow runs on main pushes, pull requests, manual
dispatch and weekly. It uses read-only repository permissions and no account or
signing secrets. Gitleaks is version/checksum pinned and scans all fetched Git
history, with encoded-content/archive inspection and fully redacted output.
Scanner reports and source snapshots are not uploaded as artifacts. GitHub
secret scanning and push protection are also enabled as repository settings.
The default scanner rules are extended with Anthropic OAuth, Telegram bot and
Tavily token patterns. A regression check generates never-issued fixtures in a
temporary directory and verifies detection without printing their values.

The Python data guard rejects databases (including SQLite content renamed to
another extension), signing containers, private environment/config files,
backups, logs and personal output directories. It checks current tracked files
and every new commit in a PR/push, so committing and then deleting a database
within the same change still fails. Existing historical personal data requires
a separate coordinated cleanup; passing this guard does not certify old
history as private-data-free.

Binary media and archives require an exact path and SHA-256 entry in
[`security/reviewed-assets.json`](../security/reviewed-assets.json). Review the
actual image/audio/archive contents and provenance before adding a checksum;
do not automatically approve a file to make CI pass. Keep earlier approved
hashes when multiple reviewed versions occur in the same change's history.
This manifest is a human review record, not a PII classifier. Textual personal
information and deliberately obfuscated data still require human review.

After committing a change locally:

```sh
python3 -m unittest discover -s scripts -p 'test_repository_data_guard.py'
python3 scripts/repository_data_guard.py --base "$(git merge-base origin/main HEAD)"
python3 scripts/verify_secret_rules.py --gitleaks "$(command -v gitleaks)"
gitleaks git . --log-opts='--all --full-history' --redact=100 --max-decode-depth=3 --max-archive-depth=3
```

The guard reads committed Git objects rather than ignored local configuration.
Gitleaks is a separate prerequisite for the final command. Do not suppress an
entire file/provider rule to accommodate an intended public Firebase client ID.
Review each finding and use the narrowest justified exception if needed.

## Firebase key restrictions

Firebase client configuration is packaged in the APK; it is not a substitute
for authorization. Deployed Firestore/Storage rules must retain account UID
checks. Public bucket access and broad client IAM grants are prohibited.

The Android key should be restricted to `dev.distillpod.android` and the retained
release signing certificate. Registering a certificate in Firebase does not
automatically restrict the API key. Keep ordinary CI builds unconfigured and
use demo-project cloud emulators for synthetic tests. A production-configured
debug build with another signing identity must not be silently added to the
production key's allowlist.

Keep API allowlists limited to the documented services in use, plus explicitly
planned client dependencies. Do not allow Gemini/Vertex AI or other unrelated
billable APIs on a public Firebase client key. Restrict a browser key to the
actual supported HTTPS hosting origins; avoid wildcard domains. Test proposed
restrictions with temporary keys, then validate the retained key after changing
it. Keep a private copy of the previous restrictions/etag for rollback; never
record key strings in a public issue, CI artifact or command output.

Header-level API-key restrictions are not app attestation or account
authorization. They do not replace Security Rules or App Check. A successful
public project-configuration request demonstrates acceptance by the API-key
gateway, not complete Google sign-in, token renewal or library synchronization
on an installed device.

See the [App Check rollout plan](android/app-check-rollout.md),
[Firebase API-key requirements](https://firebase.google.com/docs/projects/api-keys)
and [Google Cloud restriction documentation](https://cloud.google.com/api-keys/docs/add-restrictions-api-keys).
