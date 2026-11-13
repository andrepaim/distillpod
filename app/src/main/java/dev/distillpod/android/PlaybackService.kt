package dev.distillpod.android

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import dev.distillpod.android.auto.AutoLibraryCallback
import androidx.room.withTransaction
import dev.distillpod.android.data.*
import dev.distillpod.android.playback.mediaItem
import dev.distillpod.android.playback.swapCurrentSource
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import dev.distillpod.core.ReviewedAdSkipping
import dev.distillpod.core.TimeRange
import dev.distillpod.core.normalizeRanges
import dev.distillpod.android.playback.SkippedMoment

/** Owns audio, progress and the queue even when there is no Activity. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private sealed interface WriteMessage
    private data class CloudChoice(val choice: dev.distillpod.android.playback.CloudQueueChoice, val revision: Long): WriteMessage
    private var pendingCloudChoice: CloudChoice? = null
    private var queueRevision = 0L
    private var observedQueue = emptyList<String>()
    private var applyingCloudChoice = false
    private data class RestoreQueue(val revision: Long): WriteMessage
    private data class Barrier(val reply: CompletableDeferred<Unit>): WriteMessage
    private data class Snapshot(val value: dev.distillpod.android.playback.PlaybackSnapshot): WriteMessage
    private val writes = Channel<WriteMessage>(Channel.UNLIMITED)
    private val syncSession=java.util.UUID.randomUUID().toString()
    private var previousId: String? = null
    private var previousDuration = 0L
    private var previousPosition = 0L
    private val restoringState = kotlinx.coroutines.flow.MutableStateFlow(true)
    private var restoring: Boolean
        get() = restoringState.value
        set(value) { restoringState.value = value }
    private val playbackReady=CompletableDeferred<Unit>()
    private val browseCurrent=kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private data class AdConfig(val file: String, val ranges: List<TimeRange>)
    private var adConfigs = emptyMap<String, AdConfig>()
    private val adSkipping = ReviewedAdSkipping()
    private var sleepTimer: dev.distillpod.android.playback.SleepTimerController? = null
    private var widget: dev.distillpod.android.widget.NowPlayingWidgetUpdater? = null
    override fun onCreate() {
        super.onCreate()
        applicationContext.appGraph.diagnostics.event("playback.service_started")
        val graph = applicationContext.appGraph
        // Observe projection before any Next event, including hosts using the legacy media browser.
        graph.carConnection
        val dao = graph.db.library()
        val engine = ExoPlayer.Builder(this)
            .setMediaSourceFactory(dev.distillpod.android.video.VideocastMediaSourceFactory(this, graph.youtube, graph.activeFiles.audioOwnership))
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(30000)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
        val player = dev.distillpod.android.playback.OwnedAudioPlayer(engine, graph.activeFiles.audioOwnership, graph.media.directory.absolutePath)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        val health = dev.distillpod.android.playback.PlaybackHealthRecorder(this, engine)
        val transcription = dev.distillpod.android.playback.PlaybackTranscription(
            scope, graph.settings.flow.map { it.transcribeOnPlayback },
            prepare = { id ->
                withContext(Dispatchers.IO) {
                    graph.ready.await()
                    graph.transcription.prepareAutomatically(id)
                }
            },
            onFailure = { graph.diagnostics.failure("transcription",it) },
        )
        engine.addAnalyticsListener(health)
        scope.launch { while (isActive) { health.sample(); delay(5000) } }
        val activity = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("openPlayer", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        var headphoneDistill = false
        scope.launch {
            graph.settings.flow.map { it.headphoneDistill }.distinctUntilChanged().collect { headphoneDistill = it }
        }
        lateinit var libraryCallback:AutoLibraryCallback
        val artwork=dev.distillpod.android.auto.AutoArtwork(this,graph.http,scope) {
            scope.launch {session?.let {libraryCallback.libraryChanged(it)}}
        }
        libraryCallback=AutoLibraryCallback(packageName,graph.db,graph.ready,scope,playbackReady,
            saveMoment={id,position,file->
                val ep=dao.episode(id)?:error("Episode is unavailable")
                graph.captures.save(id,position,file!=null && file==ep.localFile)
            },prepareMoment={id->graph.scope.launch(Dispatchers.IO){runCatching {graph.captures.prepareSaved(id)}.onFailure {graph.diagnostics.failure("capture",it,id)}};Unit},artwork=artwork::uri,allowArtwork=artwork::allow,
            voiceEnabled = { resources.getBoolean(R.bool.voice_enabled) },
            openCarVoice = { graph.carVoiceEntry.open() },
            onExternalPlaybackCommand = { graph.voice.cancel(false) },
            diagnostics = graph.diagnostics,
            preparePlaybackItems = graph.media::preparePlaybackItems,
            onSleepTimerCommand = { command, args ->
                sleepTimer?.command(command, args) ?: androidx.media3.session.SessionResult(androidx.media3.session.SessionError.ERROR_INVALID_STATE)
            },
            queueCommands = setOf(dev.distillpod.android.playback.QueueWriteCommands.BARRIER, dev.distillpod.android.playback.QueueWriteCommands.IMPORT),
            onQueueCommand = { command, args ->
                if(command==dev.distillpod.android.playback.QueueWriteCommands.IMPORT) {
                    val choice=graph.playback.cloudQueueChoices.take(args.getString(dev.distillpod.android.playback.QueueWriteCommands.TOKEN))
                    val accepted=choice!=null && pendingCloudChoice==null
                    if(accepted) {
                        val message=CloudChoice(checkNotNull(choice),queueRevision)
                        pendingCloudChoice=message
                        if(!writes.trySend(message).isSuccess) {
                            pendingCloudChoice=null
                            choice.completion.completeExceptionally(IllegalStateException("Playback writer is unavailable"))
                        }
                    } else choice?.completion?.completeExceptionally(IllegalStateException("A cloud queue choice is already running"))
                    com.google.common.util.concurrent.Futures.immediateFuture(androidx.media3.session.SessionResult(
                        if(accepted) androidx.media3.session.SessionResult.RESULT_SUCCESS else androidx.media3.session.SessionError.ERROR_INVALID_STATE))
                } else {
                    val reply = CompletableDeferred<Unit>(scope.coroutineContext[Job])
                    val result = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.SessionResult>()
                    val job=scope.launch {
                        try {
                            check(writes.trySend(Barrier(reply)).isSuccess) { "Playback writer is unavailable" }
                            reply.await()
                            result.set(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
                        } catch (failure: CancellationException) { result.cancel(false) }
                        catch (failure: Exception) { result.setException(failure) }
                    }
                    job.invokeOnCompletion {if(it!=null)result.cancel(false)}
                    result
                }
            },
            saveMomentWithSource = {id,position,file,source ->
                val ep=dao.episode(id)
                graph.captures.save(id,position,file!=null && file==ep?.localFile,source=source)
            },
            headphoneDistillEnabled = { headphoneDistill },
            headphoneVoiceEnabled = { graph.preferences.value.headphoneVoice },
            onHeadphoneVoice = {
                val connected = graph.carConnection.value?.let { it != androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NOT_CONNECTED }
                when (dev.distillpod.android.voice.headphoneNextAction(graph.voice.state.value, connected,
                        graph.carVoiceEntry.connected.value, graph.headphoneVoice.ready.value)) {
                    dev.distillpod.android.voice.HeadphoneNextAction.START_PHONE -> graph.headphoneVoice.ask()
                    dev.distillpod.android.voice.HeadphoneNextAction.START_CAR -> if (!graph.carVoiceEntry.open())
                        dev.distillpod.android.voice.HeadphoneVoiceService.unavailable(this)
                    dev.distillpod.android.voice.HeadphoneNextAction.DONE -> graph.voice.finishQuestion()
                    dev.distillpod.android.voice.HeadphoneNextAction.CANCEL -> graph.voice.cancel(true)
                    dev.distillpod.android.voice.HeadphoneNextAction.WAIT -> Unit
                    dev.distillpod.android.voice.HeadphoneNextAction.UNAVAILABLE -> dev.distillpod.android.voice.HeadphoneVoiceService.unavailable(this)
                }
            },
            onHeadphoneCaptureFailure = {
                android.widget.Toast.makeText(this, "Couldn't save moment. Play an episode and try again.", android.widget.Toast.LENGTH_SHORT).show()
            },
            isTrustedMediaButtonCaller = { caller ->
                val manager = getSystemService(android.media.session.MediaSessionManager::class.java)
                runCatching {
                    manager.isTrustedForMediaControl(android.media.session.MediaSessionManager.RemoteUserInfo(
                        caller.packageName, -1, caller.uid))
                }.getOrDefault(false)
            })
        session = MediaLibrarySession.Builder(this, player, libraryCallback).setSessionActivity(activity)
            .setCustomLayout(libraryCallback.playbackLayout())
            .setMediaButtonPreferences(libraryCallback.playbackLayout()).build()
        widget = dev.distillpod.android.widget.NowPlayingWidgetUpdater.attach(this, player)
        scope.launch {
            graph.ready.await()
            combine(dao.observePodcasts(),dao.observeBrowseSummary(),dao.observeQueue(),browseCurrent) {pods,episodes,queue,current->
                listOf(pods,episodes,queue,current)
            }.distinctUntilChanged().collectLatest {delay(500);session?.let {libraryCallback.libraryChanged(it)}}
        }
        fun snapshot(complete: Boolean = false, boundary: Boolean = false, queueBoundary:Boolean=false, queueRestored:Boolean=false) {
            if (restoring) return
            val id = player.currentMediaItem?.mediaId
            transcription.update(id, player.isPlaying)
            graph.activeFiles.playback = player.currentMediaItem?.localConfiguration?.uri?.takeIf { it.scheme == "file" }?.path
            graph.activeFiles.queued = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).localConfiguration?.uri?.takeIf { uri -> uri.scheme == "file" }?.path }.toSet()
            writes.trySend(Snapshot(dev.distillpod.android.playback.PlaybackSnapshot((0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }, id,
                player.currentPosition.coerceAtLeast(0), player.duration.coerceAtLeast(0), complete, player.playbackParameters.speed, boundary=boundary, playing=player.isPlaying,queueBoundary=queueBoundary,queueRestored=queueRestored,suppressQueue=pendingCloudChoice!=null)))
            previousId = id; previousDuration = player.duration.coerceAtLeast(0); previousPosition = player.currentPosition.coerceAtLeast(0)
        }
        val sleep = dev.distillpod.android.playback.SleepTimerController(
            dev.distillpod.android.playback.PlayerSleepTimerPlayback(player) { engine.pauseAtEndOfMediaItems = it }, scope,
            clock = android.os.SystemClock::elapsedRealtime, publish = { graph.playbackEvents.sleepTimer.value = it },
            onExpired = { endOfEpisode ->
                // The timer paused through the player, so its play/pause boundary already checkpointed
                // progress. An item that played to its end is also recorded as finished.
                if (endOfEpisode) snapshot(complete = true, boundary = true)
            })
        sleepTimer = sleep
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = sleep.playingChanged()
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) = sleep.itemChanged()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) sleep.itemEnded()
            }
            override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) sleep.itemEnded(queueEnded = true) }
        })
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState:Int) {
                when(playbackState) {
                    Player.STATE_BUFFERING->graph.diagnostics.event("playback.buffering",reason="buffering")
                    Player.STATE_READY->graph.diagnostics.event("playback.ready",reason="ready")
                }
            }
            private var videoEpisodeId: String? = null
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                graph.diagnostics.failure("playback",error)
                val item = player.currentMediaItem ?: return
                if (C.TRACK_TYPE_VIDEO in player.trackSelectionParameters.disabledTrackTypes) return
                val id = item.mediaId
                val position = player.currentPosition.coerceAtLeast(0)
                val resume = player.playWhenReady
                // Disable before recovery so an audio failure cannot loop through video fallback.
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                graph.playbackEvents.videoFailure.value = id to "Video is unavailable. Returning to audio."
                graph.diagnostics.event("videocast.failed", reason = "failed")
                scope.launch {
                    val prepared = withContext(Dispatchers.IO) {
                        val episode = dao.episode(id) ?: return@withContext null
                        val base = mediaItem(episode.copy(localFile = null), dao.podcast(episode.podcastId))
                        graph.media.preparePlaybackItems(listOf(base)).single()
                    } ?: return@launch
                    if (player.currentMediaItem?.mediaId != id || C.TRACK_TYPE_VIDEO !in player.trackSelectionParameters.disabledTrackTypes) return@launch
                    swapCurrentSource(player, prepared, position, resume)
                    graph.diagnostics.event("videocast.audio_restored", reason = "resume_requested")
                }
            }
            override fun onRenderedFirstFrame() { graph.diagnostics.event("videocast.first_frame", reason = "ready") }
            override fun onIsPlayingChanged(isPlaying:Boolean) {graph.diagnostics.event("playback.state",reason=if(isPlaying) "playing" else "paused")}
            override fun onTimelineChanged(timeline:androidx.media3.common.Timeline,reason:Int) {
                if(reason==Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    val ids=(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}
                    if(ids!=observedQueue) {
                        if(!restoring && !applyingCloudChoice)queueRevision++
                        observedQueue=ids
                    }
                    snapshot(queueBoundary=true)
                }
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                // Video is opt-in for the current episode, including queue auto-advance.
                if (videoEpisodeId != item?.mediaId) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                    videoEpisodeId = item?.mediaId
                }
                browseCurrent.value=item?.mediaId
                if (!restoring && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && previousId != null) {
                    writes.trySend(Snapshot(dev.distillpod.android.playback.PlaybackSnapshot(emptyList(), previousId, if (previousDuration > 0) previousDuration else previousPosition, previousDuration, true, player.playbackParameters.speed, boundary=true)))
                }
                snapshot()
            }
            override fun onEvents(p: Player, events: Player.Events) {
                snapshot(p.playbackState == Player.STATE_ENDED, events.contains(Player.EVENT_IS_PLAYING_CHANGED) || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED))
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // Removal and manual next/previous callbacks expose the departed item's final position.
                // Periodic checkpoints are sparse, so this old-item-only boundary keeps it exactly,
                // without restoring a cleared queue.
                val departedId = oldPosition.mediaItem?.mediaId
                if (!restoring && dev.distillpod.android.playback.shouldCheckpointDeparture(reason, departedId, newPosition.mediaItem?.mediaId)) {
                    writes.trySend(Snapshot(dev.distillpod.android.playback.PlaybackSnapshot(emptyList(), departedId, oldPosition.positionMs.coerceAtLeast(0),
                        0L, false, player.playbackParameters.speed, boundary=true)))
                }
                if (reason == Player.DISCONTINUITY_REASON_SEEK) player.currentMediaItem?.mediaId?.let { id ->
                    adSkipping.seek(id, newPosition.positionMs, adConfigs[id]?.ranges ?: emptyList())
                    snapshot(boundary=true)
                }
            }
        })
        scope.launch(Dispatchers.IO) {
            graph.ready.await()
            val persistence = dev.distillpod.android.playback.PlaybackSnapshotWriter(graph.db, { id, value ->
                graph.portableSync.checkpoint(id, value.position, value.duration, value.complete, value.at, syncSession, value.elapsed, value.boundary, active=value.playing)
            }, graph.collectionSync::queue, graph.importedQueue)
            for (message in writes) {
                if (message is Barrier) { message.reply.complete(Unit); continue }
                if (message is RestoreQueue) {
                    try {
                        playbackReady.await()
                        // Read the current persisted choice only after older local writes finish.
                        // A queued Room emission can otherwise resurrect an order already cleared.
                        val restored=graph.db.withTransaction {
                            val items=dao.queue().mapNotNull {entry->dao.episode(entry.episodeId)?.let {ep->mediaItem(ep,dao.podcast(ep.podcastId))}}
                            items to (items.firstOrNull()?.let {dao.episode(it.mediaId)?.positionMs}?:0L)
                        }
                        val prepared = graph.media.preparePlaybackItems(restored.first)
                        withContext(Dispatchers.Main.immediate) {
                            if(!restoring && pendingCloudChoice==null && queueRevision==message.revision &&
                                player.mediaItemCount==0 && restored.first.isNotEmpty()) {
                                restoring=true
                                try {player.setMediaItems(prepared,0,restored.second);player.prepare()}
                                finally {restoring=false}
                                snapshot(queueRestored=true)
                            }
                        }
                    } catch(error:Exception) {
                        currentCoroutineContext().ensureActive()
                        graph.diagnostics.failure("playback",error)
                    }
                    continue
                }
                if (message is CloudChoice) {
                    var committed=false
                    var importMarker:List<String>?=null
                    var failure:Throwable?=null
                    try {
                        // The startup restore must finish before an explicit choice replaces it.
                        playbackReady.await()
                        if(message.choice.start()) {
                            val data=graph.db.withTransaction {
                                message.choice.resolve()
                                val all=dao.episodes().associateBy {it.id}
                                val desired=dao.queue().map {it.episodeId}.filter {it in all}
                                graph.db.sync().view("queue_library")?.let {graph.db.sync().putView(it.copy(active=true))}
                                Triple(all,dao.podcasts().associateBy {it.id},desired)
                            }
                            committed=true
                            val before = withContext(Dispatchers.Main.immediate) {
                                (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                            }
                            val candidates = (before + data.third).distinct().filter { it in data.first }
                            val prepared = graph.media.preparePlaybackItems(candidates.map { id ->
                                val episode = data.first.getValue(id)
                                mediaItem(episode.copy(localFile = null), data.second[episode.podcastId])
                            }).associateBy { it.mediaId }
                            val (actual,laterEdit)=withContext(Dispatchers.Main.immediate) {
                                val later=queueRevision!=message.revision
                                if(!later) {
                                    applyingCloudChoice=true
                                    try {
                                        val current=player.currentMediaItem?.mediaId
                                        val ids=if(current!=null && current in data.first)listOf(current)+data.third.filter {it!=current} else data.third
                                        val existing=(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}
                                        if(ids!=existing) {
                                            val index=ids.indexOf(current).coerceAtLeast(0)
                                            val position=if(current in ids)player.currentPosition.coerceAtLeast(0) else ids.firstOrNull()?.let {data.first.getValue(it).positionMs}?:0
                                            val resume=player.playWhenReady
                                            importMarker=ids
                                            message.choice.imported(ids)
                                            val playing = player.currentMediaItem
                                            player.setMediaItems(ids.map { id ->
                                                val video = playing?.localConfiguration?.uri?.let {
                                                    dev.distillpod.core.YouTubeUrl.videoId(it.toString()) != null &&
                                                        it.getQueryParameter("distillpod_video") == "1"
                                                } == true
                                                if (video && playing?.mediaId == id) playing else prepared.getValue(id)
                                            },index,position)
                                            player.prepare();player.playWhenReady=resume
                                        }
                                    } finally {
                                        observedQueue=(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}
                                        applyingCloudChoice=false
                                    }
                                }
                                (0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}.filter {it in data.first} to later
                            }
                            // A local edit accepted after the cloud command supersedes that choice.
                            if(laterEdit)graph.collectionSync.queue(actual)
                            persistence.lastQueue=actual
                        }
                    } catch(error:Throwable) {
                        failure=error
                        if(committed)persistence.lastQueue=withContext(NonCancellable+Dispatchers.Main.immediate) {
                            if(session==null)persistence.lastQueue else (0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}
                        }
                    } finally {
                        withContext(NonCancellable+Dispatchers.Main.immediate) {
                            // A failed apply must not make a later matching local edit look imported.
                            if(importMarker!=null && graph.importedQueue.value==importMarker)graph.importedQueue.value=null
                            pendingCloudChoice=null
                            // Suppressed intermediate snapshots cannot publish. This final boundary
                            // recovers later local edits (or edits during a rejected/cancelled choice).
                            if(session!=null)snapshot(queueBoundary=true)
                        }
                    }
                    if(failure!=null)message.choice.completion.completeExceptionally(failure)
                    else message.choice.completion.complete(Unit)
                    if(failure is CancellationException)currentCoroutineContext().ensureActive()
                    continue
                }
                persistence.write((message as Snapshot).value)
            }
        }
        scope.launch {
            graph.ready.await()
            val restore = withContext(Dispatchers.IO) {
                val queue = dao.queue().mapNotNull { dao.episode(it.episodeId) }
                val podcasts = dao.podcasts().associateBy { it.id }
                Triple(queue, podcasts, dao.playerSession())
            }
            val prepared = graph.media.preparePlaybackItems(restore.first.map {
                mediaItem(it.copy(localFile = null), restore.second[it.podcastId])
            })
            if (player.mediaItemCount == 0 && restore.first.isNotEmpty()) {
                val index = restore.first.indexOfFirst { it.id == restore.third?.episodeId }.coerceAtLeast(0)
                player.setMediaItems(prepared, index, restore.first[index].positionMs)
                player.setPlaybackSpeed(restore.third?.speed?.coerceIn(0.5f, 3f) ?: 1f)
                player.prepare()
            }
            restoring = false; playbackReady.complete(Unit); snapshot(queueRestored=true)
            while (isActive) { snapshot(player.playbackState == Player.STATE_ENDED); delay(1000) }
        }
        scope.launch {
            dao.observeQueue().collect {entries->
                if(restoring || pendingCloudChoice!=null || player.mediaItemCount!=0 || entries.isEmpty())return@collect
                // Restore through the same owner as persistence and explicit cloud choices.
                // The actor re-reads Room; it never applies this possibly stale emission directly.
                writes.trySend(RestoreQueue(queueRevision))
            }
        }
        scope.launch {
            // Re-check when the current item, restoration or its stored file changes, not on progress writes.
            combine(browseCurrent, restoringState) { id, busy -> id.takeUnless { busy } }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) kotlinx.coroutines.flow.flowOf(null) else dao.observeLocalSource(id).map { id to it } }
                .collect { source ->
                val (id, localFile) = source ?: return@collect
                val current = player.currentMediaItem?.takeIf { it.mediaId == id } ?: return@collect
                // A downloaded YouTube file is audio-only. Library updates must not replace
                // an explicitly selected video stream with that local audio file.
                if (current.localConfiguration?.uri?.let {
                    dev.distillpod.core.YouTubeUrl.videoId(it.toString()) != null &&
                        it.getQueryParameter("distillpod_video") == "1"
                } == true) return@collect
                if (localFile == null || restoring) return@collect
                val prepared = withContext(Dispatchers.IO) {
                    val ep = dao.episode(current.mediaId) ?: return@withContext null
                    val base = mediaItem(ep.copy(localFile = null), dao.podcast(ep.podcastId))
                    graph.media.preparePlaybackItems(listOf(base)).single()
                } ?: return@collect
                val uri = prepared.localConfiguration?.uri
                if (uri?.scheme == "file" && current.localConfiguration?.uri != uri &&
                    player.currentMediaItem == current && !restoring) {
                    val position = player.currentPosition.coerceAtLeast(0)
                    swapCurrentSource(player, prepared, position)
                }
            }
        }
        scope.launch {
            combine(dao.observeAdSourceRows(), dao.observeAnalyses(), dao.observeTranscripts(), graph.preferences, graph.db.automation().observeAds()) { episodes, analyses, transcripts, prefs, choices ->
                episodes.mapNotNull { ep ->
                    val analysis = analyses.firstOrNull { it.episodeId == ep.id && it.state == "succeeded" } ?: return@mapNotNull null
                    val choice = choices.firstOrNull { it.episodeId == ep.id }
                    if (choice != null) { if (!choice.enabled || choice.revision != adSelectionRevision(analysis)) return@mapNotNull null }
                    else if (!prefs.skipAds) return@mapNotNull null
                    if (transcripts.none { it.episodeId == ep.id && it.revision == analysis.revision && it.fingerprint == ep.mediaFingerprint && it.state == "ready" && it.source != "online" }) return@mapNotNull null
                    val file = ep.localFile?.takeIf { File(it).isFile } ?: return@mapNotNull null
                    val ranges = runCatching { val ads = JSONArray(analysis.adsJson)
                        (0 until ads.length()).mapNotNull { i -> val ad = ads.getJSONObject(i)
                            if (ad.optBoolean("approved") && ad.getLong("startMs") >= 0 && ad.getLong("endMs") <= ep.durationMs && ad.getLong("endMs") > ad.getLong("startMs")) TimeRange(ad.getLong("startMs"), ad.getLong("endMs")) else null
                        }
                    }.getOrDefault(emptyList())
                    ep.id to AdConfig(file, normalizeRanges(ranges, ep.durationMs))
                }.toMap()
            }.flowOn(Dispatchers.IO).collect { adConfigs = it }
        }
        scope.launch {
            while (isActive) {
                if (player.isPlaying) player.currentMediaItem?.let { item ->
                    val config = adConfigs[item.mediaId]
                    val position = player.currentPosition.coerceAtLeast(0)
                    if (config != null && item.localConfiguration?.uri == Uri.fromFile(File(config.file))) {
                        adSkipping.target(item.mediaId, position, config.ranges, true)?.let { target ->
                            graph.playbackEvents.lastSkipped.value = SkippedMoment(item.mediaId, position, target)
                            player.seekTo(target)
                        }
                    }
                }
                delay(200)
            }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    override fun onDestroy() {
        applicationContext.appGraph.diagnostics.event("playback.service_stopped")
        applicationContext.appGraph.activeFiles.playback = null
        applicationContext.appGraph.activeFiles.queued = emptySet()
        val player=session?.player
        val finalId=player?.currentMediaItem?.mediaId
        val finalPosition=player?.currentPosition?.coerceAtLeast(0)?:0
        val finalDuration=player?.duration?.coerceAtLeast(0)?:0
        val stoppedAt=System.currentTimeMillis()
        val stoppedElapsed=android.os.SystemClock.elapsedRealtime()
        val serviceJob=scope.coroutineContext[Job]
        // The timer is in memory only; stopping the service drops it.
        sleepTimer?.release(); sleepTimer = null
        widget?.stop(); widget = null
        session?.let { it.player.release(); it.release() }; session = null
        pendingCloudChoice?.choice?.completion?.completeExceptionally(CancellationException("Playback service stopped"))
        pendingCloudChoice=null
        writes.close(); scope.cancel()
        // The application scope can finish the final atomic checkpoint after service jobs stop.
        // Joining prevents an older queued snapshot from overwriting this final position.
        if(finalId!=null)applicationContext.appGraph.scope.launch(Dispatchers.IO) {
            serviceJob?.join()
            runCatching {
                applicationContext.appGraph.ready.await()
                applicationContext.appGraph.portableSync.finishPlayback(finalId,finalPosition,finalDuration,stoppedAt,syncSession,stoppedElapsed)
            }
        }
        super.onDestroy()
    }
}
