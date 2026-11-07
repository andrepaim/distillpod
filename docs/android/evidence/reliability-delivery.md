# Reliability analytics delivery evidence

Date: 2026-11-07. Initial physical application source: `6e8d6de` (feature branch). The final journal-ordering update and copy changes follow in this PR; the release tree is identified by Git/build-info. Candidate: 0.18.0-alpha.1, code 35. Publication and merged-commit CI are separate gates.

## Automated local checks

- 91 core and 337 app JVM tests passed for the configured voice-enabled candidate, including independent consent, revocation, retry/replay identity, clock changes, restart reconciliation, unknown outcomes and text-before-output-failure behavior.
- Voice-enabled debug lint/assembly and configured release lint/assembly passed. Default voice-disabled app JVM tests, debug lint/assembly also passed.
- 24 Python tooling tests passed, including duplicate delivery, retry recovery, outstanding/unknown jobs and denominator fixtures.
- Candidate APK passed release metadata/signature checks against the retained certificate, voice/car configuration checks, Firebase resource comparison with the published baseline, disabled paid-content sync and six native ELF 16 KB alignment checks.

The first emulator CI run exposed a race between pause and initial transcript progress publication. Startup registration/state publication now shares the controls mutex with pause; the existing native pause/resume acceptance test passed on the fixed revision in [Android CI 42378711638](https://github.com/andrepaim/distillpod/actions/runs/42378711638). Native, Firebase, Kotlin/lint/APK and isolated emulator/voice acceptance jobs all passed.

The final ordering tests also cover expiry followed by completion, consent revocation/reopening, and compatibility with earlier journal files. A content-free global sequence counter is retained when record contents are cleared.

## Cloud checks

- Five dashboards were created, then updated from the checked-in definitions, with widget read-back verified. All 17 underlying widget queries executed successfully against Sentry's Explore Logs API.
- Numeric/boolean fields require `tags[field,number]` / `tags[field,boolean]`. Tests caught and corrected untyped filtering that otherwise silently returned zero rows. Numeric widget aggregations also use typed references.
- A desktop-generated, staging-only synthetic fixture delivered five records representing four unique operation revisions (including a duplicated completion). Cloud aggregation returned two running revisions, one failure and one completion; the duplicate completion had raw count two and distinct summary count one. This validates cloud deduplication/query semantics, not phone operation execution.
- The production export/report tool retrieved the S22 candidate's startup operation and classified it as outstanding with no resolved success rate, rather than treating it as successful. Exports stay in private operator storage.
- Production-only owner-targeted alerts were created and their configuration read back: repeated failures (five-event/hour threshold) and new/regressed unhandled crashes, both excluding synthetic events and using a 60-minute cooldown. No notification or deliberate crash was forced on the owner phone.

## Physical S22

- Installed the configured, retained-signature code-35 candidate in place over the existing code-33 installation. ADB reported installation success. No uninstall, app-data reset, library import or paid AI request was performed.
- Launched the updated app; its existing diagnostic preference was retained and an operation summary arrived remotely under the new production release identity. This proves physical-device upgrade/startup ingestion, not real transcription inference or headphone delivery.
- On-screen settings checks passed: diagnostic consent was retained, usage sharing defaulted off, enabling usage did not disable diagnostics, and usage was restored off. The real app's test button emitted handled errors and retry summaries observed in Sentry under code 35: two completed synthetic jobs with four distinct running revisions and two failed revisions. These are synthetic operation states, not real transcription inference.
- With networking temporarily disabled, the queue contained 15 envelopes and retained them across a force-stop/restart (21 envelopes afterward, including startup/reconciliation events). After restoring networking, Sentry returned all four revisions for the operation created during the offline test: two running, one failed and one completed. Original operation start time distinguished it from earlier tests. The production export correctly excluded these synthetic summaries.
- Original Wi-Fi/mobile-data enablement and plugged-in screen setting were restored and verified. No secure lock screen was dismissed by automation.

Final source integration, exact merged-commit checks and publication are recorded separately by the PR, CI and release build-info record. Historical physical headphone/car tests do not validate every later revision. Native Whisper crash collection, inferred word accuracy and long-term monthly history are not claimed.
