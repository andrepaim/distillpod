package dev.distillpod.android.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCoordinatorTest {
    private class Input : VoiceInput {
        var closed = false
        override fun start() {}
        override fun read(): ByteArray? = null
        override fun close() { closed = true }
    }
    @Test fun `duplicate taps create one session and resume waits for output drain`() = runTest {
        var pauses = 0; var resumed: Boolean? = null; var closed = false
        val drain = CompletableDeferred<Unit>(); val input = Input()
        val coordinator = VoiceCoordinator(this, {
            pauses++
            object : VoiceLease {
                override val episode = "ep"; override val position = 1234L
                override suspend fun finish(resume: Boolean) { resumed = resume }
            }
        }, { "context" }, {
            object : VoiceConnection {
                override suspend fun answer(input: VoiceInput, output: VoiceOutput, phase: (String) -> Unit, text: (String) -> Unit) {
                    phase("Answering"); text("answer"); output.drain()
                }
                override fun close() { closed = true }
            }
        }, { object : VoiceOutput {
            override suspend fun play(pcm: ByteArray) {}
            override suspend fun drain() { drain.await() }
            override fun close() {}
        } })
        coordinator.start({ input }); coordinator.start({ Input() }); runCurrent()
        assertEquals(1, pauses); assertNull(resumed)
        drain.complete(Unit); runCurrent()
        assertEquals(true, resumed); assertTrue(closed); assertTrue(input.closed)
        assertEquals("Idle", coordinator.state.value.phase)
    }
    @Test fun `external interruption cancels output and prevents resume`() = runTest {
        var changed: () -> Unit = {}; var resumed: Boolean? = null; var closed = false
        val coordinator = VoiceCoordinator(this, { callback ->
            changed = callback
            object : VoiceLease {
                override val episode = "ep"; override val position = 0L
                override suspend fun finish(resume: Boolean) { resumed = resume }
            }
        }, { "source" }, {
            object : VoiceConnection {
                override suspend fun answer(input: VoiceInput, output: VoiceOutput, phase: (String) -> Unit, text: (String) -> Unit) { awaitCancellation() }
                override fun close() { closed = true }
            }
        }, { object : VoiceOutput {
            override suspend fun play(pcm: ByteArray) {}
            override suspend fun drain() {}
            override fun close() {}
        } })
        coordinator.start({ Input() }); runCurrent(); changed(); runCurrent()
        assertEquals(false, resumed); assertTrue(closed); assertFalse(coordinator.state.value.active)
    }
    @Test fun `synchronous startup failure allows another tap`() = runTest {
        var attempts = 0
        val coordinator = VoiceCoordinator(CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            { attempts++; error("No episode") }, { "" }, { error("Unused") }, { error("Unused") })
        coordinator.start({ Input() }); coordinator.start({ Input() })
        assertEquals(2, attempts); assertFalse(coordinator.state.value.active)
    }
    @Test fun `missing context resumes without opening a paid connection`() = runTest {
        var resumed = false
        val coordinator = VoiceCoordinator(this, { object : VoiceLease {
            override val episode = "ep"; override val position = 0L
            override suspend fun finish(resume: Boolean) { resumed = resume }
        } }, { error("No transcript") }, { error("Must not connect") }, { error("Must not open audio") })
        coordinator.start({ Input() }); runCurrent()
        assertTrue(resumed); assertNotNull(coordinator.state.value.error)
    }
}
