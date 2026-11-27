package dev.distillpod.android

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.MutableCreationExtras
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.ProductApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels(
        extrasProducer = {
            // Interpret launch requests explicitly and let the saved-state registry restore the task.
            MutableCreationExtras(defaultViewModelCreationExtras).apply { this[DEFAULT_ARGS_KEY] = Bundle.EMPTY }
        },
        factoryProducer = { AppViewModel.factory(applicationContext.appGraph) },
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        observeTranscriptionRecovery({ applicationContext.appGraph.ready.await() },
            { applicationContext.appGraph.transcription.resumeQueued() })
        if (savedInstanceState == null) openRequestedScreen(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                applicationContext.appGraph.account.browser.collect { url ->
                    if (url != null) {
                        applicationContext.appGraph.account.browserOpened()
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { applicationContext.appGraph.account.cancel() }
                    }
                }
            }
        }
        setContent { ProductApp(model) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val graph = applicationContext.appGraph
                combine(graph.settings.flow, graph.playback.state.map { it.episodeId }.distinctUntilChanged(),
                    graph.headphoneVoice.ready, graph.voiceApiKey.revisions) { prefs, episode, ready, _ ->
                    Triple(prefs.headphoneVoice && graph.voiceApiKey.hasKey(), episode != null, ready)
                }.distinctUntilChanged().collect { (enabled, hasEpisode, ready) ->
                    if (!enabled) dev.distillpod.android.voice.HeadphoneVoiceService.stop(this@MainActivity)
                    else if (hasEpisode && !ready) dev.distillpod.android.voice.HeadphoneVoiceService.arm(this@MainActivity)
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        applicationContext.appGraph.account.foreground(true)
        applicationContext.appGraph.syncAccount.kick()
    }
    override fun onPause() { applicationContext.appGraph.account.foreground(false); super.onPause() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequestedScreen(intent)
    }

    private fun openRequestedScreen(request: Intent) {
        if (request.getBooleanExtra("openPlayer", false)) model.open("player")
        if (request.getBooleanExtra(dev.distillpod.android.data.OPEN_ABOUT_EXTRA, false)) model.open("settings:about")
        if (request.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH") {
            val query = request.getStringExtra(SearchManager.QUERY)
                ?: request.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE)
                ?: request.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST).orEmpty()
            model.playFromMediaSearch(query)
        }
    }

}
