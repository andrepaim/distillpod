package dev.distillpod.core.sync

import java.net.URI

/**
 * The episode media address a portable record carries.
 *
 * A query string or fragment on an enclosure often holds a signed-URL signature, an access token or
 * per-listener tracking, so it never leaves the phone: the record carries the address without them.
 * Records identify an episode by its feed and GUID; this address only seeds an episode on a phone
 * that does not have it yet, and the next refresh of that feed restores the full enclosure.
 * The result has the form the protocol and the deployed Firestore rules already accept, so older
 * clients read it unchanged. Credential-bearing (`user:password@`) and non-HTTP addresses stay local,
 * as do YouTube addresses, whose video is named by the query.
 */
object MediaLocator {
    fun portable(raw: String): String? {
        val base = raw.substringBefore('#').substringBefore('?')
        if (base.toByteArray(Charsets.UTF_8).size > 2048) return null
        val uri = try { URI(base) } catch (_: Exception) { return null }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        val host = uri.host.lowercase()
        if (base != raw && (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com"))) return null
        return base
    }

    /**
     * As [portable], for an episode whose GUID is uploaded too. A feed without a GUID uses the enclosure
     * itself and some feeds repeat it there, so when the address carries a query and the GUID repeats it,
     * or is itself a query-bearing URL, the episode stays local rather than uploading that query.
     */
    fun portable(audioUrl: String, guid: String): String? {
        val base = portable(audioUrl) ?: return null
        if (base == audioUrl) return base
        val removed = audioUrl.substring(base.length)
        val guidIsQueryUrl = Regex("(?is)https?://.*[?#].*").matches(guid)
        return base.takeUnless { removed in guid || guidIsQueryUrl }
    }
}
