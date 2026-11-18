package dev.distillpod.android.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.R
import dev.distillpod.android.sync.SubscriptionCloudAccess
import dev.distillpod.android.ai.AccountState
import dev.distillpod.android.ui.theme.Button
import dev.distillpod.android.ui.theme.DistillSpacing
import dev.distillpod.android.ui.theme.OutlinedButton
import dev.distillpod.android.ui.theme.TextButton
import dev.distillpod.android.ui.theme.extras
import dev.distillpod.core.FirstRunStatus
import dev.distillpod.core.SetupChecklist
import dev.distillpod.core.SetupModelStatus
import dev.distillpod.core.SetupMomentTarget
import dev.distillpod.core.setupMomentTarget
import dev.distillpod.core.setupPlaybackStarted
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val SetupGuideRoute = "settings:setup-guide"
internal const val ChatGptDataUse = "AI actions send selected text to OpenAI and use your plan allowance."

@Composable
internal fun OnboardingWelcome(vm: AppViewModel, account: AccountState) {
    val configured = remember(vm) { SubscriptionCloudAccess.configured(vm.graph.context) }
    OnboardingWelcomeContent(
        account = account,
        syncConfigured = configured,
        onConnect = { vm.open("settings:ai") },
        onFindPodcast = { vm.finishSetup("search") },
        onRecover = { vm.open(OnboardingRecoveryRoute) },
        onSkip = { vm.finishSetup("library") },
    )
}

@Composable
internal fun OnboardingWelcomeContent(
    account: AccountState,
    syncConfigured: Boolean,
    onConnect: () -> Unit,
    onFindPodcast: () -> Unit,
    onRecover: () -> Unit,
    onSkip: () -> Unit,
) {
    SetupPage {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.primary)
            Text("DistillPod", style = MaterialTheme.typography.titleMedium)
        }
        Text("Turn a podcast moment into a takeaway", Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineLarge)
        Text("Save the last 60 seconds. With ChatGPT, you can get a takeaway or ask follow-up questions about the episode.",
            style = MaterialTheme.typography.bodyLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.fillMaxWidth().padding(DistillSpacing.gutter),
                verticalArrangement = Arrangement.spacedBy(DistillSpacing.sm)) {
                Text("Example", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SetupHeading("From the last minute")
                SetupDetail("“Our Monday meetings used to end with five action items. Once we picked the task that unblocked everyone else, the other four got done faster.”")
                HorizontalDivider(Modifier.padding(vertical = DistillSpacing.xs))
                SetupHeading("ChatGPT takeaway")
                Text("Start with the task that unblocks the rest of the team.")
            }
        }
        val libraryProtection = if (syncConfigured) "Google library sync is separate." else "Backups work separately."
        SetupDetail("ChatGPT is optional. Play podcasts and prepare transcripts on your phone. $libraryProtection")
        Column(verticalArrangement = Arrangement.spacedBy(DistillSpacing.sm)) {
            val primaryAction = Modifier.fillMaxWidth().testTag("onboarding-primary-action")
            if (account.connected) {
                SetupConnected("ChatGPT connected")
                Button(onClick = onFindPodcast, modifier = primaryAction) { Text("Find my first podcast") }
            } else {
                SetupDetail(ChatGptDataUse)
                Button(onClick = onConnect, modifier = primaryAction) {
                    Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(DistillSpacing.sm))
                    Text("Connect ChatGPT")
                }
                OutlinedButton(onClick = onFindPodcast, modifier = Modifier.fillMaxWidth()) { Text("Find my first podcast") }
            }
            OutlinedButton(onClick = onRecover, modifier = Modifier.fillMaxWidth()) {
                Text("Bring back my library")
            }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Skip setup") }
        }
    }
}

@Composable
internal fun OnboardingRecovery(vm: AppViewModel) {
    val configured = remember(vm) { SubscriptionCloudAccess.configured(vm.graph.context) }
    val binding by remember(vm) { vm.graph.db.sync().observeBinding() }.collectAsStateWithLifecycle(initialValue = null)
    val backupConnected by vm.managedBackups.connected.collectAsStateWithLifecycle(initialValue = false)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val importOpml = rememberOpmlImport(vm)
    SetupPage {
        Text("Bring back my library", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
        Text("Restore your library before you start adding shows or listening on this phone.",
            style = MaterialTheme.typography.bodyLarge)
        SettingsGroupCard {
            SetupLink("Sync & backups", onClick = { vm.open("settings:sync") }, icon = Icons.Default.Sync,
                detail = if (configured) "Restore a backup file on its own, or connect your original Google account."
                else "Restore saved shows, listening progress, moments and other library text from a backup file.")
            SettingsDivider()
            SetupLink("Import OPML", onClick = importOpml, icon = Icons.Default.ImportExport, enabled = "opml" !in busy,
                detail = "Bring in subscriptions from another podcast app.")
        }
        if (configured) {
            if (binding != null) SetupConnected("Google account linked to this library")
            SetupDetail("Have both sync and a backup? Connect your original Google account first, then restore the backup when sync finishes.")
            SetupDetail(DataProtectionGuidance.binding)
        }
        if (backupConnected) SetupConnected("Automatic backup connected")
        SetupDetail("Backup files contain readable personal text and feed addresses. Keep them private.")
        Column(verticalArrangement = Arrangement.spacedBy(DistillSpacing.sm)) {
            Button(onClick = { vm.finishSetup("library") }, modifier = Modifier.fillMaxWidth()) { Text("Open library") }
            TextButton(onClick = { vm.finishSetup("library") }, modifier = Modifier.fillMaxWidth()) { Text("Skip setup") }
        }
    }
}

internal data class SetupGuideState(
    val checklist: SetupChecklist,
    val model: SetupModel,
    val modelChecking: Boolean,
    val listened: Boolean,
)

@Composable
internal fun setupGuideForLibrary(vm: AppViewModel): SetupGuideState? {
    val setup by vm.onboarding.collectAsStateWithLifecycle()
    val decision = setup ?: return null
    if (decision.status != FirstRunStatus.DONE || decision.cardDismissed) return null
    return setupGuideState(vm).takeIf {
        it.checklist.showInLibrary(setupDone = true, cardDismissed = false)
    }
}

@Composable
private fun setupGuideState(vm: AppViewModel): SetupGuideState {
    val podcasts by vm.podcasts.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val captures by vm.captures.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val preferences by vm.graph.preferences.collectAsStateWithLifecycle()
    val account by vm.graph.account.state.collectAsStateWithLifecycle()
    val binding by remember(vm) { vm.graph.db.sync().observeBinding() }.collectAsStateWithLifecycle(initialValue = null)
    val backupConnected by vm.managedBackups.connected.collectAsStateWithLifecycle(initialValue = false)
    val livePlaybackStarted by remember(vm) {
        vm.player.map { setupPlaybackStarted(it.playing, it.positionMs, hasSavedPosition = false) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(false)
    val savedProgress = remember(episodes) { episodes.any { it.positionMs > 0 } }
    val readyFiles = rememberReadySetupModelFiles(vm, models)
    val model = setupModel(preferences.model, models) { readyFiles?.contains(it.id) == true }
    val modelChecking = readyFiles == null && models.any { it.id == preferences.model && it.state == "ready" }
    val checklist = SetupChecklist(
        followedShow = remember(podcasts) { podcasts.any { it.subscribed } },
        modelStatus = model.status,
        chatGptConnected = account.connected,
        savedMoment = captures.isNotEmpty(),
        googleBinding = binding != null,
        backupConnected = backupConnected,
    )
    return SetupGuideState(checklist, model, modelChecking, livePlaybackStarted || savedProgress)
}

@Composable
internal fun LibrarySetupCard(vm: AppViewModel, state: SetupGuideState, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().testTag("library-setup-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(DistillSpacing.gutter), verticalArrangement = Arrangement.spacedBy(DistillSpacing.item)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DistillSpacing.sm)) {
                Text("Setup guide", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = vm::dismissSetupCard) { Text("Dismiss") }
            }
            SetupGuideActions(vm, state, contextual = true)
        }
    }
}

@Composable
internal fun SetupGuidePage(vm: AppViewModel) {
    val state = setupGuideState(vm)
    SetupPage { SetupGuideActions(vm, state, contextual = false) }
}

@Composable
private fun SetupGuideActions(vm: AppViewModel, state: SetupGuideState, contextual: Boolean) {
    val checklist = state.checklist
    val scope = rememberCoroutineScope()
    val openSearch: () -> Unit = { vm.open("search") }
    val openMoment: () -> Unit = {
        scope.launch {
            val loaded = vm.player.value.episodeId
            val available = if (loaded == null) withContext(Dispatchers.IO) {
                setupLibraryEpisode(vm.podcasts.value, vm.episodes.value) { File(it).isFile }
            } else null
            val destination = when (val target = setupMomentTarget(vm.player.value.episodeId, available)) {
                SetupMomentTarget.Player -> "player"
                is SetupMomentTarget.Episode -> "episode:${target.id}"
                SetupMomentTarget.Search -> "search"
            }
            vm.open(destination)
        }
    }
    if (contextual && !state.listened) {
        Text("Find an episode to play. Follow the show if you’d like new episodes in Library.")
        Button(onClick = if (checklist.followedShow) openMoment else openSearch, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Search, null)
            Spacer(Modifier.width(DistillSpacing.sm))
            Text(if (checklist.followedShow) "Play an episode" else "Find podcasts")
        }
    }
    if (state.listened) {
        TranscriptSetupPanel(vm, state)
        ChatGptSetupPanel(vm, checklist.chatGptConnected)
    }
    SettingsGroupCard {
        SetupLink("Follow a show", openSearch, status = "Followed".takeIf { checklist.followedShow })
        if (!state.listened) {
            SettingsDivider()
            SetupLink("On-device transcripts", { vm.open("settings:transcription") }, detail = setupModelLabel(state),
                status = "Ready".takeIf { state.model.status == SetupModelStatus.READY })
            SettingsDivider()
            SetupLink("ChatGPT takeaways (optional)", { vm.open("settings:ai") },
                detail = if (checklist.chatGptConnected) "Connected" else "Add a takeaway and ask questions about an episode.",
                status = "Connected".takeIf { checklist.chatGptConnected })
        }
        SettingsDivider()
        val momentDetail = when {
            checklist.savedMoment -> "Moment saved"
            checklist.takeawayReady -> "In the player, tap Distill last 60 seconds to save a moment and get a takeaway."
            else -> "In the player, tap Distill last 60 seconds to save a moment’s source."
        }
        SetupLink("Save your first moment", openMoment, detail = momentDetail, status = "Saved".takeIf { checklist.savedMoment })
        SettingsDivider()
        SetupLink("Sync & backups (optional)", { vm.open("settings:sync") }, status = "Connected".takeIf { checklist.syncConnected })
    }
    var showMore by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { showMore = true }) { Text("More to explore") }
    if (showMore) MoreToExplore(vm) { showMore = false }
}

@Composable
private fun TranscriptSetupPanel(vm: AppViewModel, state: SetupGuideState) {
    val model = state.model
    val spec = model.spec
    val name = spec?.let { modelName(it.id) }
    val action = when (model.status) {
        SetupModelStatus.MISSING -> if (name == null || state.modelChecking) "Transcript preparation" else "Download $name"
        SetupModelStatus.PAUSED, SetupModelStatus.FAILED -> "Resume $name download"
        SetupModelStatus.QUEUED, SetupModelStatus.DOWNLOADING -> "Manage $name download"
        SetupModelStatus.READY -> "Transcript preparation"
    }
    SetupPanel {
        SetupHeading("On-device transcripts")
        SetupDetail("Prepare transcripts on this phone. Read or search them as you listen.")
        if (model.status == SetupModelStatus.READY) SetupConnected(setupModelLabel(state))
        else SetupDetail(setupModelLabel(state))
        if (model.status == SetupModelStatus.DOWNLOADING && spec != null) {
            LinearProgressIndicator(progress = { (model.bytes.toFloat() / spec.bytes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth())
        }
        OutlinedButton(onClick = { vm.open("settings:transcription") }, modifier = Modifier.fillMaxWidth()) { Text(action) }
    }
}

private fun setupModelLabel(state: SetupGuideState): String {
    val spec = state.model.spec ?: return "Choose a transcription model"
    val status = if (state.modelChecking) "Checking download" else when (state.model.status) {
        SetupModelStatus.MISSING -> "Not downloaded"
        SetupModelStatus.QUEUED -> "Waiting to download"
        SetupModelStatus.DOWNLOADING -> "Downloading"
        SetupModelStatus.PAUSED -> "Download paused"
        SetupModelStatus.FAILED -> "Download interrupted"
        SetupModelStatus.READY -> "Ready"
    }
    return "${modelName(spec.id)} · ${fileSize(spec.bytes)} · $status"
}

@Composable
private fun ChatGptSetupPanel(vm: AppViewModel, connected: Boolean) {
    SetupPanel {
        SetupHeading("ChatGPT takeaways (optional)")
        if (connected) {
            SetupConnected("Connected")
            OutlinedButton(onClick = { vm.open("settings:ai") }, modifier = Modifier.fillMaxWidth()) { Text("ChatGPT settings") }
        } else {
            SetupDetail("Not connected")
            SetupDetail(ChatGptDataUse)
            Button(onClick = { vm.open("settings:ai") }, modifier = Modifier.fillMaxWidth()) { Text("Connect ChatGPT") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreToExplore(vm: AppViewModel, dismiss: () -> Unit) {
    val browser = LocalUriHandler.current
    fun openHelp(path: String) {
        try {
            browser.openUri("https://github.com/andrepaim/distillpod/blob/main/docs/$path")
        } catch (_: IllegalArgumentException) {
            dismiss()
            vm.messages.trySend("No app can open this help link.")
        }
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = DistillSpacing.gutter).padding(bottom = DistillSpacing.section),
            verticalArrangement = Arrangement.spacedBy(DistillSpacing.sm)) {
            SetupHeading("More to explore")
            SetupLink("Headphone capture", { dismiss(); vm.open("settings:playback") },
                detail = "Save a moment with your headphones.")
            SetupLink("Home-screen widget", {
                openHelp("getting-started.md#home-screen-widget")
            }, detail = "Add playback and moment controls to your home screen.")
            SetupLink("Android Auto", {
                openHelp("android/android-auto.md")
            }, detail = "Use DistillPod on a compatible car display.")
            if (booleanResource(R.bool.voice_enabled)) {
                SetupLink("Voice", { dismiss(); vm.open("settings:voice") }, detail = "Ask a question out loud.")
            }
        }
    }
}

@Composable
private fun SetupPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DistillSpacing.page),
        verticalArrangement = Arrangement.spacedBy(DistillSpacing.gutter), content = content)
}

@Composable
private fun SetupHeading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SetupDetail(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SetupConnected(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DistillSpacing.sm)) {
        Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.extras.success)
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.extras.success)
    }
}

@Composable
private fun SetupPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(DistillSpacing.item), verticalArrangement = Arrangement.spacedBy(DistillSpacing.sm), content = content)
    }
}

@Composable
internal fun SetupLink(
    title: String,
    onClick: () -> Unit,
    detail: String? = null,
    icon: ImageVector? = null,
    status: String? = null,
    enabled: Boolean = true,
) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(DistillSpacing.item), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DistillSpacing.item)) {
            if (icon != null) SettingsIcon(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DistillSpacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (detail != null) SetupDetail(detail)
            }
            if (status != null) Icon(Icons.Default.CheckCircle, status, tint = MaterialTheme.extras.success)
            else Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
