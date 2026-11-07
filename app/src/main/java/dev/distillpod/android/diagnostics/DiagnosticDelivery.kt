package dev.distillpod.android.diagnostics

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/** HTTP acknowledgement, rather than SDK capture, determines outbox removal. */
internal class DiagnosticDelivery(private val client:OkHttpClient) {
    enum class Outcome { ACCEPTED, RETRY, REJECTED }
    data class Receipt(val outcome:Outcome,val retrySeconds:Long?=null)
    fun send(endpoint:String,key:String,bytes:ByteArray):Receipt {
        val request=Request.Builder().url(endpoint)
            .header("X-Sentry-Auth","Sentry sentry_version=7,sentry_key=$key,sentry_client=distillpod/1")
            .post(bytes.toRequestBody("application/x-sentry-envelope".toMediaType())).build()
        return client.newCall(request).execute().use {response ->
            val pause=if(response.code==429 || response.header("X-Sentry-Rate-Limits")!=null)
                (response.header("Retry-After")?.toLongOrNull()
                    ?:response.header("X-Sentry-Rate-Limits")?.substringBefore(':')?.toDoubleOrNull()?.toLong() ?: 3600L).coerceIn(30,86400)
                else null
            Receipt(when {
                response.isSuccessful -> Outcome.ACCEPTED
                response.code==429 || response.code>=500 || response.code in listOf(401,403,408) -> Outcome.RETRY
                else -> Outcome.REJECTED
            },pause)
        }
    }
}
