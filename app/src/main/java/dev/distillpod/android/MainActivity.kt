package dev.distillpod.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.distillpod.android.features.AppViewModel
import dev.distillpod.android.features.ProductApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels { AppViewModel.factory(applicationContext.appGraph) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.getBooleanExtra("openPlayer", false)) model.open("player")
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
    }
    override fun onResume() {
        super.onResume()
        applicationContext.appGraph.account.foreground(true)
        lifecycleScope.launch {
            applicationContext.appGraph.ready.await()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) applicationContext.appGraph.transcription.resumeQueued()
        }
    }
    override fun onPause() { applicationContext.appGraph.account.foreground(false); super.onPause() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); if (intent.getBooleanExtra("openPlayer", false)) model.open("player") }
}
