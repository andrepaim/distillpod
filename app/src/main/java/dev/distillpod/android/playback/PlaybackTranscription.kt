package dev.distillpod.android.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Uses persisted preferences (no optimistic default) and actual playback, including queue transitions. */
internal class PlaybackTranscription(
    scope: CoroutineScope,
    enabled: Flow<Boolean>,
    prepare: suspend (String) -> Unit,
    onFailure: (Exception) -> Unit,
) {
    private val playingEpisode = MutableStateFlow<String?>(null)

    init {
        scope.launch {
            combine(playingEpisode, enabled) { episode, allowed -> episode.takeIf { allowed } }
                .distinctUntilChanged()
                .buffer() // Keep observed playback starts while an earlier scheduling request is waiting.
                .collect { episode ->
                    if (episode != null) try {
                        prepare(episode)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // A transcript failure must not stop playback or observation of the next episode.
                        onFailure(error)
                    }
                }
        }
    }

    fun update(episodeId: String?, isPlaying: Boolean) {
        playingEpisode.value = episodeId.takeIf { isPlaying }
    }
}
