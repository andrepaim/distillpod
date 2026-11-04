package dev.distillpod.core.sync

enum class OperationStage { TRANSCRIPTION, PLAYBACK, CAPTURE, SEARCH, SYNC }
enum class OperationOutcome { STARTED, CHECKPOINT, PAUSED, RESUMED, COMPLETED, FAILED, INTERRUPTED }
enum class FailureReason { NONE, NETWORK, AUTH, QUOTA, PERMISSION, STORAGE, INVALID_DATA, PROCESS_INTERRUPTED, INTERNAL }
data class OperationEvent(val stage: OperationStage, val outcome: OperationOutcome, val reason: FailureReason = FailureReason.NONE, val elapsedMs: Long? = null)
interface TelemetrySink { fun usage(event: OperationEvent); fun diagnostic(event: OperationEvent) }
class TelemetryGate(private val sink: TelemetrySink) {
    private var usageAllowed = false
    private var diagnosticsAllowed = false
    @Synchronized fun consent(usage: Boolean, diagnostics: Boolean) {
        usageAllowed = usage; diagnosticsAllowed = diagnostics
    }
    @Synchronized fun event(event: OperationEvent) {
        if (event.elapsedMs != null && event.elapsedMs < 0) return
        if (usageAllowed) try { sink.usage(event) } catch (_: Exception) { /* Collection must not fail an operation. */ }
        if (diagnosticsAllowed) try { sink.diagnostic(event) } catch (_: Exception) { /* No recursive telemetry. */ }
    }
}
