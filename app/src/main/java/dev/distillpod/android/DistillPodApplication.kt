package dev.distillpod.android

import android.app.Application
import android.content.Context
import androidx.room.withTransaction
import dev.distillpod.android.ai.AccountRepository
import dev.distillpod.android.data.*
import dev.distillpod.android.work.AppJobs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DistillPodApplication : Application() {
    val graph by lazy { AppGraph(this) }
}
val Context.appGraph: AppGraph get() = (applicationContext as DistillPodApplication).graph

class AppGraph internal constructor(val context: Context, val scope: CoroutineScope) {
    constructor(context: Context) : this(context, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    val diagnostics = dev.distillpod.android.diagnostics.Diagnostics(context)
    val db = AppDatabase.open(context)
    val settings = AppSettings(context,db=db,portableChanged={syncAccount.kick()})
    val preferences = settings.flow.stateIn(scope, SharingStarted.Eagerly, Preferences())
    val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { it.proceed(it.request().newBuilder().header("User-Agent", "DistillPod/0.2 (Android)").build()) }.build()
    val syncAccount by lazy { dev.distillpod.android.sync.SubscriptionSyncAccount(this) }
    val youtube = dev.distillpod.android.video.YouTubeSource(dev.distillpod.android.video.AndroidYouTubeExtractor(context) { name, reason, elapsed, operation ->
        diagnostics.event(name, operation = operation, reason = reason, elapsedMs = elapsed)
    }, dev.distillpod.android.video.HttpYouTubeMetadataFetcher(http)).apply { onStreamRefresh = { diagnostics.event("youtube.stream_refreshed", reason = "ready") } }
    val podcasts = PodcastRepository(db, http, onSubscriptionChanged = { syncAccount.kick() }, onFeedNeeded = { jobs.refresh(it) }, youtube = youtube)
    val portableSync by lazy {dev.distillpod.android.sync.PortableLibrarySync(db,changed={syncAccount.kick()})}
    val collectionSync by lazy {dev.distillpod.android.sync.CollectionSync(db,changed={syncAccount.kick()})}
    val activeFiles = ActiveFiles()
    val media = MediaRepository(context, db, settings, ResumableDownloader(http), activeFiles,
        resolveAudio = { url -> if (dev.distillpod.core.YouTubeUrl.videoId(url) != null) youtube.streams(url).audio else url },
        onValidated = { diagnostics.event("videocast.download_validated", reason = "succeeded") })
    val account = AccountRepository(context, scope)
    val jobs = AppJobs(context)
    val posts by lazy { PostRepository(db) }
    val socialResults by lazy { SocialResultRepository(db) { id, attempt -> dev.distillpod.android.work.SocialPostJobs.enqueue(context, id, attempt) } }
    val socialTools by lazy { dev.distillpod.android.ai.AccountSocialTools(account.session, settings) }
    val postAssets by lazy { dev.distillpod.android.sharing.PostAssets(java.io.File(context.filesDir, "post-images")) }
    // Restored progress, completions and moments of shared feeds upload like local edits.
    val backup by lazy { LibraryBackup(db, postAssets, portable = portableSync) }
    val importedQueue=kotlinx.coroutines.flow.MutableStateFlow<List<String>?>(null)
    val playbackEvents = dev.distillpod.android.playback.PlaybackEvents()
    val playback by lazy { dev.distillpod.android.playback.PlaybackRepository(context, db, scope) }
    val carVoiceEntry = dev.distillpod.android.voice.CarVoiceEntry()
    val voiceApiKey by lazy { dev.distillpod.android.voice.VoiceApiKeyStore(context) }
    val headphoneVoice = dev.distillpod.android.voice.HeadphoneVoiceShortcut()
    val carConnection by lazy {
        kotlinx.coroutines.flow.MutableStateFlow<Int?>(null).also { state ->
            carConnectionState(context).observeForever { state.value = it }
        }
    }
    val voice: dev.distillpod.android.voice.VoiceCoordinator by lazy {
        dev.distillpod.android.voice.VoiceCoordinator(scope, { changed -> playback.pauseForVoice(changed,
                if (context.getSharedPreferences("voice", Context.MODE_PRIVATE).getBoolean("rewind", false)) 3000 else 0) },
            { lease -> withContext(Dispatchers.IO) { dev.distillpod.android.voice.VoiceContext.load(db, lease.episode, lease.position) } },
            { source -> dev.distillpod.android.voice.RealtimeVoice(source,
                withContext(Dispatchers.IO) { voiceApiKey.requireKey() }, search = { query ->
                    withContext(Dispatchers.IO) { dev.distillpod.android.voice.VoiceContext.search(db, source, query) }
                }, ready = { dev.distillpod.android.voice.voiceReadyTone(if (voice.state.value.origin == dev.distillpod.android.voice.VoiceOrigin.HEADPHONES && android.os.Build.VERSION.SDK_INT >= 31)
                    android.media.AudioManager.STREAM_VOICE_CALL else android.media.AudioManager.STREAM_MUSIC) }) },
            { dev.distillpod.android.voice.AndroidVoiceOutput(context,
                communication = voice.state.value.origin == dev.distillpod.android.voice.VoiceOrigin.HEADPHONES && android.os.Build.VERSION.SDK_INT >= 31) { voice.cancel(false) } },
            diagnosticEvent={name,reason -> diagnostics.event(name,reason=reason,
                source=reason.takeIf {it in setOf("phone","car","headphones")} ?: voice.state.value.origin?.name?.lowercase() ?: "background")},
            diagnosticFailure={diagnostics.failure("voice",it,source=voice.state.value.origin?.name?.lowercase() ?: "background")},
            preflight = {
                val (episode, position) = playback.voicePosition()
                    ?: throw dev.distillpod.android.voice.VoiceContextUnavailable(dev.distillpod.android.voice.VoiceReadiness.EPISODE_UNAVAILABLE)
                withContext(Dispatchers.IO) { dev.distillpod.android.voice.VoiceContext.check(db, episode, position) }
            })
    }
    val ai by lazy { dev.distillpod.android.ai.ChatGptGateway(account.session, settings) }
    val onlineTranscripts by lazy { OnlineTranscriptRepository(this) }
    val transcription by lazy { TranscriptionRepository(this) }
    private val confirmationSound by lazy {
        dev.distillpod.android.playback.DistillConfirmationSound(scope,settings.flow.map {it.distillSound},
            dev.distillpod.android.playback.SoundPoolConfirmationAudio(context))
    }
    val captures by lazy { CaptureRepository(db, ai, { account.state.value.connected }, { transcription.prepare(it, allowOnline = false) }, jobs::capture,{syncAccount.kick()},onSaved={confirmationSound.saved()}, diagnostics=diagnostics) }
    val understanding by lazy { EpisodeUnderstanding(db, ai, jobs::analyze, jobs::reply) }
    val explanations by lazy { MomentExplanation(db, ai, jobs::explain) }
    val recommendations by lazy { Recommendations(db, settings, podcasts, http, ai, jobs::recommend, jobs::recommendationPending) }
    val automation by lazy { ShowAutomation(this) }
    val ready = CompletableDeferred<Unit>()
    init {
        scope.launch(Dispatchers.IO) {
            combine(db.library().observeTranscripts(),db.library().observeAnalyses(),db.library().observeMessages(),db.library().observeResearch()) {ts,analyses,messages,reports->
                listOf(ts.filter {it.state=="ready"}.map {listOf(it.episodeId,it.revision,it.fingerprint)},
                    analyses.filter {it.state=="succeeded"}.map {listOf(it.episodeId,it.revision,it.summary.hashCode())},
                    messages.filter {it.state=="succeeded"}.map {it.id},reports.filter {it.state=="succeeded"}.map {it.id})
            }.distinctUntilChanged().collectLatest {
                delay(1000)
                if(db.sync().view("__extended_scope")!=null)syncAccount.kick()
            }
        }
        scope.launch(Dispatchers.IO) {
        try {
            if(diagnostics.enabled) {
            val reliabilityStates=mutableMapOf<Pair<String,String>,String>()
            db.library().transcripts().forEach {row->reliabilityStates["transcription" to row.revision]=when(row.state){
                "ready"->"completed";"partial"->"partial";"waiting_model"->"waiting";"stale"->"cancelled";"failed"->"failed";"paused"->"paused";"queued","processing"->"queued";else->"unknown"}}
            db.library().distillations().forEach {row->reliabilityStates["distillation" to row.id]=when(row.state){
                "succeeded"->"completed";"failed","needs_context","unclear_source","no_speech"->"failed"
                "queued"->"queued";"running","reviewing"->"interrupted";"waiting_auth","waiting_quota","waiting_transcript","review_source"->"waiting";else->"unknown"}}
            diagnostics.reconcile(reliabilityStates)
            }
            portableSync.releaseSessions(); importFeasibility(); understanding.recoverInterrupted(); explanations.recoverInterrupted(); recommendations.recoverInterrupted()
            db.library().distillations().filter { it.state == "queued" }.forEach { jobs.capture(it.id) }
            jobs.recoverDownloads()
            db.library().observePosts().first().forEach { row ->
                val post = runCatching { SocialResult.parse(row.content) }.getOrNull()
                if (post != null && post.stage in listOf("queued", "preparing", "researching", "illustrating")) {
                    // KEEP avoids duplicating active work after an ordinary app restart.
                    val manager = androidx.work.WorkManager.getInstance(context)
                    val pending = manager.getWorkInfosForUniqueWork("social:${row.id}").get().any { !it.state.isFinished }
                    if (!pending) dev.distillpod.android.work.SocialPostJobs.enqueue(context,row.id,post.attempt)
                }
            }
            jobs.configureNetworkPolicy(); transcription.recover(); ready.complete(Unit)
            automation.observe()
            captures.resumeSaved()
        }
        catch (error: Exception) { ready.completeExceptionally(error) }
    } }

    private suspend fun importFeasibility() {
        val marker = context.getSharedPreferences("migration", Context.MODE_PRIVATE)
        if (marker.getBoolean("feasibility-imported", false)) return
        if (context.getDatabasePath("distillpod.db").isFile) {
            LibraryStore(context).use { source ->
                db.withTransaction {
                    for (ep in source.episodes()) {
                        val podcastId = dev.distillpod.core.episodeId("podcast", ep.feedUrl)
                        if (db.library().podcast(podcastId) == null) db.library().putPodcast(PodcastEntity(podcastId, ep.feedUrl, "Imported podcast"))
                        if (db.library().episode(ep.id) == null) db.library().putEpisode(EpisodeEntity(ep.id, podcastId, ep.id, ep.title, ep.audioUrl,
                            positionMs = source.progress(ep.id), mediaFingerprint = dev.distillpod.core.episodeId("enclosure", ep.audioUrl)))
                    }
                    for (saved in source.distillations()) if (db.library().episode(saved.episodeId) != null && db.library().distillation(saved.id) == null) {
                        db.library().putDistillation(DistillationEntity(saved.id, saved.episodeId, (saved.positionMs - 60000).coerceAtLeast(0), saved.positionMs,
                            saved.excerpt, insight = saved.insight, state = if (saved.insight == null) "waiting_transcript" else "imported"))
                    }
                }
            }
        }
        check(marker.edit().putBoolean("feasibility-imported", true).commit())
    }
}
