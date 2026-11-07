# Reliability analytics delivery evidence

Date: 2026-11-07. Application source: `254a3dc` (feature branch); later dashboard/tooling/documentation revisions are recorded by Git. Candidate: 0.18.0-alpha.1, code 35. Publication and merged-commit CI are separate gates.

## Automated local checks

- 91 core and 332 app JVM tests passed for the configured voice-enabled candidate, including independent consent, revocation, retry/replay identity, clock changes, restart reconciliation, unknown outcomes and text-before-output-failure behavior.
- Voice-enabled debug lint/assembly and configured release lint/assembly passed. Default voice-disabled app JVM tests, debug lint/assembly also passed.
- 24 Python tooling tests passed, including duplicate delivery, retry recovery, outstanding/unknown jobs and denominator fixtures.
- Candidate APK passed release metadata/signature checks against the retained certificate, voice/car configuration checks, Firebase resource comparison with the published baseline, disabled paid-content sync and six native ELF 16 KB alignment checks.

## Cloud checks

- Five dashboards were created, then updated from the checked-in definitions, with widget read-back verified. All 17 underlying widget queries executed successfully against Sentry's Explore Logs API.
- Numeric/boolean fields require `tags[field,number]` / `tags[field,boolean]`. Tests caught and corrected untyped filtering that otherwise silently returned zero rows. Numeric widget aggregations also use typed references.
- A desktop-generated, staging-only synthetic fixture delivered five records representing four unique operation revisions (including a duplicated completion). Cloud aggregation returned two running revisions, one failure and one completion; the duplicate completion had raw count two and distinct summary count one. This validates cloud deduplication/query semantics, not phone operation execution.
- The production export/report tool retrieved the S22 candidate's startup operation and classified it as outstanding with no resolved success rate, rather than treating it as successful. Exports stay in private operator storage.
- Production-only owner-targeted alerts were created and their configuration read back: repeated failures (five-event/hour threshold) and new/regressed unhandled crashes, both excluding synthetic events and using a 60-minute cooldown. No notification or deliberate crash was forced on the owner phone.

## Physical S22

- Installed the configured, retained-signature code-35 candidate in place over the existing code-33 installation. ADB reported installation success. No uninstall, app-data reset, library import or paid AI request was performed.
- Launched the updated app; its existing diagnostic preference was retained and an operation summary arrived remotely under the new production release identity. This proves physical-device upgrade/startup ingestion, not real transcription inference or headphone delivery.
- On-screen consent/status checks and the UI synthetic report check are awaiting owner unlock. A secure lock screen is not dismissed by automation.

CI emulator acceptance, final source integration, exact merged-commit checks and publication are recorded separately when complete. Historical physical headphone/car tests do not validate every later revision. Native Whisper crash collection, inferred word accuracy and long-term monthly history are not claimed.
