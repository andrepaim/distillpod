package dev.distillpod.android.features

import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.data.SegmentEntity
import dev.distillpod.android.data.TranscriptStage

@OptIn(ExperimentalLayoutApi::class)
@Composable fun TranscriptTools(vm: AppViewModel, ep: EpisodeEntity) {
    val transcripts by vm.transcripts.collectAsStateWithLifecycle()
    val row = transcripts.firstOrNull { it.episodeId == ep.id }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val progress by vm.graph.transcription.progress.collectAsStateWithLifecycle()
    val live = progress[ep.id]?.takeIf { it.revision == row?.revision }
    val running = row?.state in listOf("queued", "processing")
    val duration = live?.durationMs?.takeIf { it > 0 } ?: row?.durationMs ?: 0
    val saved = row?.processedMs ?: 0
    var highWater by remember(ep.id, row?.revision) { mutableLongStateOf(saved) }
    val reported = maxOf(saved, live?.workedMs ?: 0)
    val worked = if (running) maxOf(reported, highWater) else saved
    SideEffect { highWater = if (running) worked else saved }
    val fraction = if (duration > 0) (worked.toFloat() / duration).coerceIn(0f, if (row?.state == "ready") 1f else 0.999f) else 0f
    val animated by animateFloatAsState(fraction, tween(650), label = "Transcript progress")
    val stage = if (running) live?.stage else null
    val phaseText = when (stage) {
        TranscriptStage.PUBLISHER -> "Loading publisher transcript"
        TranscriptStage.DOWNLOADING -> if (ep.totalBytes > 0) "Downloading audio · ${(ep.downloadedBytes * 100 / ep.totalBytes).coerceIn(0, 100)}%" else "Downloading audio"
        TranscriptStage.CHECKING_MODEL -> "Checking transcription model"
        TranscriptStage.DECODING -> "Decoding audio"
        TranscriptStage.STARTING -> "Starting transcription"
        TranscriptStage.TRANSCRIBING -> "Transcribing · ${timestamp(worked)} / ${timestamp(duration)} · ${(fraction * 100).toInt()}%"
        TranscriptStage.SAVING -> "Saving transcript"
        else -> null
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Transcript", style = MaterialTheme.typography.titleMedium)
        Text(phaseText ?: when (row?.state) {
            "ready" -> if (row.source == "publisher") "Publisher transcript · segment timings" else "On-device transcript · ${row.model} · segment timings"
            "queued" -> "Queued · waiting for power, network or an Android processing slot"
            "processing" -> "Preparing on this phone · ${timestamp(row.processedMs)} completed"
            "waiting_model" -> "Download the selected model in Settings to continue"
            "paused" -> "Paused · completed chunks are saved"
            "partial" -> "Publisher transcript has partial coverage"
            "stale" -> "Audio changed · prepare a new transcript"
            "failed" -> "Preparation could not finish · completed chunks are saved"
            else -> "Prepare a transcript to browse text and capture insights. Audio stays on your phone."
        }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (row != null && row.state != "ready") {
            if (duration > 0) LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Transcript preparation progress" })
            if (running && (stage !in listOf(TranscriptStage.TRANSCRIBING, TranscriptStage.QUEUED) || stage == TranscriptStage.TRANSCRIBING && worked <= saved)) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (running) Text("${timestamp(saved)} saved${if (live != null && live.chunkEndMs > live.chunkStartMs) " · current chunk ${timestamp(live.chunkStartMs)}–${timestamp(live.chunkEndMs)}" else ""}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        row?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (row?.state) {
                "processing", "queued" -> OutlinedButton(onClick = { vm.pauseTranscript(ep.id) }) { Text("Pause transcript") }
                "waiting_model" -> Button(onClick = { vm.open("settings") }) { Text("Download a model") }
                "ready" -> {}
                else -> Button(onClick = { vm.prepareTranscript(ep.id) }, enabled = "transcript:${ep.id}" !in busy) { Text(if (row == null || row.state == "stale") "Prepare transcript" else "Resume transcript") }
            }
            if (row != null && row.processedMs > 0) OutlinedButton(onClick = { vm.open("transcript:${ep.id}") }) { Text("Read transcript") }
        }
        if (row?.state in listOf("ready", "partial", "failed")) TextButton(onClick = { vm.prepareTranscript(ep.id, true) }) { Text("Transcribe locally again") }
    }
}

@Composable fun TranscriptPage(vm: AppViewModel, ep: EpisodeEntity?) {
    if (ep == null) return
    val transcripts by vm.transcripts.collectAsStateWithLifecycle()
    val row = transcripts.firstOrNull { it.episodeId == ep.id }
    var segments by remember(ep.id, row?.revision) { mutableStateOf<List<SegmentEntity>>(emptyList()) }
    LaunchedEffect(ep.id, row?.revision, row?.processedMs) { segments = row?.let { vm.graph.db.library().segments(ep.id, it.revision) } ?: emptyList() }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(ep.title, style = MaterialTheme.typography.titleLarge); TranscriptTools(vm, ep) }
        item { Text("Tap a timestamp to listen. Segment timings include surrounding words at excerpt boundaries.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        items(segments, key = { "${it.startMs}:${it.ordinal}" }) { segment ->
            Column(Modifier.fillMaxWidth().clickable { vm.play(ep.id, segment.startMs) }.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(timestamp(segment.startMs), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                Text(segment.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
