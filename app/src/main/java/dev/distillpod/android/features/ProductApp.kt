package dev.distillpod.android.features

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.distillpod.android.DiagnosticsActivity
import dev.distillpod.android.ai.AccountState
import dev.distillpod.android.data.*
import dev.distillpod.android.playback.PlaybackState
import dev.distillpod.android.ui.theme.extras
import dev.distillpod.android.ui.theme.DistillTheme

fun timestamp(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}
fun fileSize(bytes: Long): String = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1_000_000_000.0) else "%.0f MB".format(bytes / 1_000_000.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ProductApp(vm: AppViewModel) {
    val stack by vm.backStack.collectAsStateWithLifecycle()
    val route = stack.last()
    var videoFullscreen by remember { mutableStateOf(false) }
    var fullscreenExitRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(route) { if (route != "player") videoFullscreen = false }
    val podcasts by vm.podcasts.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val captures by vm.captures.collectAsStateWithLifecycle()
    val transcripts by vm.transcripts.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val preferences by vm.graph.preferences.collectAsStateWithLifecycle()
    val account by vm.graph.account.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val screenState = rememberSaveableStateHolder()
    LaunchedEffect(Unit) { for (message in vm.messages) snackbar.showSnackbar(message) }
    BackHandler(stack.size > 1 || videoFullscreen) {
        if (videoFullscreen) fullscreenExitRequest++ else vm.back()
    }
    val title = when {
        route == "library" -> "DistillPod"
        route == "search" -> "Discover"
        route == "saved" -> "Saved"
        route.startsWith("capture:") -> "Saved moment"
        route == "posts" || route.startsWith("posts:") -> "Your posts"
        route.startsWith("post:") -> "Create post"
        route == "queue" -> "Listening queue"
        route == "settings" -> "Settings"
        route == "shows" -> "Your shows"
        route.startsWith("settings:") -> when(route.substringAfter(':')) { "diagnostics" -> "Privacy & diagnostics"; "notifications" -> "Notification access"; "sync" -> "Library sync"; "ai" -> "ChatGPT"; "voice" -> "Voice"; "playback" -> "Playback & headphones"; "transcription" -> "Transcript preparation"; "storage" -> "Downloads & storage"; "library" -> "Library"; else -> "About DistillPod" }
        route.startsWith("transcript:") -> "Transcript"
        route == "backup" -> "Backup & Restore"
        route == "player" -> "Now playing"
        route.startsWith("analysis:") -> "Episode insights"
        route.startsWith("chat:") -> "Ask this episode"
        route.startsWith("research:") -> "Explore the idea"
        route == "recommendations" -> "Find new voices"
        route.startsWith("preparation:") -> "Show preparation"
        route.startsWith("podcast:") -> podcasts.firstOrNull { it.id == route.substringAfter(':') }?.title ?: "Podcast"
        else -> "Episode"
    }
    DistillTheme {
        BackupDocumentLaunchers(vm)
        NotificationPromptHost(vm.notificationPrompt, snackbar) { vm.open("settings:notifications") }
        Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            if (!videoFullscreen) TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { if (stack.size > 1) IconButton(onClick = vm::back) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    if (route == "library" || route.startsWith("podcast:")) IconButton(onClick = { vm.refresh(if (route.startsWith("podcast:")) route.substringAfter(':') else null) }, enabled = "refresh" !in busy) { Icon(Icons.Default.Refresh, "Refresh feeds") }
                    if (route == "player") IconButton(onClick = { vm.root("queue") }) { Icon(Icons.Default.QueueMusic, "Open queue") }
                    else if (!route.startsWith("settings")) IconButton(onClick = { vm.open("settings") }) { Icon(Icons.Default.Settings, "Settings") }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        }, bottomBar = {
            Column {
                if (player.episodeId != null && route != "player") {
                    val current = episodes.firstOrNull { it.id == player.episodeId }
                    MiniPlayer(player, current?.imageUrl ?: podcasts.firstOrNull { it.id == current?.podcastId }?.imageUrl, { vm.open("player") }, vm::toggle, { vm.root("queue") })
                }
                if (route != "player") NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    listOf(Triple("library", "Library", Icons.Default.LibraryMusic), Triple("search", "Search", Icons.Default.Search),
                        Triple("saved", "Saved", Icons.Default.Bookmark), Triple("queue", "Queue", Icons.Default.QueueMusic)).forEach { (key, label, icon) ->
                        NavigationBarItem(selected = stack.first() == key, onClick = { vm.root(key) }, icon = { Icon(icon, label) }, label = { Text(label) })
                    }
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().then(if (videoFullscreen) Modifier else Modifier.padding(padding).consumeWindowInsets(padding))
                .then(if (route == "player" && !videoFullscreen) Modifier.navigationBarsPadding() else Modifier)) {
                screenState.SaveableStateProvider(route) {
                when {
                    !loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    route == "library" || route.startsWith("podcast:") -> LibraryPage(vm, podcasts, transcripts, captures, player, route.takeIf { it.startsWith("podcast:") }?.substringAfter(':'))
                    route == "search" -> SearchPage(vm, podcasts)
                    route == "queue" -> QueuePage(vm, episodes, podcasts, player)
                    route == "posts" || route.startsWith("posts:") -> PostDraftsPage(vm, route.substringAfter(':', "").takeIf { it.isNotBlank() })
                    route.startsWith("post:") -> PostEditorPage(vm, route.substringAfter(':'))
                    route == "saved" -> SavedPage(vm, captures, episodes, podcasts)
                    route == "shows" -> ShowsPage(vm)
                    route.startsWith("preparation:") -> {
                        val parts = route.split(':', limit = 3)
                        podcasts.firstOrNull { it.id == parts.getOrNull(2) && it.subscribed }?.let {
                            ShowPreparationDetails(vm, it.id, parts[1] == "attention")
                        }
                    }
                    route == "settings" || route.startsWith("settings:") -> SettingsPage(vm, preferences, account, models, episodes, route.substringAfter(':', "overview"))
                    route == "backup" -> BackupPage(vm) { vm.open("settings:sync") }
                    route == "player" -> PlayerPage(vm, episodes.firstOrNull { it.id == player.episodeId }, podcasts, player, fullscreenExitRequest) { videoFullscreen = it }
                    route.startsWith("episode:") -> EpisodePage(vm, episodes.firstOrNull { it.id == route.substringAfter(':') }, podcasts, player)
                    route.startsWith("capture:") -> CapturePage(vm, captures.firstOrNull { it.id == route.substringAfter(':') }, episodes, podcasts)
                    route.startsWith("transcript:") -> transcriptTarget(route).let { target -> TranscriptPage(vm, episodes.firstOrNull { it.id == target.episodeId }, target.atMs) }
                    route.startsWith("analysis:") -> AnalysisPage(vm, episodes.firstOrNull { it.id == route.substringAfter(':') })
                    route.startsWith("chat:") -> EpisodeChatPage(vm, episodes.firstOrNull { it.id == route.substringAfter(':') })
                    route.startsWith("research:") -> ExplanationPage(vm, route.substringAfter(':'))
                    route == "recommendations" -> RecommendationsPage(vm)
                }
                }
            }
        }
    }
}

private val NOT_CURRENT = PlaybackState()
@Composable private fun Cover(url: String?, modifier: Modifier = Modifier) = PodcastCover(url, modifier)
@Composable private fun Empty(title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Default.Headphones, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun LibraryPage(vm: AppViewModel, podcasts: List<PodcastEntity>, transcripts: List<TranscriptEntity>, captures: List<DistillationEntity>, player: PlaybackState, podcastId: String?) {
    // Filtering 3,000+ episodes belongs to library changes, not to each live position tick (#141).
    val routed by vm.library.collectAsStateWithLifecycle()
    val refreshing by remember(vm) { vm.feedRefreshing() }.collectAsStateWithLifecycle(false)
    val deletionBlocks by vm.audioDeletionBlocks.collectAsStateWithLifecycle()
    val actions = remember(vm) { vm.episodeActions() }
    val fallback = remember(podcastId) { lazy { vm.currentLibraryListing(podcastId) } }
    val listing = routed?.takeIf { it.podcastId == podcastId } ?: fallback.value
    val filter = listing.filter
    val subscribed = listing.subscribed
    val selected = listing.selected
    val visible = listing.visible
    val resume = listing.resume
    val preparationRows = if (selected?.subscribed == true) showPreparationRows(vm, selected.id) else emptyList()
    val attentionIds = remember(preparationRows) { preparationRows.filter { it.retryable }.mapTo(mutableSetOf()) { it.episodeId } }
    var aboutExpanded by rememberSaveable(podcastId) { mutableStateOf(false) }
    val showResume = podcastId == null && filter == "all" && resume != null
    val list = rememberLazyListState()
    // Room/player flows can insert the resume card above the initial show row.
    // Start a newly selected collection at its actual first item, including that card.
    LaunchedEffect(podcastId, filter, showResume) { list.scrollToItem(0) }
    LazyColumn(state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (selected != null) item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(selected.imageUrl, Modifier.size(88.dp))
                Column(Modifier.weight(1f)) { Text(selected.author, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${listing.episodeCount} episodes", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { if (selected.subscribed) vm.unsubscribe(selected.id) else vm.subscribe(selected.feedUrl) }) { Text(if (selected.subscribed) "Unsubscribe" else "Subscribe") }
                }
                if (selected.subscribed) IconButton(onClick = { vm.open("preparation:all:${selected.id}") }) {
                    Icon(Icons.Default.Settings, "Show preparation settings")
                }
            }
            if (attentionIds.isNotEmpty()) TextButton(onClick = { vm.open("preparation:attention:${selected.id}") }) {
                Text(if (attentionIds.size == 1) "1 episode needs attention" else "${attentionIds.size} episodes need attention")
            }
            if (selected.description.isNotBlank()) {
                TextButton(onClick = { aboutExpanded = !aboutExpanded }) { Text(if (aboutExpanded) "Hide about this show" else "About this show") }
                if (aboutExpanded) Text(selected.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (podcastId == null && filter == "all" && resume != null) item(key = "resume") {
            val current = player.episodeId == resume.id
            ResumeListeningCard(resume, podcasts.firstOrNull { it.id == resume.podcastId },
                listeningPosition(resume, player), listeningDuration(resume, player),
                current && player.playing, { if (current) vm.open("player") else vm.open("episode:${resume.id}") },
                // A finished episode restarts from the beginning; toggling an ended player would not.
                { if (current && !resume.completed) vm.toggle() else vm.play(resume.id) })
        }
        if (podcastId == null && subscribed.isNotEmpty()) item(key = "shows") {
            SectionHeading("Your shows", "${subscribed.size} followed", action = "See all", onAction = { vm.open("shows") })
            if (subscribed.size == 1) {
                val show = subscribed.single()
                Surface(onClick = { vm.open("podcast:${show.id}") }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(show.imageUrl, Modifier.size(48.dp))
                        Text(show.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else androidx.compose.foundation.lazy.LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(subscribed, key = { it.id }) { p -> Column(Modifier.width(76.dp).clickable { vm.open("podcast:${p.id}") }) {
                    Cover(p.imageUrl, Modifier.size(76.dp)); Text(p.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                } }
            }
        }
        if (listing.feedProblems.isNotEmpty()) item(key = "feed-problems") {
            FeedProblems(listing.feedProblems, podcastId != null, refreshing, { vm.refresh(it) }, { vm.open("podcast:$it") }, vm::unsubscribe)
        }
        if (listing.episodeCount > 0) item(key = "filters") {
            SectionHeading(when (filter) { "progress" -> "In progress"; "downloads" -> "Downloaded episodes"; else -> "Latest episodes" }, "${visible.size} available")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf("all" to "Latest", "progress" to "In progress", "downloads" to "Downloads").forEach { (id, title) -> FilterChip(selected = filter == id, onClick = { vm.filter(id) }, label = { Text(title) }) }
            }
        }
        if (visible.isEmpty()) item {
            when {
                filter == "downloads" && listing.episodeCount > 0 -> Empty("Ready for offline listening", "Download an episode from Latest and it will appear here.", "Show latest", { vm.filter("all") })
                filter == "progress" && listing.episodeCount > 0 -> Empty("Nothing in progress", "Episodes you start listening to will appear here.", "Show latest", { vm.filter("all") })
                else -> Empty(if (subscribed.isEmpty()) "Your library starts here" else "No episodes here yet", "Find a podcast or add its RSS feed. Your library stays available offline.", "Find podcasts", { vm.root("search") })
            }
        }
        // Other rows get a constant idle state so position ticks recompose only the current row.
        items(visible, key = { it.id }) { ep -> EpisodeRow(ep, podcasts.firstOrNull { it.id == ep.podcastId }, if (player.episodeId == ep.id) player else NOT_CURRENT,
            transcripts.firstOrNull { it.episodeId == ep.id }?.state == "ready", captures.count { it.episodeId == ep.id }, podcastId == null,
            deletionBlocks[ep.id]?.feedback, actions, retryPreparation = if (ep.id in attentionIds) vm::retryAutomation else null) }
    }
}

@Composable private fun MiniPlayer(player: PlaybackState, artwork: String?, onOpen: () -> Unit, onToggle: () -> Unit, onQueue: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column {
            Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(onClickLabel = "Open player", onClick = onOpen).semantics { contentDescription = "Open now playing" }.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Cover(artwork, Modifier.size(44.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(player.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(timeRemaining(player.durationMs, player.positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onToggle) { Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (player.playing) "Pause" else "Resume", Modifier.size(28.dp)) }
                IconButton(onClick = onQueue) { BadgedBox(badge = { if (player.queue.size > 1) Badge(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary) { Text(player.queue.size.coerceAtMost(99).toString()) } }) { Icon(Icons.Default.QueueMusic, "Open queue") } }
            }
            if (player.durationMs > 0) LinearProgressIndicator(progress = { (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PlayerPage(vm: AppViewModel, ep: EpisodeEntity?, podcasts: List<PodcastEntity>, player: PlaybackState, fullscreenExitRequest: Int, onFullscreen: (Boolean) -> Unit) {
    if (ep == null) { Empty("Pick an episode", "Choose something from your library to start listening.", "Open library", { vm.root("library") }); return }
    val podcast = podcasts.firstOrNull { it.id == ep.podcastId }
    var dragging by remember(ep.id) { mutableStateOf<Float?>(null) }
    val displayedPosition = dragging?.toLong() ?: player.positionMs
    var fullscreen by remember(ep.id) { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    VoicePlayerHost(vm, showLastAnswer = !fullscreen) { voiceEnabled, voiceActive, ask ->
        Column(Modifier.fillMaxSize().then(if (fullscreen) Modifier else Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (ep.mimeType?.startsWith("video/") == true) {
                VideocastPlayer(vm.graph.playback, ep.id, title = ep.title, fullscreenExitRequest = fullscreenExitRequest,
                    onFullscreen = { fullscreen = it; onFullscreen(it) },
                    onCapture = vm::captureMomentInPlayer, captureBusy = "distill" in busy,
                    onVoice = ask, onToggle = vm::toggle, onSeek = vm::seek, voiceEnabled = voiceEnabled, voiceActive = voiceActive) { Cover(ep.imageUrl ?: podcast?.imageUrl, Modifier.size(196.dp)) }
            } else Cover(ep.imageUrl ?: podcast?.imageUrl, Modifier.size(196.dp))
            if (!fullscreen) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(ep.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    EpisodeShowLink(podcast, vm::open)
                }
                if (voiceEnabled) Button(onClick = ask, enabled = !voiceActive, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Ask about this")
                }
                PlayerAdControls(vm, ep)
                val skipped by vm.graph.playbackEvents.lastSkipped.collectAsStateWithLifecycle()
                if (skipped?.episodeId == ep.id) TextButton(onClick = vm::undoAdSkip) { Text("Ad skipped · Undo and replay") }
                player.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Column(Modifier.fillMaxWidth()) {
                    Slider(value = dragging ?: player.positionMs.toFloat().coerceIn(0f, player.durationMs.coerceAtLeast(1).toFloat()), onValueChange = { dragging = it },
                        onValueChangeFinished = { dragging?.let { vm.seek(it.toLong()) }; dragging = null }, valueRange = 0f..player.durationMs.coerceAtLeast(1).toFloat(), enabled = player.durationMs > 0,
                        modifier = Modifier.semantics { contentDescription = "Episode playback position"; stateDescription = "${timestamp(displayedPosition)} of ${timestamp(player.durationMs)}" })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(timestamp(displayedPosition), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(timeRemaining(player.durationMs, displayedPosition), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.skip(-10000) }, modifier = Modifier.size(64.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Replay, "Back 10 seconds", Modifier.size(28.dp)); Text("10 s", style = MaterialTheme.typography.labelSmall) }
                    }
                    FilledIconButton(onClick = vm::toggle, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(76.dp)) { Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (player.playing) "Pause" else "Play", Modifier.size(40.dp)) }
                    IconButton(onClick = { vm.skip(30000) }, modifier = Modifier.size(64.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Forward30, "Forward 30 seconds", Modifier.size(28.dp)); Text("30 s", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                PlayerEffects(vm, player.speed, podcast)
                val sleepTimer by vm.sleepTimer.collectAsStateWithLifecycle()
                SleepTimerControl(sleepTimer, onStart = vm::startSleepTimer, onEndOfEpisode = vm::sleepAtEndOfEpisode,
                    onExtend = vm::extendSleepTimer, onCancel = vm::cancelSleepTimer)
                Button(onClick = vm::captureMoment, enabled = canCaptureMoment(player), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("Distill last 60 seconds")
                }
                Text("Save the moment now. Generate its insight when the source is ready.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .35f))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Go deeper", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        TranscriptTools(vm, ep)
                        EpisodeUnderstandingTools(vm, ep)
                        DownloadAction(vm, ep)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.open("episode:${ep.id}") }) { Text("Episode details") }
                            OutlinedButton(onClick = { vm.root("queue") }) { Text("Queue") }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable internal fun EpisodeShowLink(podcast: PodcastEntity?, onOpen: (String) -> Unit) {
    if (podcast == null) {
        Text("Show unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        return
    }
    TextButton(
        onClick = { onOpen("podcast:${podcast.id}") },
        modifier = Modifier.semantics { contentDescription = "View show: ${podcast.title}" },
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(podcast.title, style = MaterialTheme.typography.bodyMedium)
            Text("View show", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.ChevronRight, contentDescription = null)
    }
}

@Composable private fun EpisodePage(vm: AppViewModel, ep: EpisodeEntity?, podcasts: List<PodcastEntity>, player: PlaybackState) {
    if (ep == null) { Empty("Episode unavailable", "Refresh the podcast or choose another saved episode."); return }
    val podcast = podcasts.firstOrNull { it.id == ep.podcastId }
    val detailStates by vm.episodeDetails.collectAsStateWithLifecycle()
    val needsDetails = ep.needsSourceDetails()
    LaunchedEffect(ep.id, needsDetails) { if (needsDetails) vm.loadEpisodeDetails(ep.id) }
    val metadata = episodeMetadataText(ep, detailStates[ep.id])
    EpisodeDetail(ep, podcast, metadata, listeningPosition(ep, player), remember(vm) { vm.episodeActions(openPlayer = true) },
        vm::open, { vm.loadEpisodeDetails(ep.id) }, Modifier.verticalScroll(rememberScrollState())) {
        EpisodeToolsGroup(vm, ep)
    }
}

@Composable private fun QueuePage(vm: AppViewModel, episodes: List<EpisodeEntity>, podcasts: List<PodcastEntity>, player: PlaybackState) {
    QueueContent(episodes, podcasts, player, { vm.root("library") }, { vm.open("episode:$it") },
        { vm.play(it) }, vm::toggle, vm::moveQueue, vm::clearQueue, vm::removeQueue)
}

/** Render the same full order used by Media3, including the current item in its actual position. */
@Composable internal fun QueueContent(
    episodes: List<EpisodeEntity>, podcasts: List<PodcastEntity>, player: PlaybackState,
    onBrowse: () -> Unit, onOpen: (String) -> Unit, onPlay: (String) -> Unit, onToggle: () -> Unit,
    onMove: (String, Int) -> Unit, onClear: () -> Unit, onRemove: (String) -> Unit = {}
) {
    // A changed playlist needs a fresh confirmation, rather than clearing unseen new items.
    var confirmClear by rememberSaveable(player.queue) { mutableStateOf(false) }
    if (player.queue.isEmpty()) {
        Empty("Your queue is clear", "Use Play next or Add to queue on an episode.", "Browse library", onBrowse)
        return
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear queue and stop playback?") },
        text = { Text("Remove all episodes from the queue, including the current episode. Your listening progress and downloaded files are kept.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear and stop") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
    )
    val byId = episodes.associateBy { it.id }
    val shows = podcasts.associateBy { it.id }
    val queue = player.queue
    val currentIndex = queue.indexOf(player.episodeId)
    val historyEnd = currentIndex.coerceAtLeast(0)
    val nextStart = if (currentIndex >= 0) currentIndex + 1 else 0
    // Controls, the history heading and its rows precede "Now playing".
    val focusItem = if (historyEnd > 0) 2 + historyEnd else 0
    val list = rememberLazyListState(initialFirstVisibleItemIndex = focusItem)
    // Open at the current item, since a long history pushes it off screen. Reopening a saved
    // route focuses it again; later edits on this screen keep the user's scroll position.
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focusItem > 0) { if (!focused && focusItem > 0) list.scrollToItem(focusItem); focused = true }
    @Composable fun row(index: Int, id: String) = byId[id]?.let { ep ->
        QueueEpisodeCard(ep, shows[ep.podcastId], player, index + 1, index > 0,
            index < queue.lastIndex, onOpen, onPlay, onToggle, onMove, onRemove)
    }
    LazyColumn(Modifier.testTag("queue-list"), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "queue-controls") {
            SectionHeading("Playback order", "Episodes before the current item remain available to replay.")
            TextButton(onClick = { confirmClear = true }) { Icon(Icons.Default.ClearAll, null); Text("Clear queue") }
        }
        if (historyEnd > 0) {
            item(key = "queue-history") { SectionHeading("History") }
            items(historyEnd, key = { queue[it] }) { row(it, queue[it]) }
        }
        if (currentIndex >= 0) {
            item(key = "queue-now") { SectionHeading("Now playing") }
            item(key = queue[currentIndex]) { row(currentIndex, queue[currentIndex]) }
        }
        item(key = "queue-next") { SectionHeading("Up next") }
        if (nextStart > queue.lastIndex) item(key = "queue-next-empty") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Nothing up next", style = MaterialTheme.typography.titleSmall)
                Text("Use Play next or Add to queue on an episode.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        else items(queue.size - nextStart, key = { queue[nextStart + it] }) { row(nextStart + it, queue[nextStart + it]) }
    }
}

@Composable private fun QueueEpisodeCard(ep: EpisodeEntity, podcast: PodcastEntity?, player: PlaybackState, number: Int?, canMoveUp: Boolean, canMoveDown: Boolean, onOpen: (String) -> Unit, onPlay: (String) -> Unit, onToggle: () -> Unit, onMove: (String, Int) -> Unit, onRemove: (String) -> Unit) {
    val current = player.episodeId == ep.id
    // TalkBack offers the row's queue edits as custom actions; boundary moves are omitted, as
    // the overflow menu disables them. Both resolve by episode identity in the playback layer.
    val actions = buildList {
        if (canMoveUp) add(CustomAccessibilityAction("Move up") { onMove(ep.id, -1); true })
        if (canMoveDown) add(CustomAccessibilityAction("Move down") { onMove(ep.id, 1); true })
        add(CustomAccessibilityAction("Remove from queue") { onRemove(ep.id); true })
    }
    // Enlarged text needs the row's width for the episode, so the controls move below it.
    val stacked = LocalDensity.current.fontScale >= 1.5f
    Card(onClick = { onOpen(ep.id) }, modifier = Modifier.testTag("queue-row-${ep.id}").semantics { customActions = actions },
        colors = CardDefaults.cardColors(containerColor = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        @Composable fun controls() = Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (current && player.playing) onToggle() else onPlay(ep.id) }) { Icon(if (current && player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "${if (current && player.playing) "Pause" else "Play"} ${ep.title}") }
            QueueRowMenu(ep, canMoveUp, canMoveDown, onMove, onRemove)
        }
        Column(Modifier.padding(start = 12.dp, top = 10.dp, bottom = if (stacked) 2.dp else 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (number != null) Text(number.toString().padStart(2, '0'), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Cover(ep.imageUrl ?: podcast?.imageUrl, Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(end = if (stacked) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(ep.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(podcast?.title ?: "Podcast", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(queueTimeLabel(ep, player), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!stacked) controls()
            }
            if (stacked) Box(Modifier.align(Alignment.End)) { controls() }
        }
    }
}

@Composable private fun QueueRowMenu(ep: EpisodeEntity, canMoveUp: Boolean, canMoveDown: Boolean, onMove: (String, Int) -> Unit, onRemove: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Queue actions for ${ep.title}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) }, enabled = canMoveUp,
                onClick = { open = false; onMove(ep.id, -1) })
            DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Default.ArrowDownward, null) }, enabled = canMoveDown,
                onClick = { open = false; onMove(ep.id, 1) })
            DropdownMenuItem(text = { Text("Remove from queue") }, leadingIcon = { Icon(Icons.Default.Close, null) },
                onClick = { open = false; onRemove(ep.id) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SettingsPage(vm: AppViewModel, prefs: Preferences, account: AccountState, models: List<ModelEntity>, episodes: List<EpisodeEntity>, section:String="overview") {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    if(section=="overview") {SettingsOverview(account.connected,context.resources.getBoolean(dev.distillpod.android.R.bool.voice_enabled),vm::open);return}
    if(section=="diagnostics") {DiagnosticsSettings(vm);return}
    LaunchedEffect(account.connected,section) { if (account.connected && section=="ai") vm.graph.account.refreshModels() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if(section=="sync") SubscriptionSyncSettings(vm) { vm.open("backup") }
        if(section=="voice") VoiceSettings(vm)
        if(section=="playback") {
            PlaybackHeadphoneSettings(vm, prefs)
        }
        if(section=="ai") {
        Text("ChatGPT", style = MaterialTheme.typography.titleLarge)
        Text(if (account.connected) "Connected" else "Not connected", color = if (account.connected) MaterialTheme.extras.success else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(account.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("AI actions send selected transcript text, questions or interests to OpenAI. Suggestions share followed-show titles only if you select that option. Audio transcription runs on your phone. Requests use your plan allowance.", style = MaterialTheme.typography.bodySmall)
        if (account.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); OutlinedButton(onClick = { vm.graph.account.cancel() }) { Text("Cancel sign-in") } }
        else {
            Button(onClick = { vm.graph.account.connect() }) { Text(if (account.connected) "Reconnect ChatGPT" else "Continue with ChatGPT") }
            if (account.retry) OutlinedButton(onClick = { vm.graph.account.retry() }) { Text("Retry account connection") }
            if (account.connected) {
                OutlinedButton(onClick = { vm.graph.account.disconnect() }) { Text("Disconnect account") }
                if (account.models.isNotEmpty()) {
                    Text("AI model", style = MaterialTheme.typography.titleSmall)
                    account.models.forEach { model -> Row(Modifier.fillMaxWidth().clickable { vm.text("aiModel", model) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = prefs.selectedAiModel == model || prefs.selectedAiModel.isBlank() && model == account.models.first(), onClick = { vm.text("aiModel", model) }); Text(model, Modifier.weight(1f))
                    } }
                }
                TextButton(onClick = { vm.graph.account.refreshModels() }) { Text("Refresh model access") }
            }
        }
        }
        if(section=="transcription") {
        Text("Transcript preparation", style = MaterialTheme.typography.titleLarge)
        SettingSwitch("Transcribe when playing", "Automatically prepare transcripts for episodes you play. Uses your download and processing settings. Turning this off leaves existing jobs running; use Pause transcript to stop one.", prefs.transcribeOnPlayback) { vm.flag("transcribeOnPlayback", it) }
        SettingSwitch("Look for existing online transcripts", "Before Whisper, search PodcastTranscript.ai for public shows. Sends the episode title; never uploads audio or starts cloud transcription. Off for unapproved private feeds.", prefs.onlineTranscripts) { vm.flag("onlineTranscripts", it) }
        SettingSwitch("Only transcribe while charging", "Pause new transcription chunks until power is connected.", prefs.chargingOnly) { vm.flag("charging", it) }
        TextButton(onClick = { vm.open("settings:storage") }) { Text("Download network policy") }
        Text("Tiny and Base support English and Portuguese. Model files stay on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        whisperModels.forEach { spec ->
            val status = models.firstOrNull { it.id == spec.id }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = prefs.model == spec.id, onClick = { vm.text("model", spec.id) }); Column { Text(spec.title); Text(fileSize(spec.bytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    when (status?.state) {
                        "ready" -> Row(verticalAlignment = Alignment.CenterVertically) { Text("Ready", color = MaterialTheme.extras.success, modifier = Modifier.weight(1f)); TextButton(onClick = { vm.deleteModel(spec.id) }) { Text("Delete model") } }
                        "queued", "downloading" -> { LinearProgressIndicator(progress = { ((status.bytes).toFloat() / spec.bytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { vm.pauseModel(spec.id) }) { Text("Pause download") } }
                        else -> OutlinedButton(onClick = { vm.downloadModel(spec.id) }) { Text(if (status?.state in listOf("failed", "paused")) "Resume model download" else "Download model") }
                    }
                    status?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Text("Language", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("auto" to "Auto", "en" to "English", "pt" to "Português").forEach { (key, title) -> FilterChip(selected = prefs.language == key, onClick = { vm.text("language", key) }, label = { Text(title) }) } }
        }
        if(section=="storage") {
        SettingSwitch("Wi-Fi / unmetered downloads only", "Applies to queued audio, model downloads and transcript preparation that needs internet. Transfers already in progress continue; retries use your new setting.", prefs.wifiOnly) { vm.flag("wifi", it) }
        StorageSettings(vm, prefs, episodes, models)
        }
        if(section=="library") {
        SettingSwitch("Refresh subscriptions", "Check your followed feeds periodically when Android permits.", prefs.refresh) { vm.flag("refresh", it) }
        RefreshIntervalControl(vm, prefs)
            DataControls(vm)
        }
        if(section=="notifications") { NotificationControls() }
        if(section=="about") {
        Text("DistillPod · $version", style = MaterialTheme.typography.labelLarge)
        LicenseAndSources(version)
        TextButton(onClick = { context.startActivity(Intent(context, DiagnosticsActivity::class.java)) }) { Text("Developer checks") }
        }
    }
}
@Composable private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}
