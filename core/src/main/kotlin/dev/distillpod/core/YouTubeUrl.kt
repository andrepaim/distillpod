package dev.distillpod.core

import java.net.URI
import java.net.URLDecoder

/** Store public identities, never temporary CDN URLs, in backups and synced records. */
object YouTubeUrl {
    private val video = Regex("[A-Za-z0-9_-]{11}")
    private val playlist = Regex("[A-Za-z0-9_-]{10,100}")
    private fun parts(raw: String): Pair<URI, Map<String, String>>? = runCatching {
        val uri = URI(raw.trim())
        require(uri.scheme in setOf("http", "https") && uri.userInfo == null && uri.port == -1)
        require(uri.host?.lowercase() in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"))
        uri to uri.rawQuery.orEmpty().split('&').mapNotNull {
            val p = it.split('=', limit = 2)
            if (p.size == 2) URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8") else null
        }.toMap()
    }.getOrNull()
    fun videoId(raw: String): String? {
        val (u, q) = parts(raw) ?: return null
        val id = when {
            u.host.equals("youtu.be", ignoreCase = true) -> u.path.removePrefix("/")
            u.path == "/watch" -> q["v"]
            u.path.matches(Regex("/(shorts|live)/[A-Za-z0-9_-]{11}")) -> u.path.substringAfterLast('/')
            else -> null
        }
        return id?.takeIf { video.matches(it) }
    }
    fun playlistId(raw: String): String? {
        val (u, q) = parts(raw) ?: return null
        return q["list"]?.takeIf { !u.host.equals("youtu.be", ignoreCase = true) && u.path in setOf("/playlist", "/watch") && playlist.matches(it) }
    }
    fun watch(id: String): String { require(video.matches(id)); return "https://www.youtube.com/watch?v=$id" }
    fun list(id: String): String { require(playlist.matches(id)); return "https://www.youtube.com/playlist?list=$id" }
    fun channel(raw: String): String? {
        val (u, _) = parts(raw) ?: return null
        if (u.host.equals("youtu.be", true)) return null
        val match = Regex("/((?:channel/UC[A-Za-z0-9_-]{22})|(?:@[A-Za-z0-9_.-]{1,100}))/?(?:(videos|featured|shorts|streams)/?)?").matchEntire(u.path) ?: return null
        return "https://www.youtube.com/${match.groupValues[1]}/videos"
    }
    fun source(raw: String): String? = playlistId(raw)?.let(::list) ?: videoId(raw)?.let(::watch) ?: channel(raw)
}
