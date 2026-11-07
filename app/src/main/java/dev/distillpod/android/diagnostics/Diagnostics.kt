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
    internal val queue=DiagnosticQueue(java.io.File(context.noBackupFilesDir,"diagnostics"))
    private val lock=Any()
    @Volatile private var generation=0L
    private val session=UUID.randomUUID().toString()
    private val installation=prefs.getString("installation",null) ?: UUID.randomUUID().toString().also {prefs.edit().putString("installation",it).apply()}
    private val executor=java.util.concurrent.ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(128),java.util.concurrent.ThreadFactory { runnable -> Thread(runnable,"distillpod-diagnostics").apply {isDaemon=true} },java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())
    val status=MutableStateFlow("Disabled")
    val enabled get()=prefs.getBoolean("enabled",false)
    val configured get()=dsn.isNotBlank()
    val reference get()="${context.packageManager.getPackageInfo(context.packageName,0).versionName} / installation $installation / session $session"
    init { if(enabled && configured) safeInitialize() }
    fun consent(allow:Boolean) = synchronized(lock) {
        generation++
        prefs.edit().putBoolean("enabled",allow).commit()
        Sentry.close()
        if(allow && configured) safeInitialize() else {
            WorkManager.getInstance(context).cancelUniqueWork("diagnostics-upload")
            WorkManager.getInstance(context).cancelUniqueWork("diagnostics-recovery")
            queue.clear(); java.io.File(context.noBackupFilesDir,"sentry").deleteRecursively()
            status.value=if(allow) "Cloud reporting is not configured in this build" else "Disabled"
        }
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
            options.logs.setBeforeSend { log -> if(log.body in EVENT_NAMES && enabled && epoch==generation) log else null }
            options.logs.setLoggerBatchProcessorFactory { opts,_ -> object:ILoggerBatchProcessor {
                override fun add(event:SentryLogEvent) {
                    val envelope=SentryEnvelope(SentryId(),opts.sdkVersion,SentryEnvelopeItem.fromLogs(opts.serializer,SentryLogEvents(listOf(event))))
                    persist(envelope,opts,epoch)
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
        event("app.started")
        schedule()
    }
    private fun persist(envelope:SentryEnvelope,options:SentryOptions,epoch:Long) {
        try { synchronized(lock) {
            if(!enabled || generation!=epoch)return
            val day=System.currentTimeMillis()/86_400_000L
            if(prefs.getLong("budget_day",-1)!=day)prefs.edit().putLong("budget_day",day).putInt("sent",0).putInt("errors",0).apply()
            val errorEnvelope=envelope.items.any {it.header.type==SentryItemType.Event}
            val count=prefs.getInt("sent",0);val errors=prefs.getInt("errors",0)
            if(!errorEnvelope && count>=1000 || errorEnvelope && errors>=30)return
            prefs.edit().putInt("sent",count+1).putInt("errors",errors+if(errorEnvelope)1 else 0).apply()
            val allowed=envelope.items.filter {it.header.type in setOf(SentryItemType.Event,SentryItemType.Log)}
            if(allowed.isEmpty())return
            val safeEnvelope=SentryEnvelope(envelope.header,allowed)
            val bytes=ByteArrayOutputStream().also {options.serializer.serialize(safeEnvelope,it)}.toByteArray()
            queue.append(bytes);status.value="Pending upload"
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
    fun event(name:String, operation:String?=null, reason:String="none", source:String="background", elapsedMs:Long?=null, related:String?=null, attempt:String?=null) {
        if(!enabled || !configured)return
        val epoch=generation
        executor.execute {if(enabled && generation==epoch) eventNow(name,operation,reason,source,elapsedMs,related,attempt)}
    }
    internal fun awaitIdle() {executor.submit {}.get(5,TimeUnit.SECONDS)}
    private fun eventNow(name:String, operation:String?, reason:String, source:String, elapsedMs:Long?,related:String?,attempt:String?) {
        if(!enabled || !configured)return
        try {
            // Categorical fields only. IDs must be random UUIDs, never hashes of content or URLs.
            if(name !in EVENT_NAMES)return
            if(reason !in REASONS || source !in setOf("background","phone","headphones","car"))return
            val attributes=mutableMapOf<String,Any>("event_id" to UUID.randomUUID().toString(),"session_id" to session,"installation_id" to installation,"reason" to reason,"source" to source,"android_api" to Build.VERSION.SDK_INT)
            operation?.takeIf {runCatching {UUID.fromString(it)}.isSuccess}?.let {attributes["operation_id"]=it}
            related?.takeIf {runCatching {UUID.fromString(it)}.isSuccess}?.let {attributes["transcript_id"]=it}
            attempt?.takeIf {runCatching {UUID.fromString(it)}.isSuccess}?.let {attributes["attempt_id"]=it}
            elapsedMs?.takeIf {it>=0}?.let {attributes["elapsed_ms"]=it}
            val network=context.getSystemService(ConnectivityManager::class.java)
            attributes["network"]=if(network.activeNetwork==null) "offline" else if(network.isActiveNetworkMetered) "metered" else "unmetered"
            Sentry.logger().log(SentryLogLevel.INFO,SentryLogParameters.create(SentryAttributes.fromMap(attributes)),name)
        }catch(_:Exception) {}
    }
    /** Intentional handled failure through the same path used by real operations. */
    fun test() {
        event("diagnostics.test",source="phone")
        try {throw IllegalStateException("Synthetic diagnostics verification")}catch(error:Exception) {failure("diagnostics",error)}
    }
    fun failure(stage:String,error:Throwable,operation:String?=null) {
        event("$stage.failed",operation,reason(error))
        if(!enabled || !configured || error is kotlinx.coroutines.CancellationException)return
        try {
            // Copy stack frames, never message/cause/suppressed payloads from the original throwable.
            val safe=RuntimeException("Diagnostic failure: ${reason(error)}").also {it.stackTrace=error.stackTrace}
            Sentry.captureException(safe) { scope ->
                scope.setTag("stage",stage)
                scope.setTag("reason",reason(error))
                scope.setTag("exception_type",error.javaClass.name.take(160))
                operation?.takeIf {runCatching {UUID.fromString(it)}.isSuccess}?.let {scope.setTag("operation_id",it)}
            }
        }catch(_:Exception) {}
    }
    internal fun uploaded() { status.value="Last upload: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}" }
    companion object {
        private val EVENT_NAMES=setOf("app.started","diagnostics.test","diagnostics.failed","headphone.received","headphone.rejected","headphone.previous","headphone.next",
            "capture.requested","capture.accepted","capture.saved","capture.waiting","capture.transcript_link","capture.prepared","capture.failed",
            "transcription.dependency","transcription.started","transcription.stage","transcription.queued","transcription.completed","transcription.failed",
            "distillation.started","distillation.finished","distillation.failed","playback.service_started","playback.service_stopped","playback.state","playback.failed",
            "voice.started","voice.finished","voice.failed","sync.finished","sync.failed","worker.failed")
        private val REASONS=setOf("none","created","deduplicated","transcript_required","waiting","waiting_auth","waiting_quota","waiting_transcript","queued","unknown",
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
