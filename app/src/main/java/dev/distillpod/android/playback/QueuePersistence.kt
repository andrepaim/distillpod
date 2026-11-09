package dev.distillpod.android.playback

internal data class QueuePersistenceDecision(val write:Boolean,val baseline:List<String>)

/** Restores/imports observe an already persisted queue; only a new local order may publish it. */
internal fun queuePersistenceDecision(previous:List<String>?,known:List<String>,persisted:List<String>,imported:Boolean,boundary:Boolean,restored:Boolean=false):QueuePersistenceDecision {
    val orderChanged=boundary && known!=previous
    val write=!imported && !restored && orderChanged && known!=persisted
    val next=if(imported || restored || previous==null || orderChanged)known else previous
    return QueuePersistenceDecision(write,next)
}
