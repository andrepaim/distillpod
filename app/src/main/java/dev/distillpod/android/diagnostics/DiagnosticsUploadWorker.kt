package dev.distillpod.android.diagnostics

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.distillpod.android.appGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves both the one-time upload request and the periodic recovery request. It always returns
 * success (#300): WorkManager's retry backoff doubles on every retry up to five hours and is only
 * reset by a success, so a backlog or a failure is continued with a fresh request instead.
 */
class DiagnosticsUploadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val diagnostics=applicationContext.appGraph.diagnostics
        if(!diagnostics.collecting || !diagnostics.configured)return@withContext Result.success()
        diagnostics.flushSummaries()
        val endpoint=endpoint(applicationContext.getString(dev.distillpod.android.R.string.diagnostics_dsn))
        if(endpoint==null) {diagnostics.status.value="Diagnostics cannot upload with this build's configuration";return@withContext Result.success()}
        // The two requests can run at once; the second leaves the outbox to the first.
        if(!draining.compareAndSet(false,true))return@withContext Result.success()
        val next=try {
            val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).followRedirects(false).build()
            val delivery=DiagnosticDelivery(client)
            diagnostics.uploader {bytes->delivery.send(endpoint.url,endpoint.key,bytes)}.drain {!isStopped && diagnostics.collecting}
        } finally {draining.set(false)}
        diagnostics.uploadFinished(next)
        // A stopped run is rescheduled by WorkManager itself.
        if(next is DiagnosticUploader.Next.Continue && !isStopped && diagnostics.collecting)
            diagnostics.scheduleUpload(next.delayMs,continuation=Diagnostics.RECOVERY_TAG !in tags)
        Result.success()
    }
    internal data class Endpoint(val url:String,val key:String)
    internal companion object {
        private val draining=AtomicBoolean(false)
        fun endpoint(value:String):Endpoint? = runCatching {
            val dsn=URI(value)
            require(dsn.scheme=="https" && dsn.host.endsWith(".sentry.io") && dsn.userInfo.matches(Regex("[a-fA-F0-9]+")))
            val project=dsn.path.substringAfterLast('/')
            require(project.matches(Regex("[0-9]+")))
            Endpoint("https://${dsn.host}${dsn.path.substringBeforeLast('/')}/api/$project/envelope/",dsn.userInfo)
        }.getOrNull()
    }
}
