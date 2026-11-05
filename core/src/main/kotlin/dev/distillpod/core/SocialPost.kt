package dev.distillpod.core

import java.net.URI

/** A link explicitly selected for publication, never an enclosure/GUID fallback. */
fun postSourceUrl(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 }?.let {
    runCatching {
        val uri = URI(it)
        require(uri.scheme?.lowercase() in setOf("https", "http") && uri.host != null && uri.userInfo == null)
        require(uri.rawQuery == null) { "Use a public link without access tokens or query parameters" }
        val host = uri.host.lowercase()
        require(host != "localhost" && !host.endsWith(".local") && !host.contains(':') && !host.matches(Regex("[0-9.]+")))
        uri.toASCIIString()
    }.getOrNull()
}

fun sourceQuoteMatches(quote: String, excerpt: String): Boolean {
    fun normalized(text: String) = text.replace(Regex("\\s+"), " ").trim()
    return quote.isBlank() || normalized(excerpt).contains(normalized(quote))
}

fun postTimestamp(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
