package dev.distillpod.android

import dev.distillpod.android.playback.ConfirmationAudio
import dev.distillpod.android.playback.DistillConfirmationSound
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class DistillConfirmationSoundTest {
    private class Effect:ConfirmationAudio {
        var plays=0
        lateinit var ready:(Boolean)->Unit
        override fun prepare(onReady:(Boolean)->Unit) {ready=onReady}
        override fun play() {plays++}
    }
    private fun fixture(block:(Effect,MutableStateFlow<Boolean>,DistillConfirmationSound,(Long)->Unit)->Unit) {
        val effect=Effect();val enabled=MutableStateFlow(true);var now=0L
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {block(effect,enabled,DistillConfirmationSound(scope,enabled,effect,{now}),{now=it})}
        finally {scope.cancel()}
    }
    @Test fun `first save waits for the sample and then plays once`()=fixture {effect,_,sound,_->
        sound.saved();assertEquals(0,effect.plays)
        effect.ready(true);assertEquals(1,effect.plays)
        sound.saved();assertEquals(2,effect.plays)
    }
    @Test fun `muting takes effect even while the sample loads`()=fixture {effect,enabled,sound,_->
        sound.saved();enabled.value=false;effect.ready(true)
        assertEquals(0,effect.plays)
        sound.saved();assertEquals(0,effect.plays)
        enabled.value=true;sound.saved();assertEquals(1,effect.plays)
    }
    @Test fun `late loading and failed loading never produce delayed feedback`()=fixture {effect,_,sound,time->
        sound.saved();time(2000);effect.ready(true);assertEquals(0,effect.plays)
        effect.ready(false);sound.saved();assertEquals(0,effect.plays)
    }
}
