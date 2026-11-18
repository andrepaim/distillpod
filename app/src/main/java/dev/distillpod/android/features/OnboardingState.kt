package dev.distillpod.android.features

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.ModelEntity
import dev.distillpod.android.data.ModelSpec
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.data.whisperModels
import dev.distillpod.core.SetupModelStatus
import dev.distillpod.core.setupModelStatus
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SetupModel(val spec: ModelSpec?, val status: SetupModelStatus, val bytes: Long)

internal fun setupModel(selected: String, rows: List<ModelEntity>, fileReady: (ModelSpec) -> Boolean): SetupModel {
    val spec = whisperModels.firstOrNull { it.id == selected }
    val row = rows.firstOrNull { it.id == spec?.id }
    val available = row?.state == "ready" && spec != null && fileReady(spec)
    return SetupModel(spec, setupModelStatus(row?.state, available), row?.bytes ?: 0)
}

/** A ready row can outlive its file. Check only ready rows and leave hashing to model verification. */
@Composable
internal fun rememberReadySetupModelFiles(vm: AppViewModel, rows: List<ModelEntity>): Set<String>? {
    val ready = whisperModels.filter { spec -> rows.any { it.id == spec.id && it.state == "ready" } }
    if (ready.isEmpty()) return emptySet()
    val directory = vm.graph.media.modelsDirectory
    return key(directory, ready) {
        produceState<Set<String>?>(null) {
            value = withContext(Dispatchers.IO) {
                ready.mapNotNullTo(mutableSetOf()) { spec ->
                    val file = File(directory, "${spec.id}.bin")
                    spec.id.takeIf { file.isFile && file.length() == spec.bytes }
                }
            }
        }.value
    }
}

/** Choose from the Library, whose rows can include retained episodes from unfollowed shows. */
internal fun setupLibraryEpisode(
    podcasts: List<PodcastEntity>,
    episodes: List<EpisodeEntity>,
    localFileExists: (String) -> Boolean,
): String? {
    val followed = podcasts.filter { it.subscribed }.mapTo(mutableSetOf()) { it.id }
    return episodes.firstOrNull { episode ->
        val inLibrary = episode.podcastId in followed || episode.localFile != null || episode.lastPlayedAt > 0
        inLibrary && (episode.hasRemoteMedia() || episode.localFile?.let(localFileExists) == true)
    }?.id
}

private fun EpisodeEntity.hasRemoteMedia(): Boolean = runCatching {
    val uri = URI(audioUrl)
    (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
        uri.host != null
}.getOrDefault(false)
