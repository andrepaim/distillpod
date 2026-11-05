# Android Auto TDD receipt

Date: November 5, 2026. Branch: `feat/android-auto`. Initial baseline `069a1d0`; integrated baseline `93a2238` retains the social preview already installed on the test phone. Implementation and builds use `/home/andrepaim/src/distillpod-android-auto`.

## Test first changes

| Behavior | Observed red result | Implementation and green check |
| --- | --- | --- |
| Four sections, ordering, queue, subscribed shows, pagination, valid downloads and invalid requests | Catalog tests could not compile before the catalog existed | Bounded Room catalog; nine catalog tests passed |
| Modern/platform discovery and automotive metadata | Both assertions failed before manifest declarations | MediaLibraryService and automotive XML; both tests passed |
| Legacy unlimited page request | Root expected four items but returned zero | Bounded unlimited-request handling passed |
| Large library and continuation navigation | Missing continuation API, followed by a behavioral failure when the client changed paging mode | 208 items traversed without omissions/duplicates; continuation works with normal paging |
| Queue selection, accent-insensitive show/episode requests and deferred capture preparation | Missing playback/save APIs | Playback policy and durable save tests passed |
| ID-only resume, voice request metadata, subscription acceptance and resumption | Session tests failed against inherited defaults | Async callbacks resolve authoritative IDs and saved positions; session tests passed |
| Generic request with more than 100 completed newer episodes | Returned no unfinished episode | Dedicated newest-unfinished query passed |
| Cached cover available offline and traversal rejected | Missing artwork cache API | Bounded thumbnail cache, placeholder and narrow provider tests passed |
| Actual car seek command authorization | Unit test failed: trusted car controller lacked the advertised custom seek commands | Grant legacy seek commands to trusted controllers; unit and emulator seek assertions passed |

Additional tests cover untrusted-controller rejection, requests waiting for startup restoration, and cancellation while waiting. These protect authorization and asynchronous startup independently of the phone UI.

## Integrated checks

The final integrated suite passed **288 JVM tests** (85 core and 203 app), including the three review cases. Lint, debug APK, instrumentation APK, and release APK assembly passed. The complete final rerun and focused backup recheck passed after transient timeouts in three existing backup tests; backup implementation was unchanged.

A dedicated API 36.1 emulator passed three real browser tests: modern browse/select/resume/paused capture, platform legacy subscribe/browse without an Activity, and subscribed folder refresh. Separate prepare/verify instrumentation processes around an actual force-stop passed one test each. The final browser test includes exact −10s and +30s execution, rather than checking icons alone.

`android-auto-tests.sh` refuses physical devices. The normal emulator runner includes the browser class and runs the separate restart class afterward. Python bootstrap tests copy the new helper and verify the phase calls and force-stop. All 11 Python tests passed.

## Issues caught during validation

Lint caught deprecated error constants, a missing media-search intent declaration, and an artwork stream API requiring Android 13. All were corrected for the Android 10 minimum. The artwork cache now notifies subscribed folders after a cover finishes loading.

The S22 Desktop Head Unit test revealed that a legacy car controller could see the retained notification seek actions without permission to invoke them. The regression test reproduced this authorization mismatch; trusted controllers now receive those actions while episode-navigation commands remain separate.

## Evidence boundaries

Initial cached JVM results were only a baseline checkpoint; the integrated suites subsequently executed against an isolated checkout. The signed phone upgrade retained the signing identity and client configuration, and private backup comparison confirmed library preservation before head-unit interactions.

A concurrent social-preview installation replaced the phone's Android Auto preview during later testing. Corrected-build head-unit checks are pending coordinated phone access. Speech recognition/app-name routing, actual vehicle/wireless checks, Bluetooth/call/navigation interruptions, the real 60-minute screen-locked test and 10,000-episode target timings are not represented as passed. See [validation](android-auto-validation.md).
