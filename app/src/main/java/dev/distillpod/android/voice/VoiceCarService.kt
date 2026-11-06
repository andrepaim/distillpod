package dev.distillpod.android.voice

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.car.app.*
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.model.*
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.*
import dev.distillpod.android.R
import dev.distillpod.android.appGraph
import kotlinx.coroutines.launch

class VoiceCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR else HostValidator.Builder(this).build()
    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = VoiceCarScreen(carContext)
    }
}
class VoiceCarScreen(context: CarContext) : Screen(context) {
    private val voice = context.appGraph.voice
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) { lifecycleScope.launch { voice.state.collect { invalidate() } } }
            override fun onStop(owner: LifecycleOwner) { voice.cancel(false) }
        })
    }
    override fun onGetTemplate(): Template {
        val state = voice.state.value
        val keyReady = carContext.appGraph.voiceApiKey.hasKey()
        val pane = Pane.Builder().addRow(Row.Builder().setTitle(if (state.active) state.phase else "Ask about this")
            .addText(state.error ?: if (keyReady) "One question, a short spoken answer, then your podcast resumes." else "Add your OpenAI API key on the phone player first. You can test the microphone here.").build())
        if (state.active) pane.addAction(Action.Builder().setTitle("Cancel and resume").setOnClickListener { voice.cancel(true) }.build())
        else {
            if (keyReady) pane.addAction(Action.Builder().setTitle("Ask").setOnClickListener { start(false) }.build())
            pane.addAction(Action.Builder().setTitle("Test microphone").setOnClickListener { start(true) }.build())
        }
        return PaneTemplate.Builder(pane.build()).setTitle("DistillPod voice preview").setHeaderAction(Action.APP_ICON).build()
    }
    private fun start(diagnostic: Boolean) {
        if (carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO), androidx.core.content.ContextCompat.getMainExecutor(carContext)) { granted, _ ->
                if (Manifest.permission.RECORD_AUDIO in granted) voice.start({ CarVoiceInput(carContext) }, diagnostic)
            }
        } else voice.start({ CarVoiceInput(carContext) }, diagnostic)
    }
}
@android.annotation.SuppressLint("MissingPermission")
class CarVoiceInput(context: CarContext) : VoiceInput {
    private val record = CarAudioRecord.create(context)
    private val resampler = PcmResampler()
    private var closed = false
    override fun start() { record.startRecording() }
    override fun read(): ByteArray? {
        val bytes = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
        val count = record.read(bytes, 0, bytes.size)
        return if (count < 0) null else resampler.convert(bytes.copyOf(count))
    }
    override fun close() { if (!closed) { closed = true; record.stopRecording() } }
}
