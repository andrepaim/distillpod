package dev.distillpod.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.ProductApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels { AppViewModel.factory(applicationContext.appGraph) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (intent.getBooleanExtra("openPlayer", false)) model.open("player")
        mediaSearch(intent)
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
        lifecycleScope.launch {
            applicationContext.appGraph.ready.await()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) applicationContext.appGraph.transcription.resumeQueued()
        }
    }
    override fun onPause() { applicationContext.appGraph.account.foreground(false); super.onPause() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); mediaSearch(intent); if (intent.getBooleanExtra("openPlayer", false)) model.open("player") }
    private fun mediaSearch(request:Intent) {
        if(request.action!="android.media.action.MEDIA_PLAY_FROM_SEARCH")return
        lifecycleScope.launch {
            val graph=applicationContext.appGraph
            graph.ready.await()
            val id=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                dev.distillpod.android.auto.AutoPlayback(graph.db).voice(request.getStringExtra(android.app.SearchManager.QUERY) ?: request.getStringExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE) ?: request.getStringExtra(android.provider.MediaStore.EXTRA_MEDIA_ARTIST).orEmpty())
            }
            if(id!=null)runCatching {graph.playback.play(id)}
        }
    }

}
