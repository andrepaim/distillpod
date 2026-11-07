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
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import dev.distillpod.core.ReviewedAdSkipping
import dev.distillpod.core.TimeRange
import dev.distillpod.core.normalizeRanges
import dev.distillpod.android.playback.SkippedMoment

/** Owns audio, progress and the queue even when there is no Activity. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private data class Snapshot(val ids: List<String>, val id: String?, val position: Long, val duration: Long, val complete: Boolean, val speed: Float, val at: Long = System.currentTimeMillis(), val boundary: Boolean = false, val playing: Boolean = false, val queueBoundary:Boolean=false, val elapsed: Long = android.os.SystemClock.elapsedRealtime())
    private val writes = Channel<Snapshot>(Channel.UNLIMITED)
    private val syncSession=java.util.UUID.randomUUID().toString()
    private var previousId: String? = null
    private var previousDuration = 0L
    private var previousPosition = 0L
    private var restoring = true
    private val playbackReady=CompletableDeferred<Unit>()
    private val browseCurrent=kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private data class AdConfig(val file: String, val ranges: List<TimeRange>)
    private var adConfigs = emptyMap<String, AdConfig>()
    private val adSkipping = ReviewedAdSkipping()
    override fun onCreate() {
        super.onCreate()
        applicationContext.appGraph.diagnostics.event("playback.service_started")
        val graph = applicationContext.appGraph
        // Observe projection before any Next event, including hosts using the legacy media browser.
        graph.carConnection
        val dao = graph.db.library()
        val player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(30000)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
        val health = dev.distillpod.android.playback.PlaybackHealthRecorder(this, player)
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
        player.addAnalyticsListener(health)
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
            },prepareMoment={id->graph.scope.launch(Dispatchers.IO){runCatching {graph.captures.prepareSaved(id)}.onFailure {graph.diagnostics.failure("capture",it,id)}};Unit},artwork=artwork::uri,
            voiceEnabled = { resources.getBoolean(R.bool.voice_enabled) },
            openCarVoice = { graph.carVoiceEntry.open() },
            onExternalPlaybackCommand = { graph.voice.cancel(false) },
            diagnostics = graph.diagnostics,
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
        scope.launch {
            graph.ready.await()
            combine(dao.observePodcasts(),dao.observeEpisodes(),dao.observeQueue(),browseCurrent) {pods,episodes,queue,current->
                listOf(pods,episodes.map {listOf(it.id,it.title,it.completed,it.localFile,it.downloadState,it.lastPlayedAt/60000)},queue,current)
            }.distinctUntilChanged().collectLatest {delay(500);session?.let {libraryCallback.libraryChanged(it)}}
        }
        fun snapshot(complete: Boolean = false, boundary: Boolean = false, queueBoundary:Boolean=false) {
            if (restoring) return
            val id = player.currentMediaItem?.mediaId
            transcription.update(id, player.isPlaying)
            graph.activeFiles.playback = player.currentMediaItem?.localConfiguration?.uri?.takeIf { it.scheme == "file" }?.path
            graph.activeFiles.queued = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).localConfiguration?.uri?.takeIf { uri -> uri.scheme == "file" }?.path }.toSet()
            writes.trySend(Snapshot((0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }, id,
                player.currentPosition.coerceAtLeast(0), player.duration.coerceAtLeast(0), complete, player.playbackParameters.speed, boundary=boundary, playing=player.isPlaying,queueBoundary=queueBoundary))
            previousId = id; previousDuration = player.duration.coerceAtLeast(0); previousPosition = player.currentPosition.coerceAtLeast(0)
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState:Int) {
                when(playbackState) {
                    Player.STATE_BUFFERING->graph.diagnostics.event("playback.buffering",reason="buffering")
                    Player.STATE_READY->graph.diagnostics.event("playback.ready",reason="ready")
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {graph.diagnostics.failure("playback",error)}
            override fun onIsPlayingChanged(isPlaying:Boolean) {graph.diagnostics.event("playback.state",reason=if(isPlaying) "playing" else "paused")}
            override fun onTimelineChanged(timeline:androidx.media3.common.Timeline,reason:Int) {
                if(reason==Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)snapshot(queueBoundary=true)
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                browseCurrent.value=item?.mediaId
                if (!restoring && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && previousId != null) {
                    writes.trySend(Snapshot(emptyList(), previousId, if (previousDuration > 0) previousDuration else previousPosition, previousDuration, true, player.playbackParameters.speed, boundary=true))
                }
                snapshot()
            }
            override fun onEvents(p: Player, events: Player.Events) {
                snapshot(p.playbackState == Player.STATE_ENDED, events.contains(Player.EVENT_IS_PLAYING_CHANGED) || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED))
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) player.currentMediaItem?.mediaId?.let { id ->
                    adSkipping.seek(id, newPosition.positionMs, adConfigs[id]?.ranges ?: emptyList())
                    snapshot(boundary=true)
                }
            }
        })
        scope.launch(Dispatchers.IO) {
            graph.ready.await()
            var lastQueue: List<String>? = null
            for (value in writes) {
                try {
                    val ep = value.id?.let { dao.episode(it) }
                    // Diagnostics may play a temporary clip; it must not replace the podcast queue.
                    if (value.id != null && ep == null) continue
                    graph.db.withTransaction {
                        if (ep != null && (value.playing || value.boundary)) graph.portableSync.checkpoint(ep.id, value.position, value.duration, value.complete, value.at, syncSession, value.elapsed, value.boundary, active=value.playing)
                        if (value.ids.isNotEmpty() || value.id == null) {
                            val known = value.ids.filter { dao.episode(it) != null }.distinct()
                            val imported=graph.importedQueue.value==known
                            if(imported)graph.importedQueue.value=null
                            if(imported)lastQueue=known
                            else if(lastQueue==null) {
                                dao.clearQueue();dao.insertQueue(known.mapIndexed {index,id->QueueEntryEntity(id,index)});lastQueue=known
                            } else if(value.queueBoundary && known!=lastQueue) {
                                graph.collectionSync.queue(known);lastQueue=known
                            }
                            dao.putPlayerSession(PlayerSessionEntity(episodeId = value.id, speed = value.speed))
                        }
                    }
                } catch (_: Exception) { /* A later snapshot can recover a transient write failure. */ }
            }
        }
        scope.launch {
            graph.ready.await()
            val restore = withContext(Dispatchers.IO) {
                val queue = dao.queue().mapNotNull { dao.episode(it.episodeId) }
                val podcasts = dao.podcasts().associateBy { it.id }
                Triple(queue, podcasts, dao.playerSession())
            }
            if (player.mediaItemCount == 0 && restore.first.isNotEmpty()) {
                val index = restore.first.indexOfFirst { it.id == restore.third?.episodeId }.coerceAtLeast(0)
                player.setMediaItems(restore.first.map { mediaItem(it, restore.second[it.podcastId]) }, index, restore.first[index].positionMs)
                player.setPlaybackSpeed(restore.third?.speed?.coerceIn(0.5f, 3f) ?: 1f)
                player.prepare()
            }
            restoring = false; playbackReady.complete(Unit); snapshot()
            while (isActive) { snapshot(player.playbackState == Player.STATE_ENDED); delay(1000) }
        }
        scope.launch {
            dao.observeQueue().collect {entries->
                if(restoring || player.mediaItemCount!=0 || entries.isEmpty())return@collect
                val restored=withContext(Dispatchers.IO) {
                    entries.mapNotNull {entry->dao.episode(entry.episodeId)?.let {ep->mediaItem(ep,dao.podcast(ep.podcastId))}}
                }
                if(player.mediaItemCount==0 && restored.isNotEmpty()) {
                    restoring=true
                    try {
                        val position=withContext(Dispatchers.IO) {dao.episode(restored.first().mediaId)?.positionMs?:0}
                        if(player.mediaItemCount==0){player.setMediaItems(restored,0,position);player.prepare()}
                    } finally {restoring=false}
                }
            }
        }
        scope.launch {
            dao.observeEpisodes().collect { episodes ->
                val current = player.currentMediaItem ?: return@collect
                val ep = episodes.firstOrNull { it.id == current.mediaId } ?: return@collect
                val local = ep.localFile?.let(::File)?.takeIf { it.isFile } ?: return@collect
                val uri = Uri.fromFile(local)
                if (current.localConfiguration?.uri != uri && !restoring) {
                    val podcast = withContext(Dispatchers.IO) { dao.podcast(ep.podcastId) }
                    val position = player.currentPosition.coerceAtLeast(0)
                    val playing = player.playWhenReady
                    player.replaceMediaItem(player.currentMediaItemIndex, mediaItem(ep, podcast))
                    player.seekTo(position); player.prepare(); player.playWhenReady = playing
                }
            }
        }
        scope.launch {
            combine(dao.observeEpisodes(), dao.observeAnalyses(), dao.observeTranscripts(), graph.preferences) { episodes, analyses, transcripts, prefs ->
                if (!prefs.skipAds) emptyMap() else episodes.mapNotNull { ep ->
                    val analysis = analyses.firstOrNull { it.episodeId == ep.id && it.state == "succeeded" } ?: return@mapNotNull null
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
        session?.let { it.player.release(); it.release() }; session = null
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
