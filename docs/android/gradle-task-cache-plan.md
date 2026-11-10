# Gradle task-output caching (#89)

Base: main `02f124b9535602a9e3914fdaa84b8f84267ca5a2`.
Keep this session on one active issue. PR #93/plugin and release drafts remain
owned elsewhere. No release or device action is part of this increment.

## Audit and chosen behavior

Pinned Gradle 8.13, Kotlin 2.1.10 and AGP 8.9.1 use JDK17/API35. Tracked
configuration does not enable task-output caching. The pinned setup-gradle
6.4.1 action already transports the local build cache through GitHub Actions
cache, but transporting dependencies does not enable task-output reuse.
Its existing enhanced provider is free for this public repository; retain it.
No configuration cache, remote Gradle service, runner or job topology change.

A real isolated Gradle8.13 probe found cache mode false by default, true with
`GRADLE_OPTS=-Dorg.gradle.caching=true`, and false again with the explicit
`--no-build-cache` override. Enable that property only in the two Android CI
jobs, including their existing fixture scripts. Keep owner/local defaults
unchanged. Make setup-gradle uploads explicitly read-only except ordinary main
pushes without distribution inputs. Manual/reusable/PR runs may read public
entries. Distribution assembly receives client configuration, so explicitly
use `--no-build-cache`; its entire job also remains read-only when distribution
inputs are present. Preserve pinned actions, permissions and release artifacts.

Generated extractor preparation declares version/SHA/output, verifies the pinned
binary, and is an ad-hoc task: do not force it cacheable. Android resource tasks
must track its generated resource tree. Voice changes resource/manifest values;
fixtures change instrumentation assets. Native CMake inputs include pinned
Whisper sources; do not add unsafe cache annotations to unsupported native tasks.
Startup validation also changes package IDs/manifests/sources and must remain
isolated from ordinary APKs when sharing task cache entries.

## Test-first validation and measurements

1. Before editing CI, assert real output-cache reuse with the current disabled
   invocation and record the expected red result, using public synthetic inputs
   in an owned probe. Then enable the chosen CI property and require FROM-CACHE
   reuse and correct output values. Test that distribution override stays off.
2. Benchmark the actual Android workload in fresh owned experimental checkouts
   sharing a dedicated task-cache directory. Preserve user worktrees; use no Git
   reset/clean or source overwrites. Distinguish cold output cache, warm output
   cache, UP-TO-DATE and executed tasks. Prepare public dependencies/fixtures
   outside timing and record revision, flags, task outcomes and elapsed time.
3. Exercise source, ordinary/generated resources, build configuration, default/
   voice, fixture content and startup package variants. Inspect actual APK values,
   assets, package/target identities and the pinned extractor hash. Changes must
   invalidate affected outputs; a warmed cache must never return stale values.
   Preserve checksum rejection/repair rather than accepting unverified extractor
   bytes. Record non-cacheable tasks and any path/host/workload limitations.
4. Keep supported improvements only. Run both complete local unit/lint/APK gates,
   workflow/Python/data/security checks, and all existing hosted startup/ordinary/
   cloud/voice acceptance on the final head. Review/integrate current main, merge
   normally and verify combined-main CI. Benchmark an additional successful
   same-revision manual run after trusted main populates the cache, read-only,
   to observe hosted warm reuse. This is a benchmark, not retrying a failure.
5. Report baseline/cold/warm samples and cache transport overhead where observable.
   Job intervals are not billed minutes; unavailable billing and small sample
   sizes must remain explicit. No unmeasured speedup or reliability claims.

## Risk and sources

Wrong keys can hide regressions or select the wrong APK configuration. Actual
invalidation/artifact checks plus unchanged complete hosted acceptance are
required. No runtime/signing/upgrade/persistence/OEM behavior changes, so a
physical phone adds no relevant evidence to this CI-only change. Reassess any
scope growth; never use owner data, credentials or paid services for benchmarks.

Primary references: [Gradle8.13 cache contract](https://github.com/gradle/gradle/blob/v8.13.0/platforms/documentation/docs/src/docs/userguide/optimizing-builds/build-cache/build_cache.adoc),
[pinned action cache contract](https://github.com/gradle/actions/blob/8da46b539cd8fe6ab27ca75128919a18d757604c/docs/setup-gradle.md),
[pinned action inputs](https://github.com/gradle/actions/blob/8da46b539cd8fe6ab27ca75128919a18d757604c/setup-gradle/action.yml),
and [AGP8.9 compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes).
