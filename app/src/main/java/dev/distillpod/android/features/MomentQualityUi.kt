package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.data.*
import org.json.JSONObject

@Composable internal fun MomentQualityTools(vm: AppViewModel, moment: DistillationEntity, episode: EpisodeEntity?) {
    val working = moment.state in setOf("waiting_transcript","queued","running","reviewing")
    val pending by remember(moment.attempt) { vm.graph.db.library().observeMomentAttempt(moment.attempt) }.collectAsStateWithLifecycle(null)
    val versions by remember(moment.id) { vm.graph.db.library().observeMomentVersions(moment.id) }.collectAsStateWithLifecycle(emptyList())
    var history by rememberSaveable(moment.id) { mutableStateOf(false) }
    var original by rememberSaveable(moment.id) { mutableStateOf(false) }
    if(moment.state=="review_source") pending?.let { attempt ->
        val context=JSONObject(attempt.contextJson)
        if(context.has("spans")) {
            Text("Review proposed source context",style=MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(MomentGeneration.context(attempt.contextJson).spans.joinToString(" ") { it.text }) }
        }
    }
    if(moment.sourceJson.isNotBlank()) {
        Text("Saved at ${timestamp(moment.endMs)} · Passage ${timestamp(moment.sourceStartMs)}–${timestamp(moment.sourceEndMs)}",style=MaterialTheme.typography.labelSmall)
        if(moment.sourceEndMs > moment.endMs) Text("The passage includes a few seconds after your tap to finish the thought.",style=MaterialTheme.typography.bodySmall)
        if(moment.quote==null) Text("Saved as a takeaway. No suitable standalone quote was selected.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={original=!original}) { Text(if(original)"Hide original capture" else "Original capture") }
        if(original) SelectionContainer { Text(JSONObject(moment.sourceJson).getString("originalExcerpt")) }
    }
    if(!working && episode!=null) {
        if(moment.insight!=null) OutlinedButton(onClick={vm.improveCapture(moment.id)}) { Text("Improve this distill") }
        if(moment.state in setOf("needs_context","unclear_source")) {
            OutlinedButton(onClick={vm.improveCapture(moment.id,true)}) { Text("Expand context and retry") }
            OutlinedButton(onClick={vm.retranscribeCapture(moment.id)}) { Text("Transcribe and retry") }
            Text("For clearer speech recognition, select Base in Settings before transcribing again.",style=MaterialTheme.typography.bodySmall)
        }
        if(moment.insight!=null || moment.state in setOf("needs_context","unclear_source"))
            Text("Generation and source review use two AI requests. Your current saved result is kept while a replacement is prepared.",style=MaterialTheme.typography.bodySmall)
    }
    if(versions.isNotEmpty()) {
        TextButton(onClick={history=!history}) { Text(if(history)"Hide previous versions" else "Previous versions (${versions.size})") }
        if(history) versions.forEach { version ->
            val previous=restoreMomentSnapshot(moment,version.snapshot)
            Card {
                Column {
                    Text(previous.insight.orEmpty())
                    previous.quote?.let { Text("“$it”") }
                    Text("Source ${timestamp(previous.sourceStartMs)}–${timestamp(previous.sourceEndMs)}",style=MaterialTheme.typography.labelSmall)
                    TextButton(onClick={vm.restoreCapture(moment.id,version.id)},enabled=!working) { Text("Restore this version") }
                }
            }
        }
    }
}
