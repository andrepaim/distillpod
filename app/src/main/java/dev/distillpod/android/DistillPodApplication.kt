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

class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val db = AppDatabase.open(context)
    val settings = AppSettings(context,db=db,portableChanged={syncAccount.kick()})
    val preferences = settings.flow.stateIn(scope, SharingStarted.Eagerly, Preferences())
    val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { it.proceed(it.request().newBuilder().header("User-Agent", "DistillPod/0.2 (Android)").build()) }.build()
    val syncAccount by lazy { dev.distillpod.android.sync.SubscriptionSyncAccount(this) }
    val podcasts = PodcastRepository(db, http, onSubscriptionChanged = { syncAccount.kick() }, onFeedNeeded = { jobs.refresh(it) })
    val portableSync by lazy {dev.distillpod.android.sync.PortableLibrarySync(db,changed={syncAccount.kick()})}
    val collectionSync by lazy {dev.distillpod.android.sync.CollectionSync(db,changed={syncAccount.kick()})}
    val activeFiles = ActiveFiles()
    val media = MediaRepository(context, db, settings, ResumableDownloader(http), activeFiles)
    val account = AccountRepository(context, scope)
    val jobs = AppJobs(context)
    val posts by lazy { PostRepository(db) }
    val socialResults by lazy { SocialResultRepository(db) { id, attempt -> dev.distillpod.android.work.SocialPostJobs.enqueue(context, id, attempt) } }
    val socialTools by lazy { dev.distillpod.android.ai.AccountSocialTools(account.session, settings) }
    val postAssets by lazy { dev.distillpod.android.sharing.PostAssets(java.io.File(context.filesDir, "post-images")) }
    val backup by lazy { LibraryBackup(db, postAssets) }
    val importedQueue=kotlinx.coroutines.flow.MutableStateFlow<List<String>?>(null)
    val playbackEvents = dev.distillpod.android.playback.PlaybackEvents()
    val playback by lazy { dev.distillpod.android.playback.PlaybackRepository(context, db, scope) }
    val voiceIdentity by lazy { dev.distillpod.android.voice.VoiceIdentity(context) }
    val voice: dev.distillpod.android.voice.VoiceCoordinator by lazy {
        dev.distillpod.android.voice.VoiceCoordinator(scope, { changed -> playback.pauseForVoice(changed,
                if (context.getSharedPreferences("voice", Context.MODE_PRIVATE).getBoolean("rewind", false)) 3000 else 0) },
            { lease -> withContext(Dispatchers.IO) { dev.distillpod.android.voice.VoiceContext.load(db, lease.episode, lease.position) } },
            { source -> dev.distillpod.android.voice.RealtimeVoice(source,
                dev.distillpod.android.voice.VoiceBroker(context.getString(R.string.voice_broker_url), voiceIdentity).token(), search = { query ->
                    withContext(Dispatchers.IO) { dev.distillpod.android.voice.VoiceContext.search(db, source, query) }
                }) },
            { dev.distillpod.android.voice.AndroidVoiceOutput(context) { voice.cancel(false) } })
    }
    val ai by lazy { dev.distillpod.android.ai.ChatGptGateway(account.session, settings) }
    val onlineTranscripts by lazy { OnlineTranscriptRepository(this) }
    val transcription by lazy { TranscriptionRepository(this) }
    private val confirmationSound by lazy {
        dev.distillpod.android.playback.DistillConfirmationSound(scope,settings.flow.map {it.distillSound},
            dev.distillpod.android.playback.SoundPoolConfirmationAudio(context))
    }
    val captures by lazy { CaptureRepository(db, ai, { account.state.value.connected }, { transcription.prepare(it, allowOnline = false) }, jobs::capture,{syncAccount.kick()},onSaved={confirmationSound.saved()}) }
    val understanding by lazy { EpisodeUnderstanding(db, ai, jobs::analyze, jobs::reply) }
    val explanations by lazy { MomentExplanation(db, ai, jobs::explain) }
    val recommendations by lazy { Recommendations(db, settings, podcasts, http, ai, jobs::recommend, jobs::recommendationPending) }
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
            portableSync.releaseSessions(); importFeasibility(); understanding.recoverInterrupted(); explanations.recoverInterrupted(); recommendations.recoverInterrupted()
            db.library().distillations().filter { it.state == "queued" }.forEach { jobs.capture(it.id) }
            db.library().episodes().filter { it.downloadState in listOf("queued", "downloading") }.forEach { jobs.download(it.id) }
            db.library().observeModels().first().filter { it.state in listOf("queued", "downloading") }.forEach { jobs.model(it.id) }
            db.library().observePosts().first().forEach { row ->
                val post = runCatching { SocialResult.parse(row.content) }.getOrNull()
                if (post != null && post.stage in listOf("queued", "preparing", "researching", "illustrating")) {
                    // KEEP avoids duplicating active work after an ordinary app restart.
                    val manager = androidx.work.WorkManager.getInstance(context)
                    val pending = manager.getWorkInfosForUniqueWork("social:${row.id}").get().any { !it.state.isFinished }
                    if (!pending) dev.distillpod.android.work.SocialPostJobs.enqueue(context,row.id,post.attempt)
                }
            }
            jobs.configureRefresh(); transcription.recover(); ready.complete(Unit)
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
