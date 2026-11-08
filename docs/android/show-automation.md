# Automatic show preparation

On a followed show's page, enable **Automatically prepare new episodes**. The first
successful refresh establishes a baseline; it does not process the back catalog.
Later refreshes admit newly discovered episodes, excluding episodes explicitly dated
before activation. Missing dates use discovery after the baseline. Disabling and
re-enabling establishes a fresh baseline. Unsubscribing through the app disables it.

Settings → Storage controls feed checking: 6 hours by default, presets or a custom
whole-hour interval from 1 to 168. Refresh subscriptions controls scheduled checks;
manual refresh still discovers episodes. Android can delay checks, and force-stop
prevents background execution until reopening the app.

Device-local Room records retain show intent and episode stages. WorkManager downloads
verified audio, existing resumable transcription prepares local/publisher text, and
existing analysis generates summaries, chapters, and suggested ads. Online cached text
is not used for automatic analysis. A coordinator advances completed stages and
schedules persisted continuations after worker completion. Startup also reconciles
stages. Processing is serialized through the automatic pipeline; manual work can run
independently. Network, model, charging and storage constraints remain enforced by
the existing components. Missing account access waits; failures and user pauses show
a retry action. Missing models can be downloaded from Transcription settings.

Disabling automation removes its pending intent. An already dispatched shared worker
may finish its current operation; subsequent automatic stages stop. This avoids
canceling work shared with manual requests. Existing downloads and insights are kept.
Temporary download network failures retry up to three times with WorkManager backoff.
Other failures retain checkpoints and require explicit retry;
the coordinator retries its own transient scheduling failures at most three times.

The player shows **Skip marked ads** only with a complete current local/publisher
transcript, successful matching analysis, and an available local audio file. No ad
ranges produces **No ads marked**. First enabling selects suggested ranges for this
episode. Review marked ads allows removing incorrect ranges, and later off/on toggles
preserve those exclusions. The choice is stored against the analysis revision, so
new analysis requires opting in again. Existing global approved-ad settings remain
the fallback for episodes without an individual choice. Undo and intentional seeks
continue using the existing original-audio timeline behavior.

Automation settings, jobs, and episode skip preferences are local to this installation
and excluded from portable backups and cloud subscription sync. Database migration
9→10 adds separate tables without changing existing records or backup formats.

## Validation

Automated coverage includes feed baselines, failures/304 responses, duplicate
admission, old publication dates, disable/re-enable, persisted stage recovery,
manual pauses, authentication waits, per-episode ad choices, review exclusions,
stale analysis, interval persistence/validation, and upgrades from schemas 1–9.
Run the checks in [development](../development.md). Emulator and physical-device
background/battery behavior require separate evidence; no owner-device or live
account actions are part of this change.
