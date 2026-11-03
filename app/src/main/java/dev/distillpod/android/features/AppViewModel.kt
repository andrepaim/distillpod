package dev.distillpod.android.features

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.distillpod.android.AppGraph
import dev.distillpod.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

class AppViewModel(val graph: AppGraph, private val saved: SavedStateHandle) : ViewModel() {
    private val dao = graph.db.library()
    val backStack = saved.getStateFlow("backStack", arrayListOf("library"))
    val query = saved.getStateFlow("query", "")
    val filter = saved.getStateFlow("filter", "all")
    val chatDraft = saved.getStateFlow("chatDraft", "")
    val suggestionTopics = saved.getStateFlow("suggestionTopics", "")
    val recommendationState = graph.recommendations.state.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val recommendationError = graph.recommendations.error.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val podcasts = dao.observePodcasts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val episodes = dao.observeEpisodes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val captures = dao.observeDistillations().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val transcripts = dao.observeTranscripts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val models = dao.observeModels().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val analyses = dao.observeAnalyses().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val chat = dao.observeMessages().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val research = dao.observeResearch().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val suggestions = dao.observeSuggestions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val player = graph.playback.state
    private val directorySearch = PodcastSearch(viewModelScope, graph.podcasts::search, graph.settings::rememberSearch)
    val search = directorySearch.state
    val recentSearches = graph.settings.recentSearches.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val busy = MutableStateFlow<Set<String>>(emptySet())
    val messages = Channel<String>(Channel.BUFFERED)
    val loaded = MutableStateFlow(false)
    val backupDocuments = BackupDocuments(graph.context, graph.backup, graph.settings, viewModelScope) { graph.playback.synchronizeRestoredQueue() }
    val managedBackups = ManagedBackups(graph.context, graph.backup, graph.settings, viewModelScope, backupDocuments::review)
    init { viewModelScope.launch { dao.observeEpisodes().first(); loaded.value = true } }
    fun root(route: String) { saved["backStack"] = arrayListOf(route) }
    fun open(route: String) { if (backStack.value.lastOrNull() != route) saved["backStack"] = ArrayList(backStack.value + route) }
    fun back() { if (backStack.value.size > 1) saved["backStack"] = ArrayList(backStack.value.dropLast(1)) }
    fun query(value: String) {
        val bounded = value.take(if (isFeedAddress(value)) 2048 else 200)
        if (bounded != query.value) { saved["query"] = bounded; directorySearch.clear() }
    }
    fun clearSearches() = action("clear-searches") { graph.settings.clearSearches() }
    fun filter(value: String) { saved["filter"] = value }
    fun chatDraft(value: String) { saved["chatDraft"] = value.take(2000) }
    fun suggestionTopics(value: String) { saved["suggestionTopics"] = value.take(300) }
    fun action(key: String, block: suspend () -> Unit) {
        if (key in busy.value) return
        busy.update { it + key }
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { messages.send(safeFailure(error)) }
            finally { busy.update { it - key } }
        }
    }
    fun search() {
        val text = query.value.trim()
        if (isFeedAddress(text)) { subscribe(text); return }
        directorySearch.submit(text)
    }
    fun subscribe(url: String) = action("subscribe:$url") {
        val id = graph.podcasts.subscribe(url); messages.send("Subscribed"); open("podcast:$id")
    }
    fun refresh(id: String? = null) = action("refresh") {
        if (id == null) graph.podcasts.refreshAll() else graph.podcasts.refresh(id)
        messages.send("Feed refresh finished")
    }
    fun play(id: String, position: Long? = null) = action("play") { graph.playback.play(id, position) }
    fun toggle() = action("toggle") { graph.playback.toggle() }
    fun seek(position: Long) = action("seek") { graph.playback.seek(position) }
    fun skip(delta: Long) = action("seek") { graph.playback.skip(delta) }
    fun speed(value: Float) = action("speed") { graph.playback.speed(value) }
    fun enqueue(id: String, next: Boolean = false) = action("queue") { graph.playback.enqueue(id, next); messages.send(if (next) "Added to play next" else "Added to queue") }
    fun removeQueue(id: String) = action("queue") { graph.playback.remove(id) }
    fun moveQueue(id: String, offset: Int) = action("queue") { graph.playback.move(id, offset) }
    fun download(id: String) = action("download:$id") { graph.jobs.download(id); messages.send("Download queued") }
    fun pauseDownload(id: String) { graph.jobs.pauseDownload(id) }
    fun deleteDownload(id: String) = action("delete:$id") { graph.media.deleteDownload(id) }
    fun clearUnusedAudio() = action("storage") { messages.send("Cleared ${fileSize(graph.media.clearUnusedAudio())} of unused audio. Queued and active files were kept.") }
    fun markCompleted(id: String, complete: Boolean) = action("completed:$id") { dao.markCompleted(id, complete) }
    fun unsubscribe(id: String) = action("unsubscribe:$id") { graph.podcasts.unsubscribe(id); root("library") }
    fun downloadModel(id: String) = action("model:$id") { graph.jobs.model(id) }
    fun deleteModel(id: String) = action("model:$id") { graph.media.deleteModel(id) }
    fun flag(key: String, value: Boolean) = action("settings") { graph.settings.flag(key, value); graph.jobs.configureRefresh() }
    fun text(key: String, value: String) = action("settings") { graph.settings.text(key, value) }
    fun deleteCapture(id: String) = action("capture:$id") { dao.deleteDistillation(id) }
    fun prepareTranscript(id: String, local: Boolean = false) = action("transcript:$id") { graph.transcription.prepare(id, local); messages.send("Transcript preparation queued") }
    fun pauseTranscript(id: String) = action("transcript:$id") { graph.transcription.pause(id) }
    fun captureMoment() = action("distill") {
        val (id, position, file) = graph.playback.captureSource()
        val ep = dao.episode(id) ?: error("Episode is unavailable")
        val saved = graph.captures.capture(id, position, file != null && file == ep.localFile)
        messages.send("Moment saved at ${timestamp(position)}")
        open("capture:$saved")
    }
    fun generateCapture(id: String, confirm: Boolean = false) = action("capture:$id") { graph.captures.generate(id, confirm) }
    fun cancelCapture(id: String) = action("capture:$id") { graph.jobs.cancelCapture(id); dao.distillation(id)?.let { dao.putDistillation(it.copy(state = "failed", error = "Generation paused. Your source is saved.")) } }
    fun exportOpml(uri: android.net.Uri) = action("opml") {
        val xml = graph.podcasts.exportOpml()
        withContext(Dispatchers.IO) { graph.context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(xml) } }
        messages.send("Subscriptions exported")
    }
    fun importOpml(uri: android.net.Uri) = action("opml") {
        val xml = withContext(Dispatchers.IO) { graph.context.contentResolver.openInputStream(uri)!!.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) { val count = input.read(block); if (count < 0) break; require(output.size() + count <= 2_000_000) { "OPML exceeds 2 MB" }; output.write(block, 0, count) }
            output.toByteArray().toString(Charsets.UTF_8)
        } }
        val count = graph.podcasts.importOpml(xml)
        graph.jobs.refresh(); messages.send("Added $count subscriptions. Feeds will refresh when connected.")
    }
    fun analyze(id: String) = action("analysis:$id") { graph.understanding.analyze(id) }
    fun pauseAnalysis(id: String) = action("analysis:$id") {
        graph.jobs.cancelAnalysis(id)
        dao.analysis(id)?.let { dao.putAnalysis(it.copy(state = "failed", error = "Analysis paused. Completed parts are saved.")) }
    }
    fun approveAd(id: String, index: Int, approved: Boolean) = action("ad:$id:$index") { graph.understanding.approveAd(id, index, approved) }
    fun ask(id: String) = action("ask:$id") { graph.understanding.ask(id, chatDraft.value); saved["chatDraft"] = "" }
    fun retryReply(id: String) = action("reply:$id") { graph.understanding.retryReply(id) }
    fun pauseReply(id: String) = action("reply:$id") {
        graph.jobs.cancelReply(id); dao.message(id)?.let { dao.putMessage(it.copy(state = "failed", error = "This reply is incomplete. Retry when ready.")) }
    }
    fun undoAdSkip() = action("seek") {
        val skipped = graph.playbackEvents.lastSkipped.value ?: return@action
        if (player.value.episodeId == skipped.episodeId) graph.playback.seek(skipped.positionMs)
        graph.playbackEvents.lastSkipped.value = null
    }
    fun explain(id: String) = action("explain:$id") { open("research:${graph.explanations.create(id)}") }
    fun pauseExplanation(id: String) = action("explain:$id") {
        graph.jobs.cancelExplanation(id); dao.research(id)?.let { dao.putResearch(it.copy(state = "failed", error = "Explanation paused. Your source is saved.")) }
    }
    suspend fun explanationText(id: String): String {
        val report = dao.research(id) ?: error("Explanation is unavailable")
        val capture = dao.distillation(report.distillationId) ?: error("Source is unavailable")
        val ep = dao.episode(capture.episodeId)
        return "Transcript explanation · DistillPod\n\n${report.report}\n\nSource: ${ep?.title ?: "Podcast"} · ${timestamp(capture.startMs)}–${timestamp(capture.endMs)}\n\nSaved excerpt:\n${capture.excerpt}\n\nThis explanation uses the saved transcript. No web research was performed."
    }
    fun exportExplanation(id: String, uri: android.net.Uri) = action("export:$id") {
        val text = explanationText(id)
        withContext(Dispatchers.IO) { graph.context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(text) } }
        messages.send("Explanation exported")
    }
    fun recommend(history: Boolean) = action("recommendations") { graph.recommendations.request(suggestionTopics.value, history) }
    fun cancelRecommendations() = action("recommendations") { graph.jobs.cancelRecommendations(); graph.recommendations.cancel() }
    fun dismissSuggestion(url: String) = action("suggestion:$url") { dao.dismissSuggestion(url) }
    companion object { fun factory(graph: AppGraph) = viewModelFactory { initializer { AppViewModel(graph, createSavedStateHandle()) } } }
}
