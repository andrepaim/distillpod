# Signed alpha APK releases

Version name and monotonically increasing version code live in `gradle.properties`. Current releases are personal-alpha prereleases, not Play Store production releases. Source versions and private phone builds can precede a published APK; `docs/releases/current.md` is the release-note input for the next matching tag, not proof that the APK has been published. See [implementation status](android/implementation-status.md) and [GitHub Releases](https://github.com/andrepaim/distillpod/releases).

The Google Play build of a release tag is a separate, manually started path that produces a signed app bundle for a hand upload in Play Console; it publishes nothing. See [Google Play bundle](#google-play-bundle).

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

- Wait for the **Android CI main-push run on that exact SHA** to pass. A green PR run or an earlier main run does not satisfy this gate. Review failures instead of bypassing them. That run can look different from a full run, or be absent:
  - **Reused route.** A direct single-merge push whose tree is identical to a recent successful full PR run can take the `reused` route: the plan job logs `Validation route: reused` with the proof run, the build, Firebase, native and emulator jobs are skipped, and the `Kotlin, lint and APK` gate passes only after verifying that proof again. See [identical merged-tree validation](development.md#identical-merged-tree-validation). This is code-equivalence evidence from the PR run, not a new execution of the tests.
  - **No run.** The Android CI push trigger ignores documentation-only paths (`paths-ignore` in `.github/workflows/ci.yml`; see [workflows](development.md#workflows)). If the push that put the SHA on `main` changed only those paths, no Android CI main-push run exists for that SHA.
  - **Cancelled run.** Android CI runs on `main` share one concurrency group with `cancel-in-progress: true`, so a later push to `main` or a manual Android CI dispatch on `main` cancels a main-push run that is still in progress. GitHub does not restart the cancelled run.

  In the last two cases no passing main-push run exists for that SHA. Publish APK runs full validation for the tag whichever route the main-push run took.
- Confirm the required signing secrets, the Firebase client configuration (`FIREBASE_CONFIG_JSON`) and the Sentry client DSN (`SENTRY_DSN`) are available to the release workflow without exposing their values. Publish APK independently reruns validation and adds release lint/assembly before signing; its distribution build fails when `FIREBASE_CONFIG_JSON` or `SENTRY_DSN` is missing.

### 5. Tag and publish

- Verify the candidate, version, notes, and authorization once more. Ensure the chosen tag is unused locally and remotely. Create an annotated tag pointing to the validated SHA and push only that tag:

  ```sh
  git tag -a "$release_tag" "$release_sha" -m "DistillPod $release_tag"
  git push origin "refs/tags/$release_tag"
  ```

- Follow the **Publish APK** run for that tag through completion. It verifies the source version and main ancestry, reruns validation, builds an unsigned non-debuggable candidate, then signs and publishes a prerelease with six assets: the signed APK (`distillpod-<version>.apk`), the corresponding source tarball (`distillpod-<version>-source.tar.gz`), `SHA256SUMS`, `build-info.json`, `THIRD_PARTY_NOTICES.md` and `source-manifest.json`. `SHA256SUMS` lists the other five.
- If a run fails, inspect its failure and any draft/assets before retrying. Retry the same tag only for the same immutable source. A rerun of the publish job checks the tag's release first: with no release it creates the draft as usual; with exactly one draft it re-signs, replaces every asset of that draft with the newly built ones (`gh release upload --clobber`) and publishes it; with a published release, more than one draft, a draft holding other asset names, or a failed lookup it stops without changing anything. The unsigned APK artifact is kept for one day, so after that a rerun of only the failed jobs cannot download it; rerun all jobs instead, which revalidates and rebuilds the same tag. If source/version changes are needed, prepare a new version/code and tag through this checklist. Do not replace published assets or move a published tag to conceal a failed release.

### 6. Verify publication and report

- Open the resulting GitHub Release and confirm it is published as a prerelease, its notes match the tagged source, and all expected assets are downloadable. A green build or a draft alone is not publication.
- Download all six assets into a new, empty directory and check them there. Without `--pattern`, `gh release download` fetches every asset (the source tarball is the largest). Set `release_dir` to that directory:

  ```sh
  gh release download "$release_tag" --repo andrepaim/distillpod --dir "$release_dir"
  (cd "$release_dir" && sha256sum --check SHA256SUMS)
  ```

  The check must print `OK` for all five listed files. It fails if any of them is missing, so a partial download does not pass. Confirm the build-info commit and workflow URL identify the validated source and successful publication run.
- Inspect the downloaded APK (`downloaded_apk="$release_dir/distillpod-${release_tag#v}.apk"`) with the tagged source's release guard, using the Android Build Tools directory for `build_tools`:

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

Distribution builds also receive the `SENTRY_DSN` repository secret, the public client DSN for [cloud diagnostics](android/cloud-diagnostics.md), through the same reusable validation workflow. It reaches the release Gradle build as an environment variable; a missing value fails the release build. Ordinary PR/debug/emulator builds receive no DSN.

The 0.16.0 personal-alpha publishing configuration enables the voice preview explicitly with `-PdistillpodVoiceEnabled=true` during release lint/assembly. Source/debug defaults remain disabled. This is a distribution choice, not a stored user preference: headphones still require explicit setup and opt-in. Verify the downloaded APK's voice resource and car-service enablement alongside its metadata when publishing this configuration.

The release job checks APK application ID, version name/code, non-debuggable state, 16 KB native-library alignment and the actual signing certificate. It publishes a build-info record with commit and workflow URL. Fork pull requests and ordinary CI do not receive signing secrets.

## Build or inspect locally

```sh
./gradlew :app:lintRelease :app:assembleRelease -PdistillpodVoiceEnabled=true
python3 scripts/release_metadata.py
```

The local candidate is `app/build/outputs/apk/release/app-release-unsigned.apk`. It must be signed before installation. The repository has no signing password or keystore configuration in Gradle. This local command omits Firebase and Sentry configuration; for a configured candidate, add `-PdistillpodFirebaseConfig=/absolute/path/google-services.json` and provide `SENTRY_DSN` in the build environment without printing it. The publishing workflow requires both even though local builds can omit them.

Verify a downloaded release by running `sha256sum --check SHA256SUMS` in a directory that holds all six assets, as in step 6. To check only the APK, keep its published file name, place it next to `SHA256SUMS` and run `sha256sum --ignore-missing --check SHA256SUMS` (GNU coreutils): it checks the listed files that are present, and fails on a mismatch or when none of them is present. Then inspect the APK with Android Build Tools `apksigner verify --verbose --print-certs <apk>`. The workflow runs the same metadata guard used by `scripts/release_metadata.py` before and after signing.

CI debug artifacts are temporary test builds. They are not distributed as phone updates because their signing certificates differ across runners. The release APK is the upgrade artifact.

## Google Play bundle

Google Play accepts new apps only as Android App Bundles (`.aab`) with Play App Signing. The **Play bundle** workflow (`.github/workflows/play-bundle.yml`) builds the [Play distribution](development.md#distribution-switches) of an existing release tag, signs it with a Play **upload key** and verifies it, then offers it as a workflow artifact. The owner uploads it by hand in Play Console ([listing steps](../store/play/listing.md#steps-in-the-console)). The workflow uploads nothing to Google Play and creates or changes no GitHub Release; Publish APK and its assets are unaffected. The tracking issue for the owner's Play tasks is [#326](https://github.com/andrepaim/distillpod/issues/326).

### What the workflow does

It runs only by hand, from the release tag, with the same tag as input. The workflow file is read from the tag, so tags created before this workflow existed cannot use it.

1. **Verify tagged source:** the run's ref is the tag named in the input, the tag points to the run's commit, that commit is on `main`, and `gradle.properties` gives that tag (`python3 scripts/release_metadata.py --tag`), as Publish APK checks.
2. **Require the Play upload key** (environment `play-upload`): fails within seconds, before validation and the build, if any of the four secrets or the fingerprint variable below is missing. It prints which names are missing, never their values.
3. **Validation:** the reusable Android CI workflow with full validation, as Publish APK runs it, including the Play distribution job. It is called with `build_release: false`, so the GitHub APK candidate is not built and no secret is passed to it.
4. **Unsigned Play bundle:** `:app:lintRelease :app:bundleRelease -PdistillpodDistribution=play -PdistillpodVoiceEnabled=true` with `--no-build-cache`, the Firebase client configuration from `FIREBASE_CONFIG_JSON` (a private temporary file removed afterwards) and `SENTRY_DSN`, exactly as the APK candidate gets them; a missing value fails the build. No YouTube extractor is fetched. The unsigned bundle is verified (below, with `--unsigned`) and kept as a one-day artifact.
5. **Sign and verify Play bundle** (environment `play-upload`): the signing step decodes the keystore into a private runner-temporary file that is deleted when the step exits, and signs with `jarsigner` (bundles carry a JAR signature; `apksigner` does not apply). A later step, with no secret in its environment and the keystore gone, verifies the signed bundle in full and uploads the artifact `distillpod-play-bundle-<tag>`, kept for 7 days:
   - `distillpod-<version>-play.aab`, the bundle to upload;
   - `play-build-info.json`: application ID, version name and code, tag, target SDK, distribution and switch values, Gradle properties, upload certificate SHA-256, bundletool version, commit and workflow run URL;
   - `THIRD_PARTY_NOTICES.md` and `source-manifest.json`, copied out of the bundle: the Play variant that `prepareDistributionLicenses` filters, without the YouTube components;
   - `SHA256SUMS`, listing the other four.

`scripts/verify_play_bundle.py` checks the bundle itself for its signature files, the JAR signature (`jarsigner -verify -strict`, accepting only the findings every self-signed upload certificate produces) and exactly one signer whose certificate has the expected SHA-256 fingerprint and an RSA key of at least 2048 bits. It runs `bundletool validate`, builds the universal APK with bundletool [1.18.3](https://github.com/google/bundletool/releases/tag/1.18.3) (the jar's SHA-256 is pinned in the script and the workflow) and checks that APK with the same guards as the GitHub APK: package `dev.distillpod.android`, version name and code from the tagged source, not debuggable, target SDK 36, the Play distribution switches (`scripts/verify_distribution_apk.py`: no YouTube extraction, no update check), 16 KB ELF LOAD-segment alignment of every native library, and `zipalign -c -P 16 4`. The Play variant is checked by bundletool's universal APK rather than by teaching the distribution check the bundle layout, so the same code checks both distributions on the APK form Android installs.

### Before the first run

These are owner tasks; the workflow fails at its first job until they are done. Keep the keystore and its password outside the repository, in a private backup.

1. Create the upload key in a private directory. It is new and separate from the personal-alpha key, and is not the app signing key. `keytool` asks for the password; a PKCS12 keystore uses the same password for the store and the key.

   ```sh
   keytool -genkeypair -keystore distillpod-play-upload.p12 -storetype PKCS12 \
     -alias distillpod-upload -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
     -validity 10000 -dname "CN=DistillPod upload key"
   ```

2. Read its certificate fingerprint and export the public certificate, which Play Console asks for when an upload key is registered or reset:

   ```sh
   keytool -list -v -keystore distillpod-play-upload.p12 -alias distillpod-upload \
     | sed -n 's/^[[:space:]]*SHA256: //p' | tr -d : | tr A-F a-f
   keytool -exportcert -rfc -keystore distillpod-play-upload.p12 -alias distillpod-upload \
     -file distillpod-play-upload-certificate.pem
   ```

3. Create the GitHub environment `play-upload` (Settings → Environments). Under deployment branches and tags choose selected branches and tags and add the tag rule `v*`. A required reviewer is optional; with one, each run waits for approval twice (the key check and the signing job).
4. Add the secrets to that environment and the fingerprint as an environment variable. `gh secret set` without `--body` prompts for the value without echoing it, so passwords stay out of shell history; `base64 -w0` is GNU coreutils.

   ```sh
   repo=andrepaim/distillpod
   base64 -w0 distillpod-play-upload.p12 | gh secret set PLAY_UPLOAD_KEYSTORE_BASE64 --env play-upload --repo "$repo"
   gh secret set PLAY_UPLOAD_KEYSTORE_PASSWORD --env play-upload --repo "$repo"
   gh secret set PLAY_UPLOAD_KEY_PASSWORD --env play-upload --repo "$repo"   # same password for PKCS12
   gh secret set PLAY_UPLOAD_KEY_ALIAS --env play-upload --repo "$repo" --body distillpod-upload
   gh variable set PLAY_UPLOAD_CERT_SHA256 --env play-upload --repo "$repo" --body "<fingerprint from step 2>"
   ```

   The fingerprint is public, so it is a variable, not a secret; the build record and the log show it. A repository variable of the same name also works, because only jobs in `play-upload` read it, but keeping it in the environment keeps it next to the key it describes. Colons and upper case are accepted.

`FIREBASE_CONFIG_JSON` and `SENTRY_DSN` are the repository secrets Publish APK already uses; nothing new is needed for them.

### Build a bundle for a tag

After the tag exists (step 5 of the [publishing checklist](#5-tag-and-publish)), start the workflow from that tag. It does not depend on Publish APK and can run before, after or without it.

```sh
gh workflow run play-bundle.yml --repo andrepaim/distillpod --ref "$release_tag" -f tag="$release_tag"
```

In the web interface: Actions → Play bundle → Run workflow, choose the tag under **Use workflow from**, and enter the same tag. A run takes about as long as full Android CI. A rerun builds the same tag again and changes nothing outside its own artifacts. The unsigned bundle is kept for one day, so after that rerun all jobs rather than only the failed ones.

### Verify the downloaded bundle

Download the artifact into a new, empty directory and check the checksums there (`run_id` is the workflow run's number):

```sh
gh run download "$run_id" --repo andrepaim/distillpod --name "distillpod-play-bundle-$release_tag" --dir "$bundle_dir"
(cd "$bundle_dir" && sha256sum --check SHA256SUMS)
```

Then, from a checkout of the tag, with `JAVA_HOME` set to JDK 17, `build_tools` set to the Android Build Tools 35.0.0 directory and the pinned bundletool in a separate directory:

```sh
curl --fail --location --output "$tools_dir/bundletool.jar" \
  https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar
echo "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29  $tools_dir/bundletool.jar" | sha256sum --check
python3 scripts/verify_play_bundle.py "$bundle_dir/distillpod-${release_tag#v}-play.aab" --tag "$release_tag" \
  --certificate-sha256 "$upload_fingerprint" --bundletool "$tools_dir/bundletool.jar" --build-tools "$build_tools"
```

`keytool -printcert -jarfile <bundle>` shows the signer; after the first upload its SHA-256 must equal the upload certificate Play Console lists on its App signing page. Upload only a bundle that passes both checks.

### Version codes

GitHub APKs and Play bundles share one increasing sequence of version codes from `gradle.properties`. The bundle of a tag carries that tag's code; there is no separate Play code. Play refuses a version code it has already received for the app, on any track and even if that upload was never released, so each tag's bundle can be uploaded once. A rebuilt or corrected bundle needs a new version code and tag through the [publishing checklist](#publishing-checklist). Tags that never reach Play leave gaps in Play's sequence, which is allowed. Keeping one sequence also lets an install from either channel update from the other if Play signs with the personal-alpha identity (see below).

### Play App Signing choice

Play re-signs what users install with an app signing key that Play holds. Which key that is remains the owner's decision ([listing](../store/play/listing.md#before-the-first-upload), row 5, and #326), and it must be made in Play Console before any release leaves internal testing: a Google-generated key, or the existing personal-alpha key uploaded with Google's PEPK tool so that GitHub and Play installs share one identity. Either way this workflow signs only with the upload key. Never store the personal-alpha key, or a copy of the app signing key, in `play-upload`.

### Rotate the upload key

- **Before Play has an upload key** (no bundle uploaded yet): create a new key as above, replace the four secrets and the variable, and delete the old keystore.
- **After the first upload**, or when the key is lost or exposed: create a new key and export its certificate as above, then request an upload key reset on Play Console's App signing page with that certificate. When Play reports the new key in effect, replace the four `play-upload` secrets and `PLAY_UPLOAD_CERT_SHA256`, run the workflow for the next tag, and check that the build record shows the new fingerprint. Delete the old keystore and its backups afterwards. Until the reset takes effect, Play rejects bundles signed with the new key.

### Limits

- Nothing reaches Google Play automatically; the first acceptance of the upload key and the bundle is Play Console's.
- The signing secrets and the fingerprint check can be exercised only by a real run once the owner has added them; locally they were tested with a throwaway key.
- The artifact has no source archive. The app is GPL-3.0; the corresponding source is the tagged commit, and Publish APK's source tarball for the same tag (`scripts/source_distribution.py`) also covers the Play build, which contains a subset of the GitHub build's components.
