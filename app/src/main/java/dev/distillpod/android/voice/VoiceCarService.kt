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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

class VoiceCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR else HostValidator.Builder(this).build()
    override fun onCreateSession(): Session = object : Session() {
        private var binding: AutoCloseable? = null
        init {
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    binding = carContext.appGraph.carVoiceEntry.attach {
                        carContext.startCarApp(Intent(CarVoiceEntry.ASK_ACTION)
                            .setComponent(android.content.ComponentName(carContext, VoiceCarService::class.java)))
                    }
                }
                override fun onDestroy(owner: LifecycleOwner) { binding?.close(); carContext.appGraph.voice.cancel(false) }
            })
        }
        override fun onCreateScreen(intent: Intent): Screen = CarPlaybackScreen(carContext, intent.action == CarVoiceEntry.ASK_ACTION)
        override fun onNewIntent(intent: Intent) {
            if (intent.action == CarVoiceEntry.ASK_ACTION) {
                val manager = carContext.getCarService(ScreenManager::class.java)
                if (manager.top !is VoiceCarScreen) manager.push(VoiceCarScreen(carContext, autoAsk = true))
            }
        }
    }
}

/** Register Media3's token before asking the host to render its native player. */
@androidx.annotation.OptIn(androidx.car.app.annotations.ExperimentalCarApi::class)
class CarPlaybackScreen(context: CarContext, private val initialAsk: Boolean = false) : Screen(context) {
    private var ready = false
    private var failure: String? = null
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                lifecycleScope.launch {
                    try {
                        val token = carContext.appGraph.playback.carPlaybackToken()
                        carContext.getCarService(androidx.car.app.media.MediaPlaybackManager::class.java).registerMediaPlaybackToken(token)
                        ready = true; invalidate()
                        if (initialAsk) screenManager.push(VoiceCarScreen(carContext, autoAsk = true))
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { failure = "The car player could not connect. Reopen DistillPod."; invalidate() }
                }
            }
        })
    }
    override fun onGetTemplate(): Template {
        if (!ready) return MessageTemplate.Builder(failure ?: "Connecting player…").setLoading(failure == null)
            .setTitle("DistillPod").setHeaderAction(Action.APP_ICON).build()
        return carPlaybackTemplate(carContext,
            { screenManager.push(VoiceCarScreen(carContext, autoAsk = true)) },
            { screenManager.push(CarVoiceLibraryScreen(carContext)) })
    }
}

@androidx.annotation.OptIn(androidx.car.app.annotations.ExperimentalCarApi::class)
internal fun carPlaybackTemplate(context: android.content.Context, onAsk: () -> Unit, onBrowse: () -> Unit): androidx.car.app.media.model.MediaPlaybackTemplate {
    val ask = Action.Builder().setTitle("Ask").setIcon(CarIcon.Builder(androidx.core.graphics.drawable.IconCompat.createWithResource(context, R.drawable.ic_media_ask)).build())
        .setOnClickListener(onAsk).build()
    val browse = Action.Builder().setTitle("Library").setIcon(CarIcon.Builder(androidx.core.graphics.drawable.IconCompat.createWithResource(context, android.R.drawable.ic_menu_agenda)).build())
        .setOnClickListener(onBrowse).build()
    return androidx.car.app.media.model.MediaPlaybackTemplate.Builder().setHeader(Header.Builder().setTitle("Now playing")
        .setStartHeaderAction(Action.APP_ICON).addEndHeaderAction(ask).addEndHeaderAction(browse).build()).build()
}

class CarVoiceLibraryScreen(context: CarContext) : Screen(context) {
    private var episodes: List<dev.distillpod.android.data.EpisodeEntity>? = null
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) { lifecycleScope.launch {
                episodes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { carContext.appGraph.db.library().episodes().take(50) }
                invalidate()
            } }
        })
    }
    override fun onGetTemplate(): Template {
        val builder = ListTemplate.Builder().setTitle("Library").setHeaderAction(Action.BACK)
        val rows = episodes ?: return builder.setLoading(true).build()
        val items = ItemList.Builder().setNoItemsMessage("Add podcasts on your phone")
        rows.forEach { episode -> items.addItem(Row.Builder().setTitle(episode.title.take(150)).setOnClickListener {
            lifecycleScope.launch {
                try { carContext.appGraph.playback.play(episode.id); screenManager.pop() }
                catch (_: Exception) { androidx.car.app.CarToast.makeText(carContext, "Episode unavailable", androidx.car.app.CarToast.LENGTH_SHORT).show() }
            }
        }.build()) }
        return builder.setSingleList(items.build()).build()
    }
}

class VoiceCarScreen(context: CarContext, private val autoAsk: Boolean = false) : Screen(context) {
    private val voice = context.appGraph.voice
    private var asked = false
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) { lifecycleScope.launch { voice.state.map { it.phase to it.error }.distinctUntilChanged().collect { invalidate() } } }
            override fun onStart(owner: LifecycleOwner) {
                if (autoAsk && !asked && carContext.appGraph.voiceApiKey.hasKey()) { asked = true; start(false) }
            }
            override fun onStop(owner: LifecycleOwner) { voice.cancel(false) }
        })
    }
    override fun onGetTemplate(): Template {
        val state = voice.state.value
        val keyReady = carContext.appGraph.voiceApiKey.hasKey()
        val pane = Pane.Builder().addRow(Row.Builder().setTitle(if (state.active) state.phase else "Ask about this")
            .addText(state.error ?: if (keyReady) "One question, a short spoken answer, then your podcast resumes." else "Add your OpenAI API key in Settings → Voice on the phone first. You can test the microphone here.").build())
        if (state.active) {
            if (state.phase == "Listening") pane.addAction(Action.Builder().setTitle("Done").setOnClickListener { voice.finishQuestion() }.build())
            pane.addAction(Action.Builder().setTitle("Cancel and resume").setOnClickListener { voice.cancel(true) }.build())
        }
        else {
            if (keyReady) pane.addAction(Action.Builder().setTitle("Ask").setOnClickListener { start(false) }.build())
            pane.addAction(Action.Builder().setTitle("Test microphone").setOnClickListener { start(true) }.build())
        }
        return PaneTemplate.Builder(pane.build()).setTitle("DistillPod voice preview").setHeaderAction(Action.BACK).build()
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
