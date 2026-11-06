# The retired DistillPod web application

DistillPod now develops the native Android app in the same GitHub repository. The prior Python/FastAPI and React/Vite server application is frozen on `legacy/web`. The exact final web source is tagged `web-legacy-final` at commit `758cbc07f6d35b39d39a4fc66c57553e00297b79`.

- [Browse the archived web branch](https://github.com/andrepaim/distillpod/tree/legacy/web)
- [Browse the exact final web snapshot](https://github.com/andrepaim/distillpod/tree/web-legacy-final)

The archive retains its old README, backend/frontend, Docker configuration, tests and history. It is no longer the actively maintained product. Its feature descriptions, Claude CLI/server integration, research tools and hosting instructions do not apply to native Android.

To inspect the old version alongside Android:

```sh
git fetch origin --tags
git worktree add ../distillpod-web web-legacy-final
```

The old web deployment is retired. Its README is a historical setup reference,
not current deployment guidance. A source archive is not a server-data backup:
database files, downloaded media, deployment credentials and service
configuration require separate handling. Migration recovery snapshots were
kept outside the current Android tree; preserving Git ancestry does not remove
data committed earlier. Do not treat the archive as a sanitized dataset.

The Android app has a phone-owned library. It does not read or migrate the old server database automatically. OPML moves subscriptions; Android's library backup/restore moves Android library data.

The migration uses ordinary commits and preserves ancestry; existing published history is not rewritten. The default branch is now `main`, containing the Android source. The legacy branch and final tag provide independent named recovery points for the old application.
