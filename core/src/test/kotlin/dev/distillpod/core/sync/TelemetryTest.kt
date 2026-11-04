package dev.distillpod.core.sync
import org.junit.Assert.*
import org.junit.Test

class TelemetryTest {
    private class Sink : TelemetrySink {
        val usage=mutableListOf<OperationEvent>();val diagnostic=mutableListOf<OperationEvent>()
        override fun usage(event: OperationEvent) { usage.add(event) }
        override fun diagnostic(event: OperationEvent) { diagnostic.add(event) }
    }
    private val failure=OperationEvent(OperationStage.TRANSCRIPTION,OperationOutcome.FAILED,FailureReason.STORAGE)
    @Test fun `fresh install has no collection`() { val sink=Sink();TelemetryGate(sink).event(failure);assertTrue(sink.usage.isEmpty());assertTrue(sink.diagnostic.isEmpty()) }
    @Test fun `usage and diagnostics are independent`() {
        val sink=Sink();val gate=TelemetryGate(sink);gate.consent(true,false);gate.event(failure)
        assertEquals(listOf(failure),sink.usage);assertTrue(sink.diagnostic.isEmpty())
        gate.consent(false,true);gate.event(failure);assertEquals(listOf(failure),sink.diagnostic);assertEquals(1,sink.usage.size)
    }
    @Test fun `revocation immediately stops events`() { val sink=Sink();val gate=TelemetryGate(sink);gate.consent(true,true);gate.consent(false,false);gate.event(failure);assertTrue(sink.usage.isEmpty());assertTrue(sink.diagnostic.isEmpty()) }
    @Test fun `telemetry exception cannot fail user operation`() { val gate=TelemetryGate(object: TelemetrySink {
        override fun usage(event:OperationEvent) { error("provider unavailable") };override fun diagnostic(event:OperationEvent) { error("provider unavailable") }
    });gate.consent(true,true);gate.event(failure) }
    @Test fun `invalid elapsed duration is never sent`() {val sink=Sink();val gate=TelemetryGate(sink);gate.consent(true,true);gate.event(failure.copy(elapsedMs=-1));assertTrue(sink.usage.isEmpty());assertTrue(sink.diagnostic.isEmpty())}
}
