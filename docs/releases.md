# Signed alpha APK releases

Version name and monotonically increasing version code live in `gradle.properties`. Current releases are personal-alpha prereleases, not Play Store production releases. Source versions and private phone builds can precede a published APK; `docs/releases/current.md` is the release-note input for the next matching tag, not proof that the APK has been published. See [implementation status](android/implementation-status.md) and [GitHub Releases](https://github.com/andrepaim/distillpod/releases).

## Publishing

1. Update both version fields and `docs/releases/current.md` on main.
2. Wait for Android CI to pass on that commit.
3. Tag the matching commit, for example `v0.10.1-alpha.1` for the current source version, and push the tag.
4. Publish APK verifies the tag against the source version and main history, reruns validation, builds an unsigned non-debuggable candidate, then signs and publishes a prerelease with APK, SHA256SUMS and build-info.json.

Version codes increase even when an automation tag fails validation and produces no downloadable release. Do not reset version codes or move a published release tag. A rejected APK identity, signature, test or lint check prevents publication. Assets are attached to a draft before it is published.

## Signing and existing installations

The application ID remains `dev.distillpod.android`. Personal-alpha APKs retain the signing certificate used by the existing owner installation, so they can install as updates. Its public SHA-256 fingerprint is:

```text
5f384697c782acfaba9db54bca0149b1a7f59bfb9b21fcef70360b3c392fc5c7
```

This is the retained local development identity for continuity, not a newly created production identity. A future public-store signing transition needs an explicit migration plan. Do not substitute a fresh runner debug key or assume an unrelated signing key can update an installed app.

The `alpha-release` GitHub environment stores `ALPHA_KEYSTORE_BASE64`, `ALPHA_KEYSTORE_PASSWORD`, `ALPHA_KEY_ALIAS`, and `ALPHA_KEY_PASSWORD` as encrypted secrets. It permits release tags. The keystore is decoded only in the signing step, stored in a private temporary file and deleted on exit. It is excluded from Git, logs and artifacts. Keep a separate private backup: losing this identity affects future update compatibility.

Distribution builds receive the `FIREBASE_CONFIG_JSON` repository secret through the reusable validation workflow. It contains the retained Android Firebase client configuration; a missing value fails the release build instead of silently disabling sync. It is written only to a private runner-temporary file and removed afterward. Ordinary PR/debug/emulator builds remain unconfigured and receive no production Firebase configuration.

The release job checks APK application ID, version name/code, non-debuggable state, 16 KB native-library alignment and the actual signing certificate. It publishes a build-info record with commit and workflow URL. Fork pull requests and ordinary CI do not receive signing secrets.

## Build or inspect locally

```sh
./gradlew :app:lintRelease :app:assembleRelease
python3 scripts/release_metadata.py --tag v0.10.1-alpha.1
```

The local candidate is `app/build/outputs/apk/release/app-release-unsigned.apk`. It must be signed before installation. The repository has no signing password or keystore configuration in Gradle. This local command omits Firebase configuration; add `-PdistillpodFirebaseConfig=/absolute/path/google-services.json` for a configured candidate. The publishing workflow requires that configuration even though local builds can omit it.

Verify a downloaded release with `sha256sum --check SHA256SUMS`, then inspect it with Android Build Tools `apksigner verify --verbose --print-certs <apk>`. The workflow runs the same metadata guard used by `scripts/release_metadata.py` before and after signing.

CI debug artifacts are temporary test builds. They are not distributed as phone updates because their signing certificates differ across runners. The release APK is the upgrade artifact.
