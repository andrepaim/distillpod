package dev.distillpod.android.diagnostics

/**
 * Content-free classification of Android foreground-service failures.
 *
 * Android names the failing component only inside the exception message
 * (`ServiceRecord{… package/class …}`), which the diagnostics boundary redacts.
 * Only an allowlisted category crosses that boundary, never the message or class.
 */
internal object ForegroundServiceFailure {
    private val TYPES=setOf("ForegroundServiceDidNotStartInTimeException","ForegroundServiceDidNotStopInTimeException",
        "ForegroundServiceStartNotAllowedException")
    private const val APP="dev.distillpod.android"
    private val SERVICES=mapOf("$APP.PlaybackService" to "playback","$APP.work.TranscriptionService" to "transcription",
        "$APP.voice.HeadphoneVoiceService" to "headphone_voice","androidx.work.impl.foreground.SystemForegroundService" to "work_manager")
    private val COMPONENT=Regex("""([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)""")

    /** Accepts a Sentry exception type (`Outer$Inner`), a simple class name or a fully qualified one. */
    fun isForegroundServiceType(type:String?):Boolean =
        type?.substringAfterLast('$')?.substringAfterLast('.') in TYPES

    fun service(message:String?):String {
        val component=COMPONENT.find(message ?: return "unknown") ?: return "unknown"
        val (pkg,cls)=component.destructured
        // Android shortens a class inside the application package to ".Name".
        return SERVICES[if(cls.startsWith('.')) pkg+cls else cls] ?: "other"
    }
}
