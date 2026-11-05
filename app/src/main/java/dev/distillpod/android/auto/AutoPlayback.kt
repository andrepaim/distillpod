package dev.distillpod.android.auto

import androidx.media3.common.MediaItem
import dev.distillpod.android.data.*
import dev.distillpod.android.playback.mediaItem
import java.text.Normalizer
import java.util.Locale

/** Shared queue policy; catalog queries and request matching stay off the player thread. */
class AutoPlayback(private val db:AppDatabase) {
    data class Selection(val items:List<MediaItem>,val index:Int,val position:Long)
    companion object {
        fun selectionIds(id:String,queue:List<String>)=if(id in queue)queue.distinct() else listOf(id)+queue.distinct()
        fun folded(s:String)=Normalizer.normalize(s,Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"").lowercase(Locale.ROOT).trim()
    }
    private val dao=db.library()
    suspend fun select(id:String,queue:List<String>,position:Long?=null):Selection {
        val ep=dao.episode(id)?:throw IllegalArgumentException("Episode is unavailable")
        val ids=selectionIds(id,queue).filter {dao.episode(it)!=null}
        val items=ids.map { val row=dao.episode(it)!!;mediaItem(row,dao.podcast(row.podcastId)) }
        val at=(position?:if(ep.completed)0 else ep.positionMs).coerceAtLeast(0).let {if(ep.durationMs>0)it.coerceAtMost(ep.durationMs) else it}
        return Selection(items,ids.indexOf(id),at)
    }
    private data class Match(val ep:EpisodeEntity,val score:Int)
    private suspend fun matches(query:String,maximum:Int):List<EpisodeEntity> {
        val q=folded(query);if(q.isBlank())return emptyList()
        val shows=dao.podcasts().filter {it.subscribed}.associateBy {it.id}
        val best=mutableListOf<Match>();var offset=0L
        while(true) {
            val batch=dao.autoSearchCandidates(100,offset)
            for(ep in batch) {
                val title=folded(ep.title);val show=folded(shows[ep.podcastId]?.title.orEmpty())
                val score=when {title==q->0;show==q->1;title.contains(q)->2;show.contains(q)->3;else->continue}
                best+=Match(ep,score)
            }
            best.sortWith(compareBy<Match> {it.score}.thenByDescending {it.ep.publishedAt}.thenBy {it.ep.id})
            if(best.size>maximum)best.subList(maximum,best.size).clear()
            if(batch.size<100)break
            offset+=batch.size
            kotlinx.coroutines.yield()
        }
        return best.map {it.ep}
    }
    suspend fun search(query:String,page:Int,pageSize:Int):List<MediaItem> {
        if(page<0 || pageSize<=0)return emptyList()
        val offset=page.toLong()*pageSize
        if(offset>=100)return emptyList()
        return matches(query,100).drop(offset.toInt()).take(pageSize.coerceAtMost(100)).map {mediaItem(it,dao.podcast(it.podcastId))}
    }
    suspend fun voice(query:String):String? {
        if(query.isBlank()) {
            dao.playerSession()?.episodeId?.let {if(dao.episode(it)!=null)return it}
            dao.queue().firstOrNull {dao.episode(it.episodeId)!=null}?.let {return it.episodeId}
            return dao.autoNewestUnfinished()?.id
        }
        val candidates=matches(query,100)
        candidates.firstOrNull {folded(it.title)==folded(query)}?.let {return it.id}
        val show=dao.podcasts().firstOrNull {it.subscribed && folded(it.title)==folded(query)}
        if(show!=null) {
            val rows=dao.episodesForPodcast(show.id)
            return rows.filter {!it.completed && it.positionMs>0}.maxByOrNull {it.lastPlayedAt}?.id
                ?:rows.firstOrNull {!it.completed}?.id ?:rows.firstOrNull()?.id
        }
        return candidates.firstOrNull()?.id
    }
}
