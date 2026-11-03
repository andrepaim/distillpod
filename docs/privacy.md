# Privacy and data handling

DistillPod keeps its library, queue, listening position, transcripts, saved excerpts and generated text in its private Android storage. Account credentials are encrypted with a key held by Android Keystore. This is a personal-alpha implementation; the repository's validation reports are not an independent security audit.

## Network requests

Podcast directory searches send the query to the directory provider. Following/refreshing a feed, loading its artwork and streaming/downloading an episode contact the corresponding hosts. Model downloads contact their configured upstream host. These services can observe the requests made to them.

Whisper transcription runs locally on the phone. Requested AI operations send selected transcript context to the connected provider, using the connected account's access and allowance. Episode audio is not uploaded for cloud transcription. Interest-based suggestions send followed-show titles only when that option is selected; suggestions are initiated explicitly.

## Backups and exports

Library backups contain readable personal library data: subscription and episode metadata, progress, queue, transcripts, saved moments and stored generated text, plus language/model choices. Manual Drive backup uses Android’s document picker; the selected provider manages cloud sync. Backups are readable JSON and have no passphrase encryption. They exclude account credentials, downloaded audio, model binaries and device-private file paths. OPML contains subscription titles and feed addresses. Choose destinations carefully and share exports only deliberately.

Do not include personal backups, feeds containing access tokens, recordings, account data or private screenshots in issue reports. A minimal reproduction on a development emulator is preferable. Repeated AI requests may consume additional plan allowance.
