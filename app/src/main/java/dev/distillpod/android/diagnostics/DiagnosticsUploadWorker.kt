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

class DiagnosticsUploadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val diagnostics=applicationContext.appGraph.diagnostics
        if(!diagnostics.enabled || !diagnostics.configured)return@withContext Result.success()
        val prefs=applicationContext.getSharedPreferences("diagnostics",Context.MODE_PRIVATE)
        val delayUntil=prefs.getLong("retry_after",0)
        if(System.currentTimeMillis()<delayUntil)return@withContext Result.retry()
        try {
            val dsn=URI(applicationContext.getString(dev.distillpod.android.R.string.diagnostics_dsn))
            require(dsn.scheme=="https" && dsn.host.endsWith(".sentry.io") && dsn.userInfo.matches(Regex("[a-fA-F0-9]+")))
            val project=dsn.path.substringAfterLast('/')
            require(project.matches(Regex("[0-9]+")))
            val endpoint="https://${dsn.host}${dsn.path.substringBeforeLast('/')}/api/$project/envelope/"
            val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).followRedirects(false).build()
            for(file in diagnostics.queue.files().take(100)) {
                if(isStopped || !diagnostics.enabled)return@withContext Result.success()
                val receipt=DiagnosticDelivery(client).send(endpoint,dsn.userInfo,file.readBytes())
                when(receipt.outcome) {
                    DiagnosticDelivery.Outcome.ACCEPTED -> {file.delete();diagnostics.uploaded()}
                    DiagnosticDelivery.Outcome.RETRY -> {diagnostics.status.value="Waiting to upload"}
                    DiagnosticDelivery.Outcome.REJECTED -> {file.delete();diagnostics.status.value="An invalid diagnostic was discarded"}
                }
                receipt.retrySeconds?.let {seconds ->
                    prefs.edit().putLong("retry_after",System.currentTimeMillis()+seconds*1000).apply()
                }
                if(receipt.outcome==DiagnosticDelivery.Outcome.RETRY || receipt.retrySeconds!=null)return@withContext Result.retry()
            }
            if(diagnostics.queue.files().isEmpty())Result.success() else Result.retry()
        }catch(_:Exception) { diagnostics.status.value="Waiting to upload";Result.retry() }
    }
}
