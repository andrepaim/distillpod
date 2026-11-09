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
    @Test fun `cancel during preflight never acquires playback lease`() = runTest {
        var checked = false
        val coordinator = VoiceCoordinator(this, { error("Must not pause") }, { error("Must not acquire context") },
            { error("Must not connect") }, { error("Must not open output") },
            preflight = { checked = true; awaitCancellation() })
        coordinator.start({ error("Must not open mic") }); runCurrent()
        assertTrue(checked)
        coordinator.cancel(true); runCurrent()
        assertFalse(coordinator.state.value.active); assertNull(coordinator.state.value.error)
    }
    @Test fun `unexpected preflight failure is reported separately from expected readiness`() = runTest {
        val failure = IllegalStateException("synthetic internal fault")
        val failures = mutableListOf<Throwable>()
        val coordinator = VoiceCoordinator(this, { error("Must not pause") }, { "source" },
            { error("Must not connect") }, { error("Must not open output") },
            diagnosticFailure = { failures += it }, preflight = { throw failure })
        coordinator.start({ error("Must not open mic") }); runCurrent()
        assertEquals(listOf(failure), failures)
        assertFalse(coordinator.state.value.error!!.contains(failure.message!!))
    }
    @Test fun `unready preflight never pauses or opens audio and retry succeeds`() = runTest {
        var ready = false; var pauses = 0; var finished = 0; var connected = 0
        val failures = mutableListOf<Throwable>(); val events = mutableListOf<Pair<String, String>>()
        val coordinator = VoiceCoordinator(this, {
            pauses++
            object : VoiceLease {
                override val episode = "ep"; override val position = 10000L
                override suspend fun finish(resume: Boolean) { assertTrue(resume); finished++ }
            }
        }, { "source" }, {
            connected++
            object : VoiceConnection {
                override suspend fun answer(input: VoiceInput, output: VoiceOutput, phase: (String) -> Unit, text: (String) -> Unit) {}
                override fun close() {}
            }
        }, { object : VoiceOutput {
            override suspend fun play(pcm: ByteArray) {}
            override suspend fun drain() {}
            override fun close() {}
        } }, diagnosticEvent = { name, reason -> events += name to reason }, diagnosticFailure = { failures += it },
            preflight = { if (!ready) throw VoiceContextUnavailable(VoiceReadiness.MISSING_TRANSCRIPT) })
        coordinator.start({ error("Must not open mic") }); runCurrent()
        assertEquals(0, pauses); assertEquals(0, connected); assertEquals(0, finished)
        assertEquals(VoiceReadiness.MISSING_TRANSCRIPT.feedback, coordinator.state.value.error)
        assertTrue(failures.isEmpty())
        assertTrue(events.contains("voice.failed" to "voice_missing_transcript"))
        ready = true; coordinator.start({ Input() }); runCurrent()
        assertEquals(1, pauses); assertEquals(1, connected); assertEquals(1, finished)
        assertNull(coordinator.state.value.error)
    }
    @Test fun `readiness race restores exact acquired lease once without rewind`() = runTest {
        var finishes = 0; var rewind: Boolean? = null
        val coordinator = VoiceCoordinator(this, { object : VoiceLease {
            override val episode = "ep"; override val position = 10000L
            override suspend fun finish(resume: Boolean) { error("Must use exact restoration") }
            override suspend fun finish(resume: Boolean, allowRewind: Boolean) {
                assertTrue(resume); finishes++; rewind = allowRewind
            }
        } }, { throw VoiceContextUnavailable(VoiceReadiness.COVERAGE_GAP) },
            { error("Must not connect") }, { error("Must not open output") },
            diagnosticFailure = { error("Expected readiness must not be reported as exception") }, preflight = {})
        coordinator.start({ error("Must not open mic") }); runCurrent()
        assertEquals(1, finishes); assertEquals(false, rewind)
        assertEquals(VoiceReadiness.COVERAGE_GAP.feedback, coordinator.state.value.error)
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
    @Test fun `text response followed by output failure remains a failed voice operation`() = runTest {
        val events=mutableListOf<Pair<String,String>>()
        var resumed=false
        val coordinator=VoiceCoordinator(this,{object:VoiceLease {
            override val episode="ep";override val position=0L
            override suspend fun finish(resume:Boolean){resumed=resume}
        }},{"source"},{object:VoiceConnection {
            override suspend fun answer(input:VoiceInput,output:VoiceOutput,phase:(String)->Unit,text:(String)->Unit) {
                input.start();phase("Answering");text("Synthetic answer");output.play(byteArrayOf(0,0))
            }
            override fun close(){}
        }},{object:VoiceOutput {
            override suspend fun play(pcm:ByteArray){error("Synthetic output failure")}
            override suspend fun drain(){}
            override fun close(){}
        }},diagnosticEvent={name,reason->events+=name to reason})
        coordinator.start({Input()});runCurrent()
        assertTrue(resumed);assertTrue(events.contains("voice.text" to "none"))
        assertFalse(events.any {it.first=="voice.audio"})
        assertTrue(events.contains("voice.finished" to "failed"))
        assertFalse(coordinator.state.value.active)
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
    @Test fun `phone and car screen cleanup cannot cancel a headphone session and route closes before resume`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = VoiceCoordinator(this, { object : VoiceLease {
            override val episode = "ep"; override val position = 0L
            override suspend fun finish(resume: Boolean) { events += "resume:$resume" }
        } }, { "source" }, { object : VoiceConnection {
            override suspend fun answer(input: VoiceInput, output: VoiceOutput, phase: (String) -> Unit, text: (String) -> Unit) { phase("Listening"); awaitCancellation() }
            override fun close() { events += "connection closed" }
        } }, { object : VoiceOutput {
            override suspend fun play(pcm: ByteArray) {}
            override suspend fun drain() {}
            override fun close() { events += "speaker closed" }
        } })
        coordinator.start({ Input() }, origin = VoiceOrigin.HEADPHONES, prepareAudio = { AutoCloseable { events += "route closed" } })
        runCurrent()
        coordinator.cancelFrom(VoiceOrigin.PHONE, false); coordinator.cancelFrom(VoiceOrigin.CAR, false); runCurrent()
        assertEquals("Listening", coordinator.state.value.phase)
        coordinator.cancelFrom(VoiceOrigin.HEADPHONES, true); runCurrent()
        assertEquals(listOf("connection closed", "speaker closed", "route closed", "resume:true"), events)
        assertNull(coordinator.state.value.origin)
    }
    @Test fun `missing headset fails before opening a provider connection and restores playback`() = runTest {
        var resumed = false
        val coordinator = VoiceCoordinator(this, { object : VoiceLease {
            override val episode = "ep"; override val position = 0L
            override suspend fun finish(resume: Boolean) { resumed = resume }
        } }, { "source" }, { error("Must not call provider") }, { error("Must not open speaker") })
        coordinator.start({ error("Must not open mic") }, origin = VoiceOrigin.HEADPHONES,
            prepareAudio = { error("No headset") })
        runCurrent()
        assertTrue(resumed); assertFalse(coordinator.state.value.active); assertNotNull(coordinator.state.value.error)
    }
    @Test fun `headset loss during capture leaves podcast paused`() = runTest {
        var resumed: Boolean? = null
        val coordinator = VoiceCoordinator(this, { object : VoiceLease {
            override val episode = "ep"; override val position = 0L
            override suspend fun finish(resume: Boolean) { resumed = resume }
        } }, { "source" }, { object : VoiceConnection {
            override suspend fun answer(input: VoiceInput, output: VoiceOutput, phase: (String) -> Unit, text: (String) -> Unit) {
                phase("Listening"); throw HeadsetDisconnected()
            }
            override fun close() {}
        } }, { object : VoiceOutput {
            override suspend fun play(pcm: ByteArray) {}
            override suspend fun drain() {}
            override fun close() {}
        } })
        coordinator.start({ Input() }, origin = VoiceOrigin.HEADPHONES); runCurrent()
        assertEquals(false, resumed)
        assertEquals("Headset disconnected. Reconnect it before asking again.", coordinator.state.value.error)
    }
}
