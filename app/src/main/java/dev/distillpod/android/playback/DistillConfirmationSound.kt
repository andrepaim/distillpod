package dev.distillpod.android.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import dev.distillpod.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal interface ConfirmationAudio {
    fun prepare(onReady:(Boolean)->Unit)
    fun play()
}

/** State is serialized on the application main scope, including native load callbacks. */
internal class DistillConfirmationSound(
    private val scope:CoroutineScope,
    private val enabled:Flow<Boolean>,
    private val audio:ConfirmationAudio,
    private val now:()->Long={SystemClock.elapsedRealtime()},
) {
    private var loaded:Boolean?=null
    private var pendingAt:Long?=null
    init {
        try {
            audio.prepare {success->
                safely {
                    loaded=success
                    val pending=pendingAt
                    pendingAt=null
                    if(success && pending!=null && now()-pending in 0..750 && enabled.first())audio.play()
                }
            }
        } catch(_:Exception) {loaded=false}
    }
    fun saved()=safely {
        if(!enabled.first() || loaded==false)return@safely
        if(loaded==true)audio.play() else pendingAt=now()
    }
    private fun safely(action:suspend ()->Unit)=scope.launch {
        try {action()}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){/* Feedback must never invalidate a committed moment. */}
    }
}

/** Mixes into the current media output without requesting focus or interrupting the podcast. */
internal class SoundPoolConfirmationAudio(context:Context):ConfirmationAudio {
    private val context=context.applicationContext
    private val pool=SoundPool.Builder().setMaxStreams(1)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private var sample=0
    override fun prepare(onReady:(Boolean)->Unit) {
        pool.setOnLoadCompleteListener {_,sampleId,status->sample=sampleId;onReady(status==0)}
        sample=pool.load(context,R.raw.distill_scribble,1)
        if(sample==0)onReady(false)
    }
    override fun play() {pool.play(sample,1f,1f,1,0,1f)}
}
