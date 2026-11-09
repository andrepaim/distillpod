package dev.distillpod.android.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.work.*
import io.sentry.*
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.SentryId
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import io.sentry.logger.ILoggerBatchProcessor
import io.sentry.logger.SentryLogParameters
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** This is the only application boundary allowed to call Sentry. Never accept content fields. */
class Diagnostics(private val context: Context, private val dsn:String = context.getString(dev.distillpod.android.R.string.diagnostics_dsn)) {
    private val prefs=context.getSharedPreferences("diagnostics",Context.MODE_PRIVATE)
    internal val queue=DiagnosticQueue(java.io.File(context.noBackupFilesDir,"diagnostics"),onDrop={prefs.edit().putLong("dropped",prefs.getLong("dropped",0)+1).commit()})
    private val lock=Any()
    private val budget=DiagnosticBudget(prefs)
    private val admissionLane=ThreadLocal<DiagnosticBudget.Lane>()
    internal val journal=OperationJournal(java.io.File(context.noBackupFilesDir,"reliability/journal"),release=context.packageManager.getPackageInfo(context.packageName,0).versionName ?: "unknown")
    private val persisted=ThreadLocal<Boolean>()
    private val timings=mutableMapOf<String,Long>()
    private var voiceOperation:String?=null
    private var playbackOperation:String?=null
    @Volatile private var generation=0L
    private val session=UUID.randomUUID().toString()
    private val installation=prefs.getString("installation",null) ?: UUID.randomUUID().toString().also {prefs.edit().putString("installation",it).apply()}
    private val executor=java.util.concurrent.ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(128),java.util.concurrent.ThreadFactory { runnable -> Thread(runnable,"distillpod-diagnostics").apply {isDaemon=true} },java.util.concurrent.RejectedExecutionHandler {_,_->prefs.edit().putLong("dropped",prefs.getLong("dropped",0)+1).apply()})
    val status=MutableStateFlow("Disabled")
    val enabled get()=prefs.getBoolean("enabled",false)
    val usageEnabled get()=prefs.getBoolean("usage_enabled",false)
    val collecting get()=enabled || usageEnabled
    internal val correlationEpoch:String? get()=if(enabled && configured)prefs.getString("correlation_epoch",null) else null
    internal fun acceptsCorrelation(correlation:DiagnosticCorrelation?):Boolean =
        correlation?.collectionEpoch?.let {it==correlationEpoch} ?: true
    val reportingHealth get()="${queue.files().size} pending envelopes · ${prefs.getLong("dropped",0)+journal.dropped} collection warnings · last acknowledgement: ${prefs.getString("last_upload","none")}"
    val configured get()=dsn.isNotBlank()
    val reference get()="${context.packageManager.getPackageInfo(context.packageName,0).versionName} / installation $installation / session $session"
    init {
        if(enabled && configured && !prefs.contains("correlation_epoch"))
            prefs.edit().putString("correlation_epoch",UUID.randomUUID().toString()).commit()
        if(collecting && configured) safeInitialize()
    }
    fun consent(allow:Boolean) = synchronized(lock) {
        generation++
        prefs.edit().putBoolean("enabled",allow).apply {
            if(allow && !enabled)putString("correlation_epoch",UUID.randomUUID().toString())
            if(!allow)remove("correlation_epoch")
        }.commit()
        Sentry.close()
        queue.clear()
        if(!allow){journal.clear();prefs.edit().remove("suppressed_summaries").commit()}
        if(collecting && configured) safeInitialize() else {
            WorkManager.getInstance(context).cancelUniqueWork("diagnostics-upload")
            WorkManager.getInstance(context).cancelUniqueWork("diagnostics-recovery")
            queue.clear(); java.io.File(context.noBackupFilesDir,"sentry").deleteRecursively()
            status.value=if(allow) "Cloud reporting is not configured in this build" else "Disabled"
        }
    }
    fun usageConsent(allow:Boolean) = synchronized(lock) {
        generation++;prefs.edit().putBoolean("usage_enabled",allow).commit();Sentry.close()
        queue.clear();java.io.File(context.noBackupFilesDir,"sentry").deleteRecursively()
        if(collecting && configured)safeInitialize() else {
            WorkManager.getInstance(context).cancelUniqueWork("diagnostics-upload")
            WorkManager.getInstance(context).cancelUniqueWork("diagnostics-recovery")
            status.value="Disabled"
        }
    }
    fun usage(feature:String,source:String="phone") {
        if(!usageEnabled || !configured || feature !in OperationJournal.KINDS || source !in OperationJournal.SOURCES)return
        synchronized(lock) {
            if(!usageEnabled)return
            runCatching {log("usage.feature",mapOf("feature" to feature,"source" to source,"stream" to "usage"))}
        }
    }
    internal fun transcriptQuality(operation:String,source:String,model:String,language:String,duration:Long,covered:Long,empty:Boolean) {
        if(!enabled || !configured || runCatching {UUID.fromString(operation)}.isFailure)return
        synchronized(lock) {
            if(!enabled)return
            runCatching {log("transcription.quality",mapOf("stream" to "diagnostics","operation_id" to operation,
                "transcript_source" to source.takeIf {it in setOf("publisher","whisper","online")} .orEmpty().ifEmpty {"other"},
                "model_category" to model.takeIf {it in setOf("tiny","tiny.en","base","base.en","small","small.en","medium","large")} .orEmpty().ifEmpty {"other"},
                "language_category" to language.takeIf {it in setOf("auto","en","pt","es","de","fr","zh","ja","ru")} .orEmpty().ifEmpty {"other"},
                "audio_duration_ms" to duration.coerceAtLeast(0),"covered_audio_ms" to covered.coerceAtLeast(0),"empty_output" to empty,"test_traffic" to false))}
        }
    }
    internal fun contextSummary(operation:String, evidence:ContextPreparationEvidence, elapsed:Long, correlation:DiagnosticCorrelation?) {
        if(!enabled || !configured)return
        synchronized(lock) {
            if(!enabled || !acceptsCorrelation(correlation))return
            runCatching {
                val fields=evidence.fields(elapsed)+(correlation ?: DiagnosticCorrelation()).fields()+
                    mapOf("stream" to "diagnostics","test_traffic" to dev.distillpod.android.sync.SubscriptionCloudAccess.instrumentation)
                val id=operation.takeIf {runCatching {UUID.fromString(it)}.isSuccess}
                log("capture.context_summary",fields+if(id!=null)mapOf("operation_id" to id) else emptyMap())
            }
        }
    }
    /** Runs on the IO startup path, after reading authoritative application state. */
    internal fun reconcile(states:Map<Pair<String,String>,String>):Unit = synchronized(lock) {
        if(!enabled || dev.distillpod.android.sync.SubscriptionCloudAccess.instrumentation)return
        runCatching {journal.recover {kind,id->states[kind to id]};executor.execute {flushSummaries()}}
    }
    internal fun flushSummaries() {
        val epoch=generation
        if(!enabled || !configured || dev.distillpod.android.sync.SubscriptionCloudAccess.instrumentation)return
        synchronized(lock) {
            if(!enabled || epoch!=generation)return
            runCatching {
                val healthFields=budget.fields()
                val exhaustion=budget.exhaustion()
                val last=prefs.getLong("last_health",0)
                if((System.currentTimeMillis()-last>6*60*60*1000 || exhaustion!=prefs.getString("health_exhaustion","")) && log("diagnostics.health",mapOf("stream" to "diagnostics",
                    "pending_envelopes" to queue.files().size,"dropped_reports" to (prefs.getLong("dropped",0)+journal.dropped),"test_traffic" to false)+healthFields))prefs.edit().putLong("last_health",System.currentTimeMillis()).putString("health_exhaustion",exhaustion).apply()
            }
        }
        val pending=runCatching {journal.pending()}.getOrElse {return}
        val denied=mutableMapOf<DiagnosticBudget.Lane,MutableSet<String>>()
        for(row in pending) {
            // Release the consent lock between records: a large recovered backlog
            // must not hold up a foreground media command or consent withdrawal.
            val accepted=synchronized(lock) {
                if(!enabled || epoch!=generation)return
                val lane=DiagnosticBudget.lane("operation.summary",row.state)
                if(!journal.isPending(row.id)) {
                    true // Another flush acknowledged this snapshot while the lock was released.
                } else if(!budget.available(lane)) {
                    denied.getOrPut(lane){mutableSetOf()}.add(row.id)
                    false
                } else runCatching {
                    val stored=log("operation.summary",mapOf("summary_id" to row.id,"operation_id" to row.operation,
                        "attempt_id" to row.attempt,"feature" to row.kind,"outcome" to row.state,"reason" to row.reason,
                        "source" to row.source,"sequence" to row.sequence,"operation_started_ms" to row.started,
                        "observed_at_ms" to row.updated,"operation_release" to row.release,"test_traffic" to (row.reason=="synthetic"),"elapsed_ms" to row.elapsed,"stream" to "diagnostics") + (row.correlation ?: DiagnosticCorrelation()).fields())
                    if(stored)journal.acknowledge(row.id)
                    stored
                }.getOrDefault(false)
            }
            // A rejected routine row must not block later terminal outcomes.
            if(!accepted)continue
        }
        synchronized(lock) {
            if(enabled && epoch==generation)budget.suppressedSummaries(denied,journal.pending().map {it.id}.toSet())
        }
    }
    private fun log(name:String,fields:Map<String,Any>):Boolean {
        persisted.set(false)
        admissionLane.set(DiagnosticBudget.lane(name,fields["outcome"] as? String))
        try {
            val attributes=fields+mapOf("emitted_at_ms" to System.currentTimeMillis(),"schema_version" to 1,"event_id" to UUID.randomUUID().toString(),"installation_id" to installation,"session_id" to session)
            Sentry.logger().log(SentryLogLevel.INFO,SentryLogParameters.create(SentryAttributes.fromMap(attributes)),name)
            return persisted.get()==true
        } finally {persisted.remove();admissionLane.remove()}
    }
    private fun observe(name:String, operation:String?, reason:String, source:String, attempt:String?, correlation:DiagnosticCorrelation?, observedAt:Long) {
        if(dev.distillpod.android.sync.SubscriptionCloudAccess.instrumentation)return
        val kind=name.substringBefore('.')
        if(kind !in OperationJournal.KINDS)return
        if(name=="voice.started") {voiceOperation=UUID.randomUUID().toString()}
        if(name=="playback.service_started")playbackOperation=UUID.randomUUID().toString()
        val id=operation ?: when(kind){"voice"->voiceOperation;"playback"->playbackOperation;else->null} ?: return
        if(name=="capture.transcript_link" || name=="transcription.dependency") {
            journal.transition("distillation",id,journal.latest().lastOrNull {it.kind=="distillation" && it.operation==id}?.state ?: "waiting",
                reason,source,correlation=correlation,observedAt=observedAt)
            return
        }
        if(name=="capture.saved") {
            if(reason=="created")journal.transition("distillation",id,"waiting","transcript_required",source,correlation=correlation,observedAt=observedAt)
            return
        }
        val state=when(name) {
            "capture.started"->"running"
            "capture.completed"->"completed"
            "capture.prepared"->when(reason) {"queued"->"queued";"waiting_auth","waiting_quota","waiting_transcript","review_source"->"waiting";else->"failed"}
            "distillation.queued"->if(reason=="queued")"queued" else "waiting"
            "transcription.queued"->if(reason in setOf("scheduled","queued"))"queued" else "waiting"
            "transcription.started","distillation.started","voice.started","playback.service_started"->"running"
            "transcription.stage"->if(reason=="queued")return else "running"
            "transcription.partial"->"partial"
            "transcription.completed"->"completed"
            "transcription.paused"->"paused"
            "transcription.cancelled","distillation.cancelled"->"cancelled"
            "capture.waiting"->"waiting"
            "distillation.finished"->if(reason=="succeeded")"completed" else "failed"
            "voice.resume_failed"->"failed"
            "voice.finished"->if(reason=="succeeded")"completed" else if(reason=="cancelled")"cancelled" else "failed"
            "playback.service_stopped"->"completed"
            else->if(name.endsWith(".failed"))if(reason=="cancelled")"interrupted" else "failed" else null
        } ?: return
        val feature=if(name in setOf("capture.waiting","capture.prepared"))"distillation" else kind
        journal.transition(feature,id,state,reason,if(kind=="voice")reason.takeIf {it in OperationJournal.SOURCES} ?: "background" else source,attempt,correlation,observedAt)
    }
    private fun safeInitialize() {
        try {initialize()}catch(_:Exception) {Sentry.close();status.value="Diagnostics could not start"}
    }
    private fun initialize() {
        val epoch=generation
        SentryAndroid.init(context) { options ->
            options.dsn=dsn
            options.cacheDirPath=java.io.File(context.noBackupFilesDir,"sentry").absolutePath
            options.isSendDefaultPii=false
            options.isEnableAutoSessionTracking=false
            options.isEnableUserInteractionBreadcrumbs=false
            options.isEnableActivityLifecycleBreadcrumbs=false
            options.isEnableAppLifecycleBreadcrumbs=false
            options.isEnableSystemEventBreadcrumbs=false
            options.isEnableNetworkEventBreadcrumbs=false
            options.isAttachScreenshot=false
            options.isAttachViewHierarchy=false
            options.isAttachThreads=false
            options.isSendClientReports=false
            options.environment=if(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0) "staging" else "production"
            val info=context.packageManager.getPackageInfo(context.packageName,0)
            options.release="${context.packageName}@${info.versionName}+${info.longVersionCode}"
            options.setBeforeBreadcrumb { _,_ -> null }
            options.setBeforeSend { event,_ ->
                if(!enabled || epoch!=generation) null else {
                    event.message=null;event.user=null;event.request=null;event.serverName=null
                    event.breadcrumbs=null;event.extras=null;event.threads=null
                    event.contexts.keys().toList().forEach {event.contexts.remove(it)}
                    event.setTag("session_id",session)
                    event.setTag("installation_id",installation)
                    event.setTag("android_api",Build.VERSION.SDK_INT.toString())
                    event.exceptions?.forEach { exception ->
                        exception.value="Diagnostic failure: ${event.getTag("reason")?.takeIf {it in REASONS} ?: "internal"}"
                        exception.stacktrace?.frames?.forEach { frame ->
                            frame.vars=null;frame.absPath=null;frame.preContext=null;frame.postContext=null;frame.contextLine=null
                        }
                    }
                    event
                }
            }
            // Persist logs synchronously, including those preceding an abrupt process exit.
            options.logs.isEnabled=true
            options.logs.setBeforeSend { log -> if(log.body in EVENT_NAMES && collecting && epoch==generation) log else null }
            options.logs.setLoggerBatchProcessorFactory { opts,_ -> object:ILoggerBatchProcessor {
                override fun add(event:SentryLogEvent) {
                    val envelope=SentryEnvelope(SentryId(),opts.sdkVersion,SentryEnvelopeItem.fromLogs(opts.serializer,SentryLogEvents(listOf(event))))
                    persist(envelope,opts,epoch,admissionLane.get() ?: DiagnosticBudget.lane(event.body))
                }
                override fun flush(timeoutMillis:Long) {}
                override fun close(isRestarting:Boolean) {}
            } }
            options.setTransportFactory { opts,_ -> object:ITransport {
                override fun send(envelope:SentryEnvelope,hint:Hint) { persist(envelope,opts,epoch) }
                override fun flush(timeoutMillis:Long) {}
                override fun getRateLimiter():RateLimiter?=null
                override fun close() {}
                override fun close(isRestarting:Boolean) {}
            } }
        }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("diagnostics-recovery",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DiagnosticsUploadWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        status.value="Enabled"
        // The event executor flushes recovered summaries off the startup thread.
        event("app.started")
        schedule()
    }
    private fun persist(envelope:SentryEnvelope,options:SentryOptions,epoch:Long,logLane:DiagnosticBudget.Lane=DiagnosticBudget.Lane.ROUTINE) {
        try { synchronized(lock) {
            if(!collecting || generation!=epoch)return
            val allowed=envelope.items.filter {it.header.type in setOf(SentryItemType.Event,SentryItemType.Log)}
            if(allowed.isEmpty())return
            val lane=if(allowed.any {it.header.type==SentryItemType.Event})DiagnosticBudget.Lane.ERROR else logLane
            if(!budget.admit(lane))return
            val safeEnvelope=SentryEnvelope(envelope.header,allowed)
            val bytes=ByteArrayOutputStream().also {options.serializer.serialize(safeEnvelope,it)}.toByteArray()
            persisted.set(queue.append(bytes,priority=lane!=DiagnosticBudget.Lane.ROUTINE));status.value="Pending upload"
        };schedule() } catch(_:Exception) { /* Diagnostics must never break the app. */ }
    }
    private fun schedule() {
        try {
            val request=OneTimeWorkRequestBuilder<DiagnosticsUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(10,TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("diagnostics-upload",ExistingWorkPolicy.KEEP,request)
        }catch(_:Exception) {}
    }
    fun event(name:String, operation:String?=null, reason:String="none", source:String="background", elapsedMs:Long?=null, related:String?=null, attempt:String?=null, correlation:DiagnosticCorrelation?=null) {
        if(usageEnabled && configured) when(name) {
            "voice.started"->usage("voice",reason)
            "capture.accepted"->usage("capture",source)
            "playback.state"->if(reason=="playing")usage("playback",source)
        }
        if(!enabled || !configured)return
        if(name !in EVENT_NAMES || reason !in REASONS || source !in OperationJournal.SOURCES || !acceptsCorrelation(correlation))return
        val observedAt=System.currentTimeMillis()
        // Capture the context before asynchronous emission; never attach a newer run to an old event.
        val snapshot=correlation
        val epoch=synchronized(lock) {
            if(!enabled || !acceptsCorrelation(correlation))return
            runCatching {observe(name,operation,reason,source,attempt,snapshot,observedAt)}
            generation
        }
        val observedTick=android.os.SystemClock.elapsedRealtime()
        executor.execute {if(enabled && generation==epoch) {eventNow(name,operation,reason,source,elapsedMs,related,attempt,observedTick,snapshot,observedAt,epoch);flushSummaries()}}
    }
    /** One compact terminal observation. No arbitrary attributes or provider strings cross this boundary. */
    internal fun attemptSummary(summary:DistillationOutcome, correlation:DiagnosticCorrelation?, observedAt:Long=System.currentTimeMillis()) {
        if(!enabled || !configured || dev.distillpod.android.sync.SubscriptionCloudAccess.instrumentation)return
        val execution=correlation?.executionId ?: return
        synchronized(lock) {
            if(!enabled || !acceptsCorrelation(correlation))return
            runCatching {
                log("distillation.attempt_summary",summary.fields()+correlation.fields()+mapOf(
                    "summary_id" to execution,"operation_id" to (correlation.momentId ?: execution),
                    "observed_at_ms" to observedAt,"stream" to "diagnostics",
                    "test_traffic" to dev.distillpod.android.sync.SubscriptionCloudAccess.instrumentation))
            }
        }
    }
    internal fun awaitIdle() {executor.submit {}.get(5,TimeUnit.SECONDS)}
    private fun eventNow(name:String, operation:String?, reason:String, source:String, elapsedMs:Long?,related:String?,attempt:String?,observedTick:Long,correlation:DiagnosticCorrelation?,observedAt:Long,expectedGeneration:Long) {
        synchronized(lock) {
            if(!enabled || !configured || generation!=expectedGeneration || !acceptsCorrelation(correlation))return
            try {
                // Categorical fields only. IDs must be random UUIDs, never hashes of content or URLs.
                if(name !in EVENT_NAMES)return
                if(reason !in REASONS || source !in setOf("background","phone","headphones","car"))return
                val attributes=mutableMapOf<String,Any>("event_id" to UUID.randomUUID().toString(),"session_id" to session,"installation_id" to installation,"schema_version" to 1,"test_traffic" to (name.startsWith("diagnostics.") || dev.distillpod.android.sync.SubscriptionCloudAccess.instrumentation),"reason" to reason,"source" to source,"android_api" to Build.VERSION.SDK_INT)
                attributes.putAll((correlation ?: DiagnosticCorrelation()).fields())
                attributes["observed_at_ms"]=observedAt
                attributes["emitted_at_ms"]=System.currentTimeMillis()
                synchronized(lock) {
                    val tick=observedTick
                    if(name in setOf("voice.started","playback.buffering","playback.service_started"))timings[name]=tick
                    val start=when(name){"voice.audio"->"voice.started";"playback.ready"->"playback.buffering";"playback.state"->"playback.service_started";else->null}
                    if(start!=null)timings.remove(start)?.let {attributes["stage_elapsed_ms"]=(tick-it).coerceAtLeast(0)}
                    if(name=="playback.state" && reason=="playing")timings["playing"]=tick
                    if(name=="playback.state" && reason=="paused" || name=="playback.service_stopped")timings.remove("playing")?.let {attributes["playing_ms"]=(tick-it).coerceAtLeast(0)}
                }
                operation?.takeIf {runCatching {UUID.fromString(it)}.isSuccess}?.let {attributes["operation_id"]=it}
                related?.takeIf {runCatching {UUID.fromString(it)}.isSuccess}?.let {attributes[if(name=="capture.completed")"moment_id" else "transcript_id"]=it}
                attempt?.takeIf {runCatching {UUID.fromString(it)}.isSuccess}?.let {attributes["attempt_id"]=it}
                elapsedMs?.takeIf {it>=0}?.let {attributes["elapsed_ms"]=it}
                val network=context.getSystemService(ConnectivityManager::class.java)
                attributes["network"]=if(network.activeNetwork==null) "offline" else if(network.isActiveNetworkMetered) "metered" else "unmetered"
                Sentry.logger().log(SentryLogLevel.INFO,SentryLogParameters.create(SentryAttributes.fromMap(attributes)),name)
            }catch(_:Exception) {}
        }
    }
    /** Intentional handled failure through the same path used by real operations. */
    fun test() {
        event("diagnostics.test",source="phone")
        synchronized(lock) {if(enabled && configured)runCatching {
            val id=UUID.randomUUID().toString()
            journal.transition("transcription",id,"running","synthetic","phone")
            journal.transition("transcription",id,"failed","synthetic","phone")
            journal.transition("transcription",id,"running","synthetic","phone")
            journal.transition("transcription",id,"completed","synthetic","phone")
            flushSummaries()
        }}
        try {throw IllegalStateException("Synthetic diagnostics verification")}catch(error:Exception) {failure("diagnostics",error)}
    }
    fun failure(stage:String,error:Throwable,operation:String?=null,source:String="background",correlation:DiagnosticCorrelation?=null) {
        event("$stage.failed",operation,reason(error),source=source,correlation=correlation)
        if(!enabled || !configured || !acceptsCorrelation(correlation) || error is kotlinx.coroutines.CancellationException)return
        synchronized(lock) {
            if(!enabled || !acceptsCorrelation(correlation))return
            try {
                // Copy stack frames, never message/cause/suppressed payloads from the original throwable.
                val safe=RuntimeException("Diagnostic failure: ${reason(error)}").also {it.stackTrace=error.stackTrace}
                Sentry.captureException(safe) { scope ->
                    scope.setTag("stage",stage)
                    (correlation ?: DiagnosticCorrelation()).fields().forEach { (key,value) -> scope.setTag(key,value.toString()) }
                    scope.setTag("source",source.takeIf {it in OperationJournal.SOURCES} ?: "background")
                    scope.setTag("test_traffic",(stage=="diagnostics" || dev.distillpod.android.sync.SubscriptionCloudAccess.instrumentation).toString())
                    scope.setTag("reason",reason(error))
                    scope.setTag("exception_type",error.javaClass.name.take(160))
                    operation?.takeIf {runCatching {UUID.fromString(it)}.isSuccess}?.let {scope.setTag("operation_id",it)}
                }
            }catch(_:Exception) {}
        }
    }
    internal fun uploaded() { prefs.edit().putString("last_upload",java.time.Instant.now().toString()).apply();status.value="Last upload: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}" }
    companion object {
        private val EVENT_NAMES=setOf("capture.context_summary","distillation.attempt_summary","youtube.extraction_started","youtube.extraction_completed","youtube.extraction_failed","youtube.extraction_retry","youtube.stream_refreshed","videocast.video_requested","videocast.audio_restored","videocast.first_frame","videocast.failed","videocast.download_validated","transcription.quality","diagnostics.health","operation.summary","usage.feature","transcription.partial","transcription.paused","transcription.cancelled","distillation.cancelled","voice.recording","voice.text","voice.audio","voice.resumed","voice.resume_failed","playback.buffering","playback.ready","playback.focus","playback.route","app.started","diagnostics.test","diagnostics.failed","headphone.received","headphone.rejected","headphone.previous","headphone.next",
            "capture.started","capture.completed","capture.requested","capture.accepted","capture.saved","capture.waiting","capture.transcript_link","capture.prepared","capture.failed",
            "transcription.dependency","transcription.started","transcription.stage","transcription.queued","transcription.completed","transcription.failed",
            "distillation.discarded","distillation.queued","distillation.started","distillation.finished","distillation.failed","playback.service_started","playback.service_stopped","playback.state","playback.failed",
            "voice.started","voice.finished","voice.failed","sync.finished","sync.failed","worker.failed")
        private val REASONS=setOf("restricted","throttled","formats","busy","partial","superseded","reconciled","process_interrupted","buffering","ended","resume_succeeded","resume_failed","none","created","deduplicated","transcript_required","waiting","waiting_auth","waiting_quota","waiting_transcript","queued","unknown",
            "no_speech","needs_context","review_source","unclear_source","succeeded","failed","ready","rejected","disabled","accepted","duplicate","cancelled","repeat","untrusted",
            "charging_required","battery_low","offline","wifi_required","scheduled","online","publisher","downloading","checking_model","decoding","starting","transcribing","saving",
            "playing","paused","phone","car","headphones","resume_requested","resume_cancelled","dns","timeout","permission","auth_expired","quota","network_or_storage","invalid_data","internal")
        fun reason(error:Throwable):String=when(error) {
            is kotlinx.coroutines.CancellationException -> "cancelled"
            is java.net.UnknownHostException -> "dns"
            is java.net.SocketTimeoutException -> "timeout"
            is SecurityException -> "permission"
            is dev.distillpod.android.ReauthorizationRequired -> "auth_expired"
            is dev.distillpod.android.QuotaExceeded -> "quota"
            is java.io.IOException -> "network_or_storage"
            is IllegalArgumentException -> "invalid_data"
            else -> "internal"
        }
    }
}
