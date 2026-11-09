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
    val posts = dao.observePosts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val captures = dao.observeDistillations().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val localTranscripts = dao.observeTranscripts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val onlineTranscripts = dao.observeOnlineTranscripts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val transcripts = combine(localTranscripts, onlineTranscripts, episodes) { local, online, library ->
        val localById = local.associateBy { it.episodeId }
        val onlineById = online.associateBy { it.episodeId }
        library.mapNotNull { ep ->
            val row = localById[ep.id]
            val cached = onlineById[ep.id]?.takeIf { it.matches(ep) }
            if (row?.state == "ready" && row.source != "online" && row.fingerprint == ep.mediaFingerprint) row
            else cached?.transcript(ep) ?: row
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val models = dao.observeModels().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val analyses = dao.observeAnalyses().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val chat = dao.observeMessages().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val research = dao.observeResearch().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val suggestions = dao.observeSuggestions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val automationShows = graph.db.automation().observeShows().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val automationJobs = graph.db.automation().observeJobs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val adPreferences = graph.db.automation().observeAds().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun automateShow(id: String, enabled: Boolean) = action("automation:$id") { graph.automation.setEnabled(id, enabled) }
    fun retryAutomation(id: String) = action("auto-retry:$id") { graph.automation.retry(id) }
    fun refreshHours(hours: Int) = action("settings") { graph.settings.refreshHours(hours); graph.jobs.configureRefresh() }
    fun skipEpisodeAds(id: String, enabled: Boolean) = action("ads:$id") {
        setEpisodeAdSkipping(graph.db, id, enabled)
    }

    val player = graph.playback.state
    private val directorySearch = PodcastSearch(viewModelScope, graph.podcasts::search, graph.settings::rememberSearch,
        graph.podcasts.searchProviderNames, graph.podcasts::searchProviders)
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
            catch (error: Exception) { graph.diagnostics.failure("worker",error,source="phone");messages.send(safeFailure(error)) }
            finally { busy.update { it - key } }
        }
    }
    fun search() {
        val text = query.value.trim()
        if (isFeedAddress(text)) { subscribe(text); return }
        directorySearch.submit(text)
    }
    fun retrySearch(provider: String) = directorySearch.retry(provider)
    fun subscribe(url: String) = action("subscribe:$url") {
        val candidate = search.value.results.firstOrNull { it.feedUrl == url }
        val id = if (candidate != null) graph.podcasts.follow(candidate) else graph.podcasts.subscribe(url); messages.send("Subscribed"); open("podcast:$id")
    }
    fun refresh(id: String? = null) = action("refresh") {
        if (id == null) graph.podcasts.refreshAll() else graph.podcasts.refresh(id)
        messages.send("Feed refresh finished")
    }
    fun play(id: String, position: Long? = null) = action("play") { graph.voice.cancel(false); graph.playback.play(id, position) }
    fun toggle() = action("toggle") { graph.voice.cancel(false); graph.playback.toggle() }
    fun seek(position: Long) = action("seek") { graph.voice.cancel(false); graph.playback.seek(position) }
    fun skip(delta: Long) = action("seek") { graph.voice.cancel(false); graph.playback.skip(delta) }
    fun speed(value: Float) = action("speed") { graph.playback.speed(value) }
    private val queueActions = QueueActions(viewModelScope) { error ->
        graph.diagnostics.failure("worker", error, source="phone")
        messages.send(safeFailure(error))
    }
    fun clearQueue() = queueActions.submit { graph.voice.cancel(false); graph.playback.clear() }
    fun enqueue(id: String, next: Boolean = false) = queueActions.submit { graph.playback.enqueue(id, next); messages.send(if (next) "Added to play next" else "Added to queue") }
    fun removeQueue(id: String) = queueActions.submit { graph.playback.remove(id) }
    fun moveQueue(id: String, offset: Int) = queueActions.submit { graph.playback.move(id, offset) }
    fun download(id: String) = action("download:$id") { graph.jobs.download(id); messages.send("Download queued") }
    fun pauseDownload(id: String) { graph.jobs.pauseDownload(id) }
    fun deleteDownload(id: String) = action("delete:$id") { graph.media.deleteDownload(id) }
    fun clearUnusedAudio() = action("storage") { messages.send("Cleared ${fileSize(graph.media.clearUnusedAudio())} of unused audio. Queued and active files were kept.") }
    fun markCompleted(id: String, complete: Boolean) = action("completed:$id") { graph.portableSync.markCompleted(id, complete) }
    fun unsubscribe(id: String) = action("unsubscribe:$id") { graph.automation.setEnabled(id, false); graph.podcasts.unsubscribe(id); root("library") }
    fun downloadModel(id: String) = action("model:$id") { graph.jobs.model(id) }
    fun deleteModel(id: String) = action("model:$id") { graph.media.deleteModel(id) }
    fun flag(key: String, value: Boolean) = action("settings") {
        graph.settings.flag(key, value)
        if (key == "wifi") graph.jobs.configureNetworkPolicy() else graph.jobs.configureRefresh()
    }
    fun text(key: String, value: String) = action("settings") { graph.settings.text(key, value) }
    fun createPost(momentId: String, format: String = "discussion") = action("create-post:$momentId") {
        open("post:${graph.socialResults.create(momentId, format)}")
    }
    fun duplicatePost(id: String) = action("post:$id") { open("post:${graph.posts.duplicate(id).id}") }
    fun deletePost(id: String) = action("post:$id") { graph.posts.delete(id) }
    fun deleteCapture(id: String) = action("capture:$id") { graph.portableSync.deleteMoment(id) }
    fun prepareTranscript(id: String, local: Boolean = false) = action("transcript:$id") { graph.diagnostics.usage("transcription");graph.transcription.prepare(id, local); messages.send("Transcript preparation queued") }
    fun pauseTranscript(id: String) = action("transcript:$id") { graph.transcription.pause(id) }
    fun captureMoment() = captureMoment(openSaved = true)
    fun captureMomentInPlayer() = captureMoment(openSaved = false)
    private fun captureMoment(openSaved: Boolean) = action("distill") {
        graph.diagnostics.event("capture.requested",source="phone")
        val (id, position, file) = graph.playback.captureSource()
        val ep = dao.episode(id) ?: error("Episode is unavailable")
        val saved = graph.captures.capture(id, position, file != null && file == ep.localFile)
        graph.diagnostics.event("capture.accepted",saved,source="phone")
        messages.send("Moment saved at ${timestamp(position)}")
        if (openSaved) open("capture:$saved")
    }
    fun generateCapture(id: String, confirm: Boolean = false) = action("capture:$id") { graph.diagnostics.usage("distillation");graph.captures.generate(id, confirm) }
    fun improveCapture(id: String, expanded: Boolean = false) = action("capture:$id") { graph.captures.improve(id, expanded) }
    fun retranscribeCapture(id: String) = action("capture:$id") {
        val moment=dao.distillation(id) ?: return@action
        graph.transcription.prepare(moment.episodeId, forceLocal=true)
        graph.captures.improve(id, expanded=true)
    }
    fun restoreCapture(id: String, version: String) = action("capture:$id") { graph.captures.restoreVersion(id, version) }
    fun cancelCapture(id: String) = action("capture:$id") { graph.jobs.cancelCapture(id); graph.captures.cancel(id) }
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
        val capture = explanationMoment(report, dao.distillation(report.distillationId) ?: error("Source is unavailable"))
        val ep = dao.episode(capture.episodeId)
        return "Transcript explanation · DistillPod\n\n${report.report}\n\nSource: ${ep?.title ?: "Podcast"} · ${timestamp(capture.sourceStartMs)}–${timestamp(capture.sourceEndMs)}\n\nSaved excerpt:\n${capture.excerpt.ifBlank { "The original excerpt was not saved with this older explanation." }}\n\nThis explanation uses the saved transcript. No web research was performed."
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
