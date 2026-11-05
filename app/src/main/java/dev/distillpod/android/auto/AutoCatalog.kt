package dev.distillpod.android.auto

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.distillpod.android.data.AppDatabase
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.playback.mediaItem
import java.io.File

/** Read-only projection of the existing library. Playback keeps canonical episode IDs. */
class AutoCatalog(private val db: AppDatabase,private val artwork: ((String?)->android.net.Uri)?=null) {
    companion object {
        const val ROOT="distillpod:auto:root"
        const val CONTINUE="distillpod:auto:continue"
        const val QUEUE="distillpod:auto:queue"
        const val DOWNLOADS="distillpod:auto:downloads"
        const val PODCASTS="distillpod:auto:podcasts"
        const val SHOW="distillpod:auto:show:"
        const val MAX_PAGE=100
        private const val MORE="distillpod:auto:more:"
    }
    private val dao=db.library()
    private fun folder(id: String,title: String,art: String?=null): MediaItem = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
            .setArtworkUri(artwork?.invoke(art) ?: art?.let(android.net.Uri::parse)).build()).build()
    private val roots get()=listOf(folder(CONTINUE,"Continue listening"),folder(QUEUE,"Queue"),folder(DOWNLOADS,"Downloads"),folder(PODCASTS,"Podcasts"))
    suspend fun item(id: String): MediaItem? = when {
        id==ROOT -> folder(ROOT,"DistillPod")
        id.startsWith(MORE) -> decodeMore(id)?.let { if(item(it.first)?.mediaMetadata?.isBrowsable==true)folder(id,"More episodes") else null }
        roots.any { it.mediaId==id } -> roots.first { it.mediaId==id }
        id.startsWith(SHOW) -> dao.podcast(id.removePrefix(SHOW))?.takeIf { it.subscribed }?.let { folder(id,it.title,it.imageUrl) }
        else -> dao.episode(id)?.let { playable(it) }
    }
    private suspend fun playable(ep: EpisodeEntity): MediaItem {
        val item=mediaItem(ep,dao.podcast(ep.podcastId))
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setIsBrowsable(false).setIsPlayable(true)
            .setArtworkUri(artwork?.invoke((ep.imageUrl?:dao.podcast(ep.podcastId)?.imageUrl)) ?: item.mediaMetadata.artworkUri).build()).build()
    }
    private fun decodeMore(id: String): Pair<String,Int>? = runCatching {
        val parts=id.removePrefix(MORE).split(":")
        require(parts.size==2)
        val parent=String(java.util.Base64.getUrlDecoder().decode(parts[0]),Charsets.UTF_8)
        val page=parts[1].toInt();require(page in 1..100000 && !parent.startsWith(MORE))
        parent to page
    }.getOrNull()
    /** Legacy browsers often request all children at once. Expose bounded continuation folders. */
    suspend fun legacyChildren(id: String): List<MediaItem> {
        val (parent,page)=if(id.startsWith(MORE))decodeMore(id)?:return emptyList() else id to 0
        val rows=children(parent,page,MAX_PAGE-1)
        if(rows.size<MAX_PAGE-1 || children(parent,page+1,MAX_PAGE-1).isEmpty())return rows
        val encoded=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(parent.toByteArray(Charsets.UTF_8))
        return rows+folder(MORE+encoded+":"+(page+1),"More episodes")
    }
    suspend fun children(parent: String,page: Int,pageSize: Int): List<MediaItem> {
        // Reject unreasonable requests instead of multiplying untrusted Ints or returning unbounded parcels.
        if(page<0 || pageSize<=0)return emptyList()
        if(parent.startsWith(MORE))return if(page==0)legacyChildren(parent).take(pageSize.coerceAtMost(MAX_PAGE)) else emptyList()
        val offset=page.toLong()*pageSize
        val limit=pageSize.coerceAtMost(MAX_PAGE)
        if(parent==ROOT)return roots.drop(offset.coerceAtMost(roots.size.toLong()).toInt()).take(limit)
        if(parent==PODCASTS)return dao.autoPodcasts(limit,offset).map { folder(SHOW+it.id,it.title,it.imageUrl) }
        val section=when(parent) { CONTINUE->"continue"; QUEUE->"queue"; DOWNLOADS->"downloads"; else->if(parent.startsWith(SHOW))"show" else return emptyList() }
        val show=parent.removePrefix(SHOW)
        if(section=="show" && dao.podcast(show)?.subscribed!=true)return emptyList()
        val current=dao.playerSession()?.episodeId
        if(section!="downloads")return dao.autoEpisodes(section,show,current,limit,offset).map { playable(it) }
        // File deletion can leave stale rows. Page across usable files, scanning SQL in bounded batches.
        var scan=0L;var valid=0L;val result=mutableListOf<MediaItem>()
        while(result.size<limit) {
            val batch=dao.autoEpisodes(section,show,current,MAX_PAGE,scan)
            if(batch.isEmpty())break
            for(ep in batch)if(ep.localFile?.let { File(it).isFile && File(it).length()>0 }==true) {
                if(valid++>=offset)result+=playable(ep)
                if(result.size==limit)break
            }
            scan+=batch.size
            if(batch.size<MAX_PAGE)break
        }
        return result
    }
}
