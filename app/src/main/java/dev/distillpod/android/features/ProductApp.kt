package dev.distillpod.android.features

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ink = Color(0xFF0C0F16)
private val accent = Color(0xFFB7B9FF)
private val mint = Color(0xFF91DCC8)
private val colors = darkColorScheme(primary = accent, secondary = mint, tertiary = Color(0xFFFFD28B),
    background = ink, onBackground = Color(0xFFF1F2F6), onPrimary = Color(0xFF17182E),
    primaryContainer = Color(0xFF292D48), onPrimaryContainer = Color(0xFFE6E3FF),
    surface = Color(0xFF151A24), onSurface = Color(0xFFF1F2F6), surfaceVariant = Color(0xFF232B39),
    outline = Color(0xFF536074), onSurfaceVariant = Color(0xFFB0BACB))
@Composable internal fun DistillTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)), content = content)
}
fun timestamp(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}
fun fileSize(bytes: Long): String = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1_000_000_000.0) else "%.0f MB".format(bytes / 1_000_000.0)
private fun date(ms: Long) = if (ms <= 0) "" else DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

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
        route == "saved" || route.startsWith("capture:") -> "Saved moments"
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
        route.startsWith("podcast:") -> podcasts.firstOrNull { it.id == route.substringAfter(':') }?.title ?: "Podcast"
        else -> "Episode"
    }
    DistillTheme {
        BackupDocumentLaunchers(vm)
        Scaffold(containerColor = ink, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            if (!videoFullscreen) TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { if (stack.size > 1) IconButton(onClick = vm::back) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    if (route == "library" || route.startsWith("podcast:")) IconButton(onClick = { vm.refresh(if (route.startsWith("podcast:")) route.substringAfter(':') else null) }, enabled = "refresh" !in busy) { Icon(Icons.Default.Refresh, "Refresh feeds") }
                    if (route == "player") IconButton(onClick = { vm.root("queue") }) { Icon(Icons.Default.QueueMusic, "Open queue") }
                    else if (!route.startsWith("settings")) IconButton(onClick = { vm.open("settings") }) { Icon(Icons.Default.Settings, "Settings") }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = ink))
        }, bottomBar = {
            Column {
                if (player.episodeId != null && route != "player") {
                    val current = episodes.firstOrNull { it.id == player.episodeId }
                    MiniPlayer(player, current?.imageUrl ?: podcasts.firstOrNull { it.id == current?.podcastId }?.imageUrl, { vm.open("player") }, vm::toggle, { vm.root("queue") })
                }
                if (route != "player") NavigationBar(containerColor = ink, tonalElevation = 0.dp) {
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
                    route == "library" || route.startsWith("podcast:") -> LibraryPage(vm, podcasts, episodes, transcripts, captures, player, route.takeIf { it.startsWith("podcast:") }?.substringAfter(':'))
                    route == "search" -> SearchPage(vm, podcasts)
                    route == "queue" -> QueuePage(vm, episodes, podcasts, player)
                    route == "posts" || route.startsWith("posts:") -> PostDraftsPage(vm, route.substringAfter(':', "").takeIf { it.isNotBlank() })
                    route.startsWith("post:") -> PostEditorPage(vm, route.substringAfter(':'))
                    route == "saved" -> SavedPage(vm, captures, episodes, podcasts)
                    route == "shows" -> ShowsPage(vm, podcasts)
                    route == "settings" || route.startsWith("settings:") -> SettingsPage(vm, preferences, account, models, episodes, route.substringAfter(':', "overview"))
                    route == "backup" -> BackupPage(vm) { vm.open("settings:sync") }
                    route == "player" -> PlayerPage(vm, episodes.firstOrNull { it.id == player.episodeId }, podcasts, player, fullscreenExitRequest) { videoFullscreen = it }
                    route.startsWith("episode:") -> EpisodePage(vm, episodes.firstOrNull { it.id == route.substringAfter(':') }, podcasts, player)
                    route.startsWith("capture:") -> CapturePage(vm, captures.firstOrNull { it.id == route.substringAfter(':') }, episodes)
                    route.startsWith("transcript:") -> TranscriptPage(vm, episodes.firstOrNull { it.id == route.substringAfter(':') })
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
@Composable private fun LibraryPage(vm: AppViewModel, podcasts: List<PodcastEntity>, episodes: List<EpisodeEntity>, transcripts: List<TranscriptEntity>, captures: List<DistillationEntity>, player: PlaybackState, podcastId: String?) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val subscribed = podcasts.filter { it.subscribed }
    val selected = podcasts.firstOrNull { it.id == podcastId }
    val library = episodes.filter { ep -> if (podcastId != null) ep.podcastId == podcastId else subscribed.any { it.id == ep.podcastId } || ep.localFile != null || ep.lastPlayedAt > 0 }
    val visible = library.filter { ep -> when (filter) { "downloads" -> ep.localFile != null; "progress" -> ep.positionMs > 0 && !ep.completed; else -> true } }.let { rows -> if (selected?.feedUrl?.let { dev.distillpod.core.YouTubeUrl.source(it) } != null)
        rows.sortedBy { it.sourceOrder ?: Int.MAX_VALUE } else rows }
    val resume = library.firstOrNull { it.id == player.episodeId && !it.completed }
        ?: library.filter { it.positionMs > 0 && !it.completed }.maxByOrNull { it.lastPlayedAt }
    val showResume = podcastId == null && filter == "all" && resume != null
    val list = rememberLazyListState()
    // Room/player flows can insert the resume card above the initial show row.
    // Start a newly selected collection at its actual first item, including that card.
    LaunchedEffect(podcastId, filter, showResume) { list.scrollToItem(0) }
    LazyColumn(state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (selected != null) item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(selected.imageUrl, Modifier.size(88.dp))
                Column(Modifier.weight(1f)) { Text(selected.author, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${library.size} episodes", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { if (selected.subscribed) vm.unsubscribe(selected.id) else vm.subscribe(selected.feedUrl) }) { Text(if (selected.subscribed) "Unsubscribe" else "Subscribe") }
                }
            }
            if (selected.subscribed) ShowAutomationControls(vm, selected.id)
            if (selected.description.isNotBlank()) Text(selected.description, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (podcastId == null && filter == "all" && resume != null) item(key = "resume") {
            val current = player.episodeId == resume.id
            ResumeListeningCard(resume, podcasts.firstOrNull { it.id == resume.podcastId },
                if (current) player.positionMs else resume.positionMs, if (current && player.durationMs > 0) player.durationMs else resume.durationMs,
                current && player.playing, { if (current) vm.open("player") else vm.open("episode:${resume.id}") },
                { if (current) vm.toggle() else vm.play(resume.id) })
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
        if (podcasts.any { it.error != null }) item { Text("Some feeds could not refresh. Your saved library is still available.", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
        if (library.isNotEmpty()) item(key = "filters") {
            SectionHeading(when (filter) { "progress" -> "In progress"; "downloads" -> "Downloaded episodes"; else -> "Latest episodes" }, "${visible.size} available")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf("all" to "Latest", "progress" to "In progress", "downloads" to "Downloads").forEach { (id, title) -> FilterChip(selected = filter == id, onClick = { vm.filter(id) }, label = { Text(title) }) }
            }
        }
        if (visible.isEmpty()) item {
            when {
                filter == "downloads" && library.isNotEmpty() -> Empty("Ready for offline listening", "Download an episode from Latest and it will appear here.", "Show latest", { vm.filter("all") })
                filter == "progress" && library.isNotEmpty() -> Empty("Nothing in progress", "Episodes you start listening to will appear here.", "Show latest", { vm.filter("all") })
                else -> Empty(if (subscribed.isEmpty()) "Your library starts here" else "No episodes here yet", "Find a podcast or add its RSS feed. Your library stays available offline.", "Find podcasts", { vm.root("search") })
            }
        }
        items(visible, key = { it.id }) { ep -> EpisodeCard(vm, ep, podcasts.firstOrNull { it.id == ep.podcastId }, player,
            transcripts.firstOrNull { it.episodeId == ep.id }, captures.count { it.episodeId == ep.id }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun EpisodeCard(vm: AppViewModel, ep: EpisodeEntity, podcast: PodcastEntity?, player: PlaybackState, transcript: TranscriptEntity? = null, captureCount: Int = 0) {
    val current = player.episodeId == ep.id
    val position = if (current) player.positionMs else ep.positionMs
    val duration = if (current && player.durationMs > 0) player.durationMs else ep.durationMs
    Card(onClick = { vm.open("episode:${ep.id}") }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 14.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(ep.imageUrl ?: podcast?.imageUrl, Modifier.size(60.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(podcast?.title ?: "Podcast", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    Text(ep.title, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(listOf(date(ep.publishedAt), if (position > 0 && !ep.completed) timeRemaining(duration, position) else duration.takeIf { it > 0 }?.let(::timestamp) ?: "").filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = { if (current && player.playing) vm.toggle() else vm.play(ep.id) }) {
                    Icon(if (current && player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "${if (current && player.playing) "Pause" else "Play"} ${ep.title}")
                }
            }
            if (position > 0 && duration > 0 && !ep.completed) LinearProgressIndicator(progress = { (position.toFloat() / duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(end = 6.dp).height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (ep.completed) StatusLabel("Played")
                    if (ep.localFile != null) StatusLabel("Offline", true)
                    if (transcript?.state == "ready") StatusLabel("Transcript")
                    if (captureCount > 0) StatusLabel("$captureCount saved", true)
                    if (ep.downloadState in listOf("queued", "downloading")) StatusLabel(if (ep.totalBytes > 0) "${(ep.downloadedBytes * 100 / ep.totalBytes).coerceIn(0, 100)}%" else "Queued")
                }
                if (ep.localFile == null) IconButton(onClick = { if (ep.downloadState in listOf("queued", "downloading")) vm.pauseDownload(ep.id) else vm.download(ep.id) }) {
                    Icon(if (ep.downloadState in listOf("queued", "downloading")) Icons.Default.PauseCircleOutline else Icons.Default.Download,
                        "${if (ep.downloadState in listOf("queued", "downloading")) "Pause download for" else "Download"} ${ep.title}")
                }
                EpisodeMenu(vm, ep)
            }
        }
    }
}
@Composable private fun EpisodeMenu(vm: AppViewModel, ep: EpisodeEntity) {
    val deletionBlocks by vm.audioDeletionBlocks.collectAsState()
    val blocked = deletionBlocks[ep.id]
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More actions for ${ep.title}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Play next") }, onClick = { vm.enqueue(ep.id, true); open = false })
            DropdownMenuItem(text = { Text("Add to queue") }, onClick = { vm.enqueue(ep.id); open = false })
            DropdownMenuItem(text = { Text(if (ep.completed) "Mark unplayed" else "Mark played") }, onClick = { vm.markCompleted(ep.id, !ep.completed); open = false })
            if (ep.localFile != null) {
                DropdownMenuItem(text = { Text("Delete download") }, enabled = blocked == null,
                    onClick = { vm.deleteDownload(ep.id); open = false })
                blocked?.let { DropdownMenuItem(text = { Text(it.feedback) }, enabled = false, onClick = {}) }
            }
        }
    }
}
@Composable private fun DownloadAction(vm: AppViewModel, ep: EpisodeEntity) {
    if (ep.downloadState in listOf("queued", "downloading")) TextButton(onClick = { vm.pauseDownload(ep.id) }) {
        Text(if (ep.totalBytes > 0) "${(ep.downloadedBytes * 100 / ep.totalBytes).coerceIn(0, 100)}% · Pause" else "Queued · Pause")
    } else if (ep.localFile == null) TextButton(onClick = { vm.download(ep.id) }) { Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Text(if (ep.downloadState in listOf("paused", "failed")) " Resume" else " Download") }
}

@Composable private fun MiniPlayer(player: PlaybackState, artwork: String?, onOpen: () -> Unit, onToggle: () -> Unit, onQueue: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .12f)), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    listOf(1f, 1.25f, 1.5f, 2f).forEach { speed -> FilterChip(selected = player.speed == speed, onClick = { vm.speed(speed) }, label = { Text("${speed.toString().removeSuffix(".0")}×") }) }
                }
                Button(onClick = vm::captureMoment, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun EpisodePage(vm: AppViewModel, ep: EpisodeEntity?, podcasts: List<PodcastEntity>, player: PlaybackState) {
    if (ep == null) { Empty("Episode unavailable", "Refresh the podcast or choose another saved episode."); return }
    val podcast = podcasts.firstOrNull { it.id == ep.podcastId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(ep.imageUrl ?: podcast?.imageUrl, Modifier.size(96.dp)); Column(Modifier.weight(1f)) { EpisodeShowLink(podcast, vm::open); Text(date(ep.publishedAt), style = MaterialTheme.typography.bodySmall) }
        }
        Text(ep.title, style = MaterialTheme.typography.headlineSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.play(ep.id); vm.open("player") }) { Icon(Icons.Default.PlayArrow, null); Text(if (ep.positionMs > 0 && !ep.completed) "Resume ${timestamp(ep.positionMs)}" else "Play episode") }
            DownloadAction(vm, ep)
        }
        if (ep.downloadState in listOf("downloading", "queued")) Text("${fileSize(ep.downloadedBytes)}${if (ep.totalBytes > 0) " of ${fileSize(ep.totalBytes)}" else ""} · Downloads follow your network settings.", style = MaterialTheme.typography.bodySmall)
        ep.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TranscriptTools(vm, ep)
        EpisodeUnderstandingTools(vm, ep)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { vm.enqueue(ep.id, true) }) { Text("Play next") }; OutlinedButton(onClick = { vm.enqueue(ep.id) }) { Text("Add to queue") } }
        if (ep.description.isNotBlank()) SelectionContainer { Text(ep.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable private fun QueuePage(vm: AppViewModel, episodes: List<EpisodeEntity>, podcasts: List<PodcastEntity>, player: PlaybackState) {
    if (player.queue.isEmpty()) { Empty("Your queue is clear", "Use Play next or Add to queue on an episode.", "Browse library", { vm.root("library") }); return }
    val currentIndex = player.queue.indexOf(player.episodeId)
    val upcoming = if (currentIndex >= 0) player.queue.drop(currentIndex + 1) else player.queue
    val previous = if (currentIndex > 0) player.queue.take(currentIndex) else emptyList()
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        player.episodeId?.let { current -> episodes.firstOrNull { it.id == current }?.let { ep ->
            item(key = "current") {
                SectionHeading(if (player.playing) "Now playing" else "Ready to resume")
                Spacer(Modifier.height(10.dp))
                QueueEpisodeCard(vm, ep, podcasts.firstOrNull { it.id == ep.podcastId }, player, null, false, false)
            }
        } }
        item(key = "up-next-heading") { SectionHeading("Up next", "${upcoming.size} ${if (upcoming.size == 1) "episode" else "episodes"} in order") }
        if (upcoming.isEmpty()) item { Text("Add an episode with Play next or Add to queue to keep listening.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        items(upcoming, key = { it }) { id -> episodes.firstOrNull { it.id == id }?.let { ep ->
            val ordinal = upcoming.indexOf(id)
            QueueEpisodeCard(vm, ep, podcasts.firstOrNull { it.id == ep.podcastId }, player, ordinal + 1, ordinal > 0, ordinal < upcoming.lastIndex)
        } }
        if (previous.isNotEmpty()) item { SectionHeading("Earlier in queue", "Kept here so you can return to it") }
        items(previous, key = { it }) { id -> episodes.firstOrNull { it.id == id }?.let { ep ->
            val index = previous.indexOf(id)
            QueueEpisodeCard(vm, ep, podcasts.firstOrNull { it.id == ep.podcastId }, player, null, index > 0, index < previous.lastIndex)
        } }
    }
}

@Composable private fun QueueEpisodeCard(vm: AppViewModel, ep: EpisodeEntity, podcast: PodcastEntity?, player: PlaybackState, number: Int?, canMoveUp: Boolean, canMoveDown: Boolean) {
    val current = player.episodeId == ep.id
    Card(onClick = { vm.open("episode:${ep.id}") }, colors = CardDefaults.cardColors(containerColor = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(start = 14.dp, top = 14.dp, end = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (number != null) Text(number.toString().padStart(2, '0'), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Cover(ep.imageUrl ?: podcast?.imageUrl, Modifier.size(48.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ep.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(podcast?.title ?: "Podcast", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { if (current && player.playing) vm.toggle() else vm.play(ep.id) }) { Icon(if (current && player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "${if (current && player.playing) "Pause" else "Play"} ${ep.title}") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(timeRemaining(ep.durationMs, if (current) player.positionMs else ep.positionMs), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!current) {
                    IconButton(onClick = { vm.moveQueue(ep.id, -1) }, enabled = canMoveUp) { Icon(Icons.Default.ArrowUpward, "Move ${ep.title} up") }
                    IconButton(onClick = { vm.moveQueue(ep.id, 1) }, enabled = canMoveDown) { Icon(Icons.Default.ArrowDownward, "Move ${ep.title} down") }
                }
                IconButton(onClick = { vm.removeQueue(ep.id) }) { Icon(Icons.Default.Close, "Remove ${ep.title} from queue") }
            }
        }
    }
}

private fun captureState(state: String) = when (state) { "waiting_transcript" -> "Waiting for transcript"; "waiting_auth" -> "Connect ChatGPT to continue"; "waiting_quota" -> "Plan allowance unavailable"; "queued", "running" -> "Finding the idea"; "reviewing" -> "Checking the source"; "needs_context" -> "Needs more context"; "unclear_source" -> "Review the transcript"; "failed" -> "Needs retry"; "review_source" -> "Review source"; "no_speech" -> "No speech in this window"; "imported" -> "Imported"; else -> "Saved" }
@Composable private fun SavedPage(vm: AppViewModel, captures: List<DistillationEntity>, episodes: List<EpisodeEntity>, podcasts: List<PodcastEntity>) {

    var query by rememberSaveable { mutableStateOf("") }
    val matching = remember(captures,episodes,podcasts,query) { captures.filter { saved ->
        val ep=episodes.firstOrNull {it.id==saved.episodeId}
        val show=podcasts.firstOrNull {it.id==ep?.podcastId}
        query.isBlank() || listOfNotNull(saved.quote,saved.excerpt,saved.insight,ep?.title,show?.title).any {it.contains(query.trim(),ignoreCase=true)}
    } }
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
    TextButton(onClick = { vm.open("posts") }) { Text("Your posts") }
    LocalSearch(query,{query=it},"Search saved moments","Clear saved search")
    LazyColumn(contentPadding = PaddingValues(bottom=20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionHeading("Your collection", "${matching.size} ${if (matching.size == 1) "moment" else "moments"} to revisit") }
        if(captures.isEmpty())item { Empty("Keep the moments that matter", "Save the last minute in the player. Your quotes, insights and original sources will appear here.", "Open library", { vm.root("library") }) }
        else if(matching.isEmpty())item { Text("No matching moments",style=MaterialTheme.typography.titleMedium); TextButton(onClick={query=""}) {Text("Clear search")} }
        items(matching, key = { it.id }) { saved ->
            val ep = episodes.firstOrNull { it.id == saved.episodeId }
            val podcast = podcasts.firstOrNull { it.id == ep?.podcastId }
            Card(onClick = { vm.open("capture:${saved.id}") }, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FormatQuote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        StatusLabel(captureState(saved.state), saved.state == "succeeded")
                    }
                    Text(saved.insight ?: "Moment saved · ${captureState(saved.state)}", maxLines = 5, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, lineHeight = 28.sp), fontWeight = FontWeight.Normal)
                    saved.quote?.let { Text("“$it”", maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(ep?.imageUrl ?: podcast?.imageUrl, Modifier.size(36.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(ep?.title ?: "Episode", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            Text("${timestamp(saved.sourceStartMs)} – ${timestamp(saved.sourceEndMs)} · Source passage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
}
@Composable private fun CapturePage(vm: AppViewModel, capture: DistillationEntity?, episodes: List<EpisodeEntity>) {
    if (capture == null) return
    val context = LocalContext.current
    val ep = episodes.firstOrNull { it.id == capture.episodeId }
    var createPost by rememberSaveable { mutableStateOf(false) }
    if (createPost) CreatePostSheet(vm,capture.id) { createPost = false }
    val share = listOfNotNull(capture.insight, capture.quote?.let { "“$it”" }, ep?.title?.let { "$it · ${timestamp(capture.sourceStartMs)}–${timestamp(capture.sourceEndMs)}" }).joinToString("\n\n")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Button(onClick = { createPost = true }, enabled = capture.excerpt.isNotBlank()) { Text("Create post") }
        TextButton(onClick = { vm.open("posts:${capture.id}") }) { Text("Posts from this moment") }
        Text(captureState(capture.state), color = MaterialTheme.colorScheme.secondary)
        if (ep != null && capture.fingerprint.isNotBlank() && capture.fingerprint != ep.mediaFingerprint) Text("The audio source changed since this moment was saved. The saved text is preserved; review the audio before relying on its replay time.", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        if (capture.state in listOf("waiting_transcript", "queued", "running", "reviewing")) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = { vm.cancelCapture(capture.id) }) { Text("Pause generation") }
        }
        if(capture.state=="saved" && capture.excerpt.isBlank() && ep!=null) OutlinedButton(onClick={vm.generateCapture(capture.id)}) {Text("Prepare saved moment")}
        if (capture.state == "waiting_transcript" && ep != null) TranscriptTools(vm, ep)
        if (capture.state == "waiting_auth") Button(onClick = { vm.open("settings:ai") }) { Text("Connect ChatGPT") }
        if (capture.state == "review_source") Text("Review the proposed source context below before sending it to ChatGPT. The saved result is kept until its replacement passes review.", color = MaterialTheme.colorScheme.tertiary)
        if (capture.excerpt.isNotBlank() && capture.state in listOf("review_source", "waiting_auth", "waiting_quota", "failed", "saved")) {
            Button(onClick = { vm.generateCapture(capture.id, true) }) { Text(if (capture.state == "review_source") "Generate insight from this source" else if(capture.state=="saved") "Generate insight" else "Retry insight") }
            Text("Retrying a request can use more of your ChatGPT allowance.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        capture.insight?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.headlineSmall) } }
        capture.quote?.let { SelectionContainer { Text("“$it”", style = MaterialTheme.typography.bodyLarge) } }
        MomentQualityTools(vm, capture, ep)
        val reports by vm.research.collectAsStateWithLifecycle()
        val latest = reports.firstOrNull { it.distillationId == capture.id && it.state in listOf("queued", "running", "succeeded") && explanationMatches(it,capture) }
        if (capture.excerpt.isNotBlank()) OutlinedButton(onClick = { if (latest == null) vm.explain(capture.id) else vm.open("research:${latest.id}") }) { Text(if (latest?.state == "succeeded") "Read explanation" else "Explain this idea") }
        Text(ep?.title ?: "Episode", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { vm.play(capture.episodeId, capture.sourceStartMs); vm.open("player") }) { Text("Replay source · ${timestamp(capture.sourceStartMs)}") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DistillPod", share)) }) { Text("Copy") }
            OutlinedButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, share), "Share saved moment")) }) { Text("Share") }
        }
        capture.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        var showSource by rememberSaveable(capture.id) { mutableStateOf(false) }
        HorizontalDivider()
        TextButton(onClick = { showSource = !showSource }) { Text(if(showSource) "Hide source passage" else "Read source passage") }
        if(showSource) SelectionContainer { Text(capture.excerpt.ifBlank { "The source text is waiting for a transcript." }, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = { vm.deleteCapture(capture.id); vm.back() }) { Text("Delete saved moment", color = MaterialTheme.colorScheme.error) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SettingsPage(vm: AppViewModel, prefs: Preferences, account: AccountState, models: List<ModelEntity>, episodes: List<EpisodeEntity>, section:String="overview") {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    if(section=="overview") {SettingsOverview(vm,account.connected);return}
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
        Text(if (account.connected) "Connected" else "Not connected", color = if (account.connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
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
                        "ready" -> Row(verticalAlignment = Alignment.CenterVertically) { Text("Ready", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f)); TextButton(onClick = { vm.deleteModel(spec.id) }) { Text("Delete model") } }
                        "queued", "downloading" -> { LinearProgressIndicator(progress = { ((status.bytes).toFloat() / spec.bytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { vm.graph.jobs.pauseModel(spec.id) }) { Text("Pause download") } }
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
