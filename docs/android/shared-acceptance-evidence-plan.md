# Shared acceptance evidence and cleanup (#69)

The existing hosted red evidence is recorded in #69: the download policy fixture
had no audio request, ProductFlow cleanup reported protected deletion, and the
storage fixture timed out after removing its selected partial file. These do not
establish one common cause or a production regression.

Before further diagnosis, preserve the original body error when cleanup fails.
For ProductFlow's own synthetic episode, cancel its unfinished work, pause its
transcription, remove it from playback and wait for both persisted queue and
active-file ownership release before invoking the existing guarded delete. Never
clear the entire library/queue or bypass a protection by rewriting its state.

Capture only bounded scheduler/episode enums and constraint/attempt/stop-reason
fields when the expected request is absent. On the final storage display timeout,
retain the original assertion and attach expected/current filesystem byte counts
and the fixture's existence/state flags. No content, URLs, identifiers or owner
device state belong in the diagnostic output. Preserve timeout bounds and causes.

Review the exact current-main diff, compile/lint/build default and voice variants,
run Python/security/data checks and require all five hosted checks. Normal PR
merge and combined-main CI are required. Record remaining initiating-cause gaps;
a passing run or added diagnostics alone do not prove an intermittent defect fixed.

Risk is limited to emulator fixture control and evidence: no runtime, schema,
account, permission, device setting, release or production behavior changes.
ProductFlow gains an explicit emulator guard; the storage fixture already has
one. No physical run is applicable to these disposable-emulator fixtures. Feature
phone acceptance gates remain unchanged. The shared Whisper source was copied
from an immutable pinned Git archive into this new worktree; other checkouts
and their build state were not changed.
