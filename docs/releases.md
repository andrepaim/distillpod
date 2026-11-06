# Signed alpha APK releases

Version name and monotonically increasing version code live in `gradle.properties`. Current releases are personal-alpha prereleases, not Play Store production releases. Source versions and private phone builds can precede a published APK; `docs/releases/current.md` is the release-note input for the next matching tag, not proof that the APK has been published. See [implementation status](android/implementation-status.md) and [GitHub Releases](https://github.com/andrepaim/distillpod/releases).

## Release policy

Merge validated changes into `main` frequently; publish an APK when there is a useful build to install or distribute. One release may contain one feature or several. Merging or pushing to `main` does not trigger publication: Android CI runs on pull requests and main pushes, while Publish APK runs on version-tag pushes.

One designated release owner coordinates the entire checklist below, including version fields, release notes, tags, and publication. Other sessions can continue feature work in separate worktrees. An explicit request to publish an APK authorizes completion through publication and verification; it does not require another routine confirmation. A request to finish or merge a feature alone does not authorize publication. See [agent instructions](../AGENTS.md).

## Publishing checklist

### 1. Establish the release scope

- Fetch `origin` and tags. Work in a clean, dedicated worktree based on current `origin/main`; preserve other sessions' branches and edits.
- Find the most recently published applicable Android alpha release with a downloadable APK. Include prereleases when inspecting GitHub Releases: do not rely on GitHub's stable-only latest-release shortcut, the highest version tag, a draft, or `docs/releases/current.md`.
- Resolve that release's tag to its source commit and cross-check its attached `build-info.json`. Record this baseline and the proposed release commit.
- Review the full commit history and code diff from that baseline to the candidate, together with merged PRs and validation evidence. For example, after setting `previous_tag` and `candidate_sha` to the verified values:

  ```sh
  git log --oneline "$previous_tag..$candidate_sha"
  git diff --stat "$previous_tag" "$candidate_sha"
  git diff "$previous_tag" "$candidate_sha"
  ```

- Account for new features, fixes, changed behavior, removals, migrations, dependencies, and known limitations across all included work. Check interactions between features, not just each feature's original test result. Unmerged work is outside the release.

### 2. Review compatibility and readiness

- Confirm that the candidate can update the existing installation: preserve application ID and signing identity, and choose a version code above every previously allocated release/private-phone build code. Review previous attempts as well as published releases; the installed build may be ahead of the last public APK.
- Review database migrations, backup/restore compatibility, and Firebase protocol/rule compatibility with older installed clients. Publishing an APK does not update every client immediately. Keep schema/protocol expansions disabled or defer publication when their prerequisites are unmet; coordinate required backend changes as separately authorized work.
- Confirm the distribution build retains required Firebase client configuration and intended feature-flag defaults. Verify any unfinished feature is safely disabled, including its background behavior.
- Identify relevant smoke checks for the changes, including in-place upgrade/data preservation when migrations change. Follow [development/device instructions](development.md) and feature-specific protocols. Use isolated emulators by default. Existing task authorization governs owner-phone installation and live-account testing; publication alone does not authorize them.
- Record automated, emulator, and physical-device evidence separately, with source revision and limitations. Do not imply that old device evidence or automated UI tests establish physical-device validation. Resolve release-blocking failures; describe nonblocking alpha limitations honestly.

### 3. Prepare the release commit

- Choose the next version and update `distillpodVersionName` and `distillpodVersionCode` in `gradle.properties`. The release tag must be exactly `v` followed by the version name.
- Rewrite `docs/releases/current.md` for the complete reviewed release scope. Lead with the user-visible result, then cover new features, fixes, behavior/compatibility changes, update instructions, and known limitations where relevant. Omit empty sections and internal implementation detail that does not help users.
- State validation accurately and identify meaningful checks still outstanding. Cross-check every release-note claim against the code and evidence; distinguish new changes from retained functionality. Never claim an unpublished asset is available.
- Update affected usage/status documentation as needed. Review the staged diff so the release commit contains only intended release preparation.
- Integrate the release preparation into `main`. If another change enters the candidate before it is fixed, review the additional scope and rerun affected checks. Record the exact resulting commit SHA; later main commits need not be included in this release.

### 4. Validate the exact candidate

- Run relevant checks from [development](development.md), including migration/backup/sync tests when those areas change, and complete the identified smoke checks. Record any unavailable device coverage as a limitation rather than a pass.
- Set `release_tag` to the selected version tag and `release_sha` to the exact candidate commit. From a checkout of that commit, run:

  ```sh
  git diff --check
  python3 scripts/release_metadata.py --tag "$release_tag"
  git merge-base --is-ancestor "$release_sha" origin/main
  ```

- Wait for the **Android CI main-push run on that exact SHA** to pass. A green PR run or an earlier main run does not satisfy this gate. Review failures instead of bypassing them.
- Confirm required signing secrets and Firebase configuration are available to the release workflow without exposing their values. Publish APK independently reruns validation and adds release lint/assembly before signing.

### 5. Tag and publish

- Verify the candidate, version, notes, and authorization once more. Ensure the chosen tag is unused locally and remotely. Create an annotated tag pointing to the validated SHA and push only that tag:

  ```sh
  git tag -a "$release_tag" "$release_sha" -m "DistillPod $release_tag"
  git push origin "refs/tags/$release_tag"
  ```

- Follow the **Publish APK** run for that tag through completion. It verifies the source version and main ancestry, reruns validation, builds an unsigned non-debuggable candidate, then signs and publishes a prerelease with APK, `SHA256SUMS`, and `build-info.json`.
- If a run fails, inspect its failure and any draft/assets before retrying. Retry the same tag only for the same immutable source when the workflow can safely resume. If source/version changes are needed, prepare a new version/code and tag through this checklist. Do not replace published assets or move a published tag to conceal a failed release.

### 6. Verify publication and report

- Open the resulting GitHub Release and confirm it is published as a prerelease, its notes match the tagged source, and all expected assets are downloadable. A green build or a draft alone is not publication.
- Download the APK, `SHA256SUMS`, and `build-info.json` into a dedicated directory. Run `sha256sum --check SHA256SUMS` there. Confirm the build-info commit and workflow URL identify the validated source and successful publication run.
- Inspect the downloaded APK with the tagged source's release guard, using the Android Build Tools directory for `build_tools`:

  ```sh
  python3 scripts/release_metadata.py --tag "$release_tag" --apk "$downloaded_apk" --build-tools "$build_tools" --verify-signature
  "$build_tools/zipalign" -c -P 16 4 "$downloaded_apk"
  ```

  This checks package/version metadata, non-debuggable state, retained signing certificate, and alignment. Do not substitute a CI debug artifact for the signed update APK.
- Report the release URL, version/code, source commit, user-visible highlights, validation results, and remaining limitations. Report any post-publication verification failure immediately and coordinate recovery; do not mark the release verified until those checks pass.

Version codes increase even when an automation tag fails validation and produces no downloadable release. Do not reset version codes or move a published release tag. A rejected APK identity, signature, test or lint check prevents publication. Assets are attached to a draft before it is published.

## Signing and existing installations

The application ID remains `dev.distillpod.android`. Personal-alpha APKs retain the signing certificate used by the existing owner installation, so they can install as updates. Its public SHA-256 fingerprint is:

```text
5f384697c782acfaba9db54bca0149b1a7f59bfb9b21fcef70360b3c392fc5c7
```

This is the retained local development identity for continuity, not a newly created production identity. A future public-store signing transition needs an explicit migration plan. Do not substitute a fresh runner debug key or assume an unrelated signing key can update an installed app.

The `alpha-release` GitHub environment stores `ALPHA_KEYSTORE_BASE64`, `ALPHA_KEYSTORE_PASSWORD`, `ALPHA_KEY_ALIAS`, and `ALPHA_KEY_PASSWORD` as encrypted secrets. It permits release tags. The keystore is decoded only in the signing step, stored in a private temporary file and deleted on exit. It is excluded from Git, logs and artifacts. Keep a separate private backup: losing this identity affects future update compatibility.

Distribution builds receive the `FIREBASE_CONFIG_JSON` repository secret through the reusable validation workflow. It contains the retained Android Firebase client configuration; a missing value fails the release build instead of silently disabling sync. It is written only to a private runner-temporary file and removed afterward. Ordinary PR/debug/emulator builds remain unconfigured and receive no production Firebase configuration.

The 0.16.0 personal-alpha publishing configuration enables the voice preview explicitly with `-PdistillpodVoiceEnabled=true` during release lint/assembly. Source/debug defaults remain disabled. This is a distribution choice, not a stored user preference: headphones still require explicit setup and opt-in. Verify the downloaded APK's voice resource and car-service enablement alongside its metadata when publishing this configuration.

The release job checks APK application ID, version name/code, non-debuggable state, 16 KB native-library alignment and the actual signing certificate. It publishes a build-info record with commit and workflow URL. Fork pull requests and ordinary CI do not receive signing secrets.

## Build or inspect locally

```sh
./gradlew :app:lintRelease :app:assembleRelease -PdistillpodVoiceEnabled=true
python3 scripts/release_metadata.py
```

The local candidate is `app/build/outputs/apk/release/app-release-unsigned.apk`. It must be signed before installation. The repository has no signing password or keystore configuration in Gradle. This local command omits Firebase configuration; add `-PdistillpodFirebaseConfig=/absolute/path/google-services.json` for a configured candidate. The publishing workflow requires that configuration even though local builds can omit it.

Verify a downloaded release with `sha256sum --check SHA256SUMS`, then inspect it with Android Build Tools `apksigner verify --verbose --print-certs <apk>`. The workflow runs the same metadata guard used by `scripts/release_metadata.py` before and after signing.

CI debug artifacts are temporary test builds. They are not distributed as phone updates because their signing certificates differ across runners. The release APK is the upgrade artifact.
