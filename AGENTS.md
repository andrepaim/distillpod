# Working on DistillPod

## Parallel development

- Use a separate short-lived branch and Git worktree for each independent change, starting from current `origin/main`. Never switch branches, alter unrelated edits, or run concurrent Gradle builds in another session's worktree.
- Keep changes small and merge completed work frequently. Split larger features into safe increments; use temporary feature flags when unfinished behavior needs to land disabled.
- Before merging, fetch current `main`, integrate intervening changes, review the combined diff, and rerun checks relevant to the resulting code. Separately passing feature branches do not establish that their combination works.
- Keep `main` releasable. Follow [development and validation instructions](docs/development.md), and verify CI on the combined commit. Fix or coordinate a revert of regressions promptly; do not publish a failing revision.
- Commit only the intended task files. Prefer pull requests for integration; an explicit instruction to commit and push to `main` authorizes that route. Never force-push `main` to resolve concurrent changes.

## Release ownership and authorization

- Feature completion, merging, and APK publication are separate decisions. A feature task or a push to `main` does not implicitly authorize a release.
- Designate one session as the release owner. It coordinates version fields in `gradle.properties`, `docs/releases/current.md`, release tags, and publication. Feature sessions document behavior and validation without independently changing release metadata unless assigned that work.
- An explicit request to publish a new APK authorizes the release owner to complete the [release checklist](docs/releases.md), including pushing the release commit/tag and publishing verified assets, without asking for the same authorization again. Report concrete blockers instead of bypassing checks.
- Every APK publication must follow `docs/releases.md`, the authoritative release procedure. Review all changes since the last published APK, including features, fixes, compatibility changes, and known limitations. Do not use stale release notes or treat an unpublished tag as a published release.
- Preserve in-place upgrade compatibility: application ID, retained signing identity, increasing version codes, local data migrations, backup formats, and sync behavior with older installed clients.
- Publication does not implicitly authorize installing on the owner's phone, destructive device tests, live-account actions, paid services, or backend/schema deployment. Follow existing task authorization and the relevant device/cloud instructions.

## Evidence

- Distinguish implemented behavior, automated checks, emulator checks, and physical-device validation. Record the revision and limitations of evidence; historical device results do not validate every later change.
- For documentation-only changes, review accuracy, local links, and `git diff --check`; a full local Android build is unnecessary unless build behavior also changes. CI still runs according to the repository workflows.
- Keep credentials, signing keys, personal content, and device state out of commits, logs, and CI artifacts.
