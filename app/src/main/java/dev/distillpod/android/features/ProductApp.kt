package dev.distillpod.android.features

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.heading
import kotlinx.coroutines.launch
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
import dev.distillpod.android.ui.theme.DistillSpacing
import dev.distillpod.android.ui.theme.Button
import dev.distillpod.android.ui.theme.OutlinedButton
import dev.distillpod.android.ui.theme.TextButton
import dev.distillpod.android.ui.theme.FilterChip
import dev.distillpod.android.ui.theme.DistillComponentDefaults

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
        route == "search" -> "Search"
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
                navigationIcon = { if (stack.size > 1) IconButton(onClick = vm::back) { Icon(if (route == "player") Icons.Default.KeyboardArrowDown else Icons.Default.ArrowBack, "Back") } },
                actions = {
                    // Proposal 5.6: the settings gear only on Library; every other screen reaches Settings through its overflow menu.
                    val settings = OverflowAction("Settings", Icons.Default.Settings) { vm.open("settings") }
                    val refresh = OverflowAction(if (route == "library") "Refresh feeds" else "Refresh feed", Icons.Default.Refresh, "refresh" !in busy) {
                        vm.refresh(route.takeIf { it.startsWith("podcast:") }?.substringAfter(':'))
                    }
                    when {
                        route == "player" -> {
                            val playing = episodes.firstOrNull { it.id == player.episodeId }
                            if (playing != null) PlayerOverflowMenu(vm, playing, podcasts.firstOrNull { it.id == playing.podcastId })
                            else IconButton(onClick = { vm.root("queue") }) { Icon(Icons.Default.QueueMusic, "Open queue") }
                        }
                        route == "library" -> {
                            IconButton(onClick = settings.onClick) { Icon(Icons.Default.Settings, "Settings") }
                            TopBarOverflow(listOf(refresh))
                        }
                        // Pull to refresh has no accessibility action, so the overflow keeps an explicit refresh.
                        route.startsWith("podcast:") -> TopBarOverflow(listOf(refresh, settings))
                        route == "saved" -> TopBarOverflow(listOf(OverflowAction("Your posts", Icons.AutoMirrored.Filled.Article) { vm.open("posts") }, settings))
                        !route.startsWith("settings") -> TopBarOverflow(listOf(settings))
                    }
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
                        NavigationBarItem(selected = stack.first() == key, onClick = { vm.root(key) }, icon = { Icon(icon, label) }, label = { Text(label) },
                            colors = DistillComponentDefaults.navigationBarItemColors())
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
                    route == "queue" -> QueuePage(vm, episodes, podcasts, player, snackbar)
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
                    route == "player" -> PlayerPage(vm, episodes.firstOrNull { it.id == player.episodeId }, podcasts, fullscreenExitRequest) { videoFullscreen = it }
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

private class OverflowAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val enabled: Boolean = true, val onClick: () -> Unit)

/** The top bar's ⋮ menu. */
@Composable private fun TopBarOverflow(actions: List<OverflowAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(text = { Text(action.label) }, leadingIcon = { Icon(action.icon, null) }, enabled = action.enabled,
                    onClick = { open = false; action.onClick() })
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

/** Filter id, label, and the shorter label used when large text would truncate three segments. */
private val LIBRARY_FILTERS = listOf(Triple("all", "Latest", "Latest"), Triple("progress", "In progress", "Started"), Triple("downloads", "Downloaded", "Offline"))

/**
 * The Library tab, or a show page for [podcastId] (proposal 5.4): continue listening, the show rail, feed
 * problems, sticky filters and flat episode rows. Pulling down refreshes feeds; the top bar's overflow menu
 * keeps "Refresh feeds" for TalkBack and keyboard users, since a pull gesture has no accessibility action.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    val continueListening = if (podcastId == null && filter == "all") listing.continueListening else emptyList()
    val preparationRows = if (selected?.subscribed == true) showPreparationRows(vm, selected.id) else emptyList()
    val attentionIds = remember(preparationRows) { preparationRows.filter { it.retryable }.mapTo(mutableSetOf()) { it.episodeId } }
    var aboutExpanded by rememberSaveable(podcastId) { mutableStateOf(false) }
    val showResume = continueListening.isNotEmpty()
    val list = rememberLazyListState()
    val gutter = Modifier.padding(horizontal = DistillSpacing.gutter)
    // Room/player flows can insert the resume card above the initial show row.
    // Start a newly selected collection at its actual first item, including that card.
    LaunchedEffect(podcastId, filter, showResume) { list.scrollToItem(0) }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh(podcastId) }, modifier = Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp)) {
        if (selected != null) item {
            Column(gutter.padding(bottom = DistillSpacing.gutter)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(selected.imageUrl, Modifier.size(88.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(selected.author, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${listing.episodeCount} episodes", style = MaterialTheme.typography.labelLarge)
                        ShowFollowButton(selected.title, selected.subscribed, { vm.subscribe(selected.feedUrl) }, { vm.unsubscribe(selected.id) })
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
        }
        if (showResume) item(key = "resume") {
            Column(Modifier.padding(bottom = DistillSpacing.section), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(gutter) { SectionHeading("Continue listening") }
                @Composable fun card(ep: EpisodeEntity, primary: Boolean, modifier: Modifier) {
                    val current = player.episodeId == ep.id
                    // Only the playing card reads live position ticks (#141).
                    val state = if (current) player else NOT_CURRENT
                    ResumeListeningCard(ep, podcasts.firstOrNull { it.id == ep.podcastId }, listeningPosition(ep, state), listeningDuration(ep, state),
                        current && player.playing, { if (current) vm.open("player") else vm.open("episode:${ep.id}") },
                        // A finished episode restarts from the beginning; toggling an ended player would not.
                        { if (current && !ep.completed) vm.toggle() else vm.play(ep.id) }, modifier, primary)
                }
                if (continueListening.size == 1) card(continueListening.single(), true, gutter.fillMaxWidth())
                else androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = DistillSpacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(DistillSpacing.item)) {
                    itemsIndexed(continueListening, key = { _, ep -> ep.id }) { index, ep -> card(ep, index == 0, Modifier.fillParentMaxWidth(.86f)) }
                }
            }
        }
        if (podcastId == null && subscribed.isNotEmpty()) item(key = "shows") {
            Column(Modifier.padding(bottom = DistillSpacing.section)) {
                Box(gutter) { SectionHeading("Your shows", "${subscribed.size} followed", action = "See all", onAction = { vm.open("shows") }) }
                if (subscribed.size == 1) {
                    val show = subscribed.single()
                    Surface(onClick = { vm.open("podcast:${show.id}") }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = gutter.fillMaxWidth().padding(top = 6.dp)) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(show.imageUrl, Modifier.size(48.dp))
                            Text(show.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else androidx.compose.foundation.lazy.LazyRow(Modifier.padding(top = 6.dp), contentPadding = PaddingValues(horizontal = DistillSpacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(subscribed, key = { it.id }) { p -> Column(Modifier.width(76.dp).clickable { vm.open("podcast:${p.id}") }) {
                        Cover(p.imageUrl, Modifier.size(76.dp)); Text(p.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    } }
                }
            }
        }
        if (listing.feedProblems.isNotEmpty()) item(key = "feed-problems") {
            Box(gutter.padding(bottom = DistillSpacing.gutter)) {
                FeedProblems(listing.feedProblems, podcastId != null, refreshing, { vm.refresh(it) }, { vm.open("podcast:$it") }, vm::unsubscribe)
            }
        }
        if (listing.episodeCount > 0) stickyHeader(key = "filters") { LibraryFilters(filter, visible.size, vm::filter) }
        if (visible.isEmpty()) item {
            when {
                filter == "downloads" && listing.episodeCount > 0 -> Empty("Ready for offline listening", "Download an episode from Latest and it will appear here.", "Show latest", { vm.filter("all") })
                filter == "progress" && listing.episodeCount > 0 -> Empty("Nothing in progress", "Episodes you start listening to will appear here.", "Show latest", { vm.filter("all") })
                else -> Empty(if (subscribed.isEmpty()) "Your library starts here" else "No episodes here yet", "Find a podcast or add its RSS feed. Your library stays available offline.", "Find podcasts", { vm.root("search") })
            }
        }
        // Other rows get a constant idle state so position ticks recompose only the current row.
        itemsIndexed(visible, key = { _, ep -> ep.id }) { index, ep ->
            // An in-progress row ends in its progress line, which already separates it from this one.
            if (index > 0) visible[index - 1].let { prev -> if (!episodeInProgress(prev, if (player.episodeId == prev.id) player else NOT_CURRENT)) EpisodeRowDivider() }
            EpisodeRow(ep, podcasts.firstOrNull { it.id == ep.podcastId }, if (player.episodeId == ep.id) player else NOT_CURRENT,
                transcripts.firstOrNull { it.episodeId == ep.id }?.state == "ready", captures.count { it.episodeId == ep.id }, podcastId == null,
                deletionBlocks[ep.id]?.feedback, actions, retryPreparation = if (ep.id in attentionIds) vm::retryAutomation else null)
        }
    }
    }
}

/** The sticky filter header: one segmented control over the rows, on the page background so rows pass beneath it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LibraryFilters(filter: String, count: Int, onFilter: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = DistillSpacing.gutter, vertical = DistillSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("library-filters")) {
            val large = LocalDensity.current.fontScale >= 1.3f
            LIBRARY_FILTERS.forEachIndexed { index, (id, label, short) ->
                // TalkBack always hears the full name.
                SegmentedButton(selected = filter == id, onClick = { onFilter(id) }, shape = SegmentedButtonDefaults.itemShape(index, LIBRARY_FILTERS.size),
                    icon = {}, modifier = Modifier.semantics { contentDescription = label },
                    label = { Text(if (large) short else label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
        }
        Text("$count available", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun MiniPlayer(player: PlaybackState, artwork: String?, onOpen: () -> Unit, onToggle: () -> Unit, onQueue: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column {
            // A 2 dp accent progress line flush with the top edge; drawn, so it needs no layout pass.
            if (player.durationMs > 0) Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                drawRect(accent, size = size.copy(width = size.width * (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f)))
            }
            Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(onClickLabel = "Open player", onClick = onOpen).semantics { contentDescription = "Open now playing" }.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PodcastCover(artwork, Modifier.size(40.dp), MaterialTheme.shapes.small)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(player.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(timeRemaining(player.durationMs, player.positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onToggle) { Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (player.playing) "Pause" else "Resume", Modifier.size(28.dp)) }
                IconButton(onClick = onQueue) { BadgedBox(badge = { if (player.queue.size > 1) Badge(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) { Text(player.queue.size.coerceAtMost(99).toString()) } }) { Icon(Icons.Default.QueueMusic, "Open queue") } }
            }
        }
    }
}

/**
 * Now playing (proposal 5.1): artwork over its tint, title and show, scrubber, transport, the action dock
 * and a handle for the peek sheet. It fits one phone screen at default text size and scrolls at large text.
 * The live player state is read through derived slices; only [PlayerScrubber] reads the position (#141).
 */
@Composable private fun PlayerPage(vm: AppViewModel, ep: EpisodeEntity?, podcasts: List<PodcastEntity>, fullscreenExitRequest: Int, onFullscreen: (Boolean) -> Unit) {
    if (ep == null) { Empty("Pick an episode", "Choose something from your library to start listening.", "Open library", { vm.root("library") }); return }
    val podcast = podcasts.firstOrNull { it.id == ep.podcastId }
    val live = vm.player.collectAsStateWithLifecycle()
    val playing by remember { derivedStateOf { live.value.playing } }
    val speed by remember { derivedStateOf { live.value.speed } }
    val durationMs by remember { derivedStateOf { live.value.durationMs } }
    val error by remember { derivedStateOf { live.value.error } }
    val canDistill by remember { derivedStateOf { canCaptureMoment(live.value) } }
    val position = remember(live) { { live.value.positionMs } }
    var fullscreen by remember(ep.id) { mutableStateOf(false) }
    var sheet by rememberSaveable(ep.id) { mutableStateOf<PlayerSheetTab?>(null) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val captures by vm.captures.collectAsStateWithLifecycle()
    val moments = remember(captures, ep.id) { momentMarks(captures, ep.id) }
    val chapters = rememberChapterStarts(vm, ep)
    val transcripts by vm.transcripts.collectAsStateWithLifecycle()
    val readable = transcripts.firstOrNull { it.episodeId == ep.id }?.processedMs?.let { it > 0 } == true
    val skipped by vm.graph.playbackEvents.lastSkipped.collectAsStateWithLifecycle()
    val sleepTimer by vm.sleepTimer.collectAsStateWithLifecycle()
    val artwork = ep.imageUrl ?: podcast?.imageUrl
    val tint = rememberArtworkTint(artwork)
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        val page = this
        VoicePlayerHost(vm, showLastAnswer = !fullscreen) { voiceEnabled, voiceActive, ask ->
            BoxWithConstraints(with(page) { Modifier.weight(1f) }.fillMaxWidth().then(if (fullscreen) Modifier else Modifier.artworkBackdrop(tint))) {
                // About 60% of the width, smaller when a short screen needs the room for the controls.
                val art = minOf(maxWidth * .6f, maxHeight - 412.dp).coerceAtLeast(120.dp)
                Column(Modifier.fillMaxSize().then(if (fullscreen) Modifier else Modifier.verticalScroll(scroll).heightIn(min = maxHeight).padding(horizontal = 20.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                        if (ep.mimeType?.startsWith("video/") == true) {
                            VideocastPlayer(vm.graph.playback, ep.id, title = ep.title, fullscreenExitRequest = fullscreenExitRequest,
                                onFullscreen = { fullscreen = it; onFullscreen(it) },
                                onCapture = vm::captureMomentInPlayer, captureBusy = "distill" in busy,
                                onVoice = ask, onToggle = vm::toggle, onSeek = vm::seek, voiceEnabled = voiceEnabled, voiceActive = voiceActive) { PlayerArtwork(artwork, art) }
                        } else PlayerArtwork(artwork, art)
                    }
                    if (!fullscreen) {
                        Spacer(Modifier.height(12.dp)); Spacer(Modifier.weight(1f))
                        Column(Modifier.fillMaxWidth()) {
                            Text(ep.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            PlayerShowLink(podcast, vm::open)
                        }
                        if (skipped?.episodeId == ep.id) TextButton(onClick = vm::undoAdSkip, modifier = Modifier.align(Alignment.Start)) { Text("Ad skipped · Undo and replay") }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth()) }
                        PlayerScrubber(position, durationMs, vm::seek, Modifier.padding(top = 4.dp), moments, chapters)
                        PlayerTransport(playing, vm::toggle, vm::skip, modifier = Modifier.padding(vertical = 8.dp),
                            speed = { PlayerSpeedControl(vm, speed, podcast) },
                            sleep = { SleepTimerControl(sleepTimer, onStart = vm::startSleepTimer, onEndOfEpisode = vm::sleepAtEndOfEpisode,
                                onExtend = vm::extendSleepTimer, onCancel = vm::cancelSleepTimer) })
                        PlayerActionDock(canDistill, vm::captureMoment, voiceEnabled, voiceActive, ask,
                            onTranscript = { if (readable) vm.open(transcriptRoute(ep.id, position())) else sheet = PlayerSheetTab.TRANSCRIPT })
                        Spacer(Modifier.height(12.dp)); Spacer(Modifier.weight(.4f))
                        PlayerSheetHandle({ sheet = PlayerSheetTab.UP_NEXT }, Modifier.padding(bottom = 8.dp))
                    }
                }
            }
        }
    }
    sheet?.let { PlayerPeekSheet(vm, ep, it) { sheet = null } }
}

@Composable private fun PlayerArtwork(url: String?, size: androidx.compose.ui.unit.Dp) {
    PodcastCover(url, Modifier.size(size).shadow(16.dp, MaterialTheme.shapes.large), MaterialTheme.shapes.large)
}

/** Chapter starts from the current, valid episode analysis; empty without one. */
@Composable private fun rememberChapterStarts(vm: AppViewModel, ep: EpisodeEntity): List<Long> {
    val analyses by vm.analyses.collectAsStateWithLifecycle()
    val transcripts by vm.transcripts.collectAsStateWithLifecycle()
    val analysis = analyses.firstOrNull { it.episodeId == ep.id }
    val transcript = transcripts.firstOrNull { it.episodeId == ep.id }
    // The same validity rule as the insights page: a succeeded analysis of the current transcript of this audio.
    val valid = transcript?.state == "ready" && transcript.fingerprint == ep.mediaFingerprint &&
        analysis?.state == "succeeded" && analysis.revision == transcript.revision
    val json = analysis?.chaptersJson.takeIf { valid }
    return remember(json) { json?.let(::chapterStarts) ?: emptyList() }
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

@Composable private fun QueuePage(vm: AppViewModel, episodes: List<EpisodeEntity>, podcasts: List<PodcastEntity>, player: PlaybackState, snackbar: SnackbarHostState) {
    QueueContent(episodes, podcasts, player, { vm.root("library") }, { vm.open("episode:$it") },
        { vm.play(it) }, vm::toggle, vm::moveQueue, vm::clearQueue, vm::removeQueue, QueueUndo(snackbar, vm::enqueue))
}

/**
 * Removing with Undo (proposal 4): the screen's snackbar host and the enqueue action that re-inserts an
 * episode before single-step moves return it to its index. Without it, rows have no swipe gesture.
 */
internal class QueueUndo(val snackbar: SnackbarHostState, val enqueue: (String, Boolean) -> Unit)

/**
 * The queue (proposal 5.5): "Now playing", then "Up next" with its count and time left, then the episodes
 * already played in this playlist. Up next rows reorder by their drag handle and, with [undo], swipe left to
 * remove. Every edit resolves the episode by id through [onMove], [onRemove] and the undo's enqueue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun QueueContent(
    episodes: List<EpisodeEntity>, podcasts: List<PodcastEntity>, player: PlaybackState,
    onBrowse: () -> Unit, onOpen: (String) -> Unit, onPlay: (String) -> Unit, onToggle: () -> Unit,
    onMove: (String, Int) -> Unit, onClear: () -> Unit, onRemove: (String) -> Unit = {}, undo: QueueUndo? = null
) {
    // A changed playlist needs a fresh confirmation, rather than clearing unseen new items.
    var confirmClear by rememberSaveable(player.queue) { mutableStateOf(false) }
    // Remembered before the empty state returns, so an Undo offered for the last removal survives it.
    val scope = rememberCoroutineScope()
    val latestQueue by rememberUpdatedState(player.queue)
    val latestCurrent by rememberUpdatedState(player.episodeId)
    val list = rememberLazyListState()
    val drag = remember(list) { QueueDrag(list) }
    val restored = remember { mutableStateMapOf<String, Int>() }
    if (player.queue.isEmpty()) {
        Empty("Your queue is clear", "Use Play next or Add to queue on an episode.", "Browse library", onBrowse)
        return
    }
    if (confirmClear) QueueClearSheet(onDismiss = { confirmClear = false }, onConfirm = { confirmClear = false; onClear() })
    val byId = remember(episodes) { episodes.associateBy { it.id } }
    val shows = remember(podcasts) { podcasts.associateBy { it.id } }
    val queue = player.queue
    val currentIndex = queue.indexOf(player.episodeId)
    val nextStart = currentIndex + 1
    val actualNext = remember(queue, nextStart) { queue.drop(nextStart) }
    val upNext = drag.order(actualNext)
    LaunchedEffect(actualNext, drag.pending) { drag.settle(actualNext) }
    val remaining = queueRemainingMs(upNext.mapNotNull { byId[it] }, player)
    val latestNext by rememberUpdatedState(actualNext)
    // The dropped position is applied to the playlist as it is now, by id, one step at a time.
    fun drop(id: String, order: List<String>) {
        val start = latestQueue.indexOf(latestCurrent) + 1
        queueMoveSteps(latestQueue, id, start + order.indexOf(id)).forEach { onMove(id, it) }
    }
    fun remove(id: String) {
        val index = latestQueue.indexOf(id)
        onRemove(id)
        // Removing the playing episode moves playback on, which Undo could not reverse.
        if (undo == null || index < 0 || id == latestCurrent) return
        undo.snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            val title = byId[id]?.title
            val result = undo.snackbar.showSnackbar(if (title != null) "Removed “$title”" else "Removed from queue", actionLabel = "Undo",
                duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) {
                // A fading-out row can be reused when its id returns; a new generation gives it a fresh swipe state.
                restored[id] = (restored[id] ?: 0) + 1
                val plan = queueRestorePlan(latestQueue, latestCurrent, id, index)
                undo.enqueue(id, plan.next)
                plan.steps.forEach { onMove(id, it) }
            }
        }
    }
    @Composable fun row(index: Int, id: String, reorderable: Boolean) = byId[id]?.let { ep ->
        val current = id == player.episodeId
        SwipeToRemove(enabled = undo != null && !current, generation = restored[id] ?: 0, onRemove = { remove(id) }) {
            QueueEpisodeCard(ep, shows[ep.podcastId], player, index > 0, index < queue.lastIndex, onOpen, onPlay, onToggle, onMove, ::remove,
                dragHandle = if (reorderable) ({ QueueDragHandle(drag, id, { latestNext }, ::drop) }) else null)
        }
    }
    LazyColumn(Modifier.testTag("queue-list"), state = list, contentPadding = PaddingValues(bottom = 20.dp)) {
        val clear = @Composable { TextButton(onClick = { confirmClear = true }, modifier = Modifier.semantics { contentDescription = "Clear queue" }) { Text("Clear") } }
        if (currentIndex >= 0) {
            item(key = "queue-now") { QueueHeading("Now playing", clear) }
            item(key = queue[currentIndex]) {
                Box(Modifier.padding(horizontal = DistillSpacing.sm)) { row(currentIndex, queue[currentIndex], reorderable = false) }
            }
        }
        item(key = "queue-next") { QueueHeading(upNextHeading(upNext.size, remaining), clear.takeIf { currentIndex < 0 }) }
        if (upNext.isEmpty()) item(key = "queue-next-empty") {
            Column(Modifier.padding(horizontal = DistillSpacing.gutter), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Nothing up next", style = MaterialTheme.typography.titleSmall)
                Text("Use Play next or Add to queue on an episode.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        else itemsIndexed(upNext, key = { _, id -> id }) { offset, id ->
            val dragged = drag.dragging == id
            Column(Modifier.zIndex(if (dragged) 1f else 0f).then(if (dragged) Modifier.graphicsLayer { translationY = drag.displacement(id) } else Modifier.animateItem())) {
                // Lines up with the text after the drag handle and artwork.
                if (offset > 0) EpisodeRowDivider(inset = 48.dp + 10.dp + 48.dp + 10.dp)
                row(nextStart + offset, id, reorderable = true)
            }
        }
        if (currentIndex > 0) {
            item(key = "queue-history") { QueueHeading("History", null) }
            items(currentIndex, key = { queue[it] }) { row(it, queue[it], reorderable = false) }
        }
    }
}

@Composable private fun QueueHeading(title: String, action: (@Composable () -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = DistillSpacing.gutter, end = DistillSpacing.xs, top = DistillSpacing.sm),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        action?.invoke()
    }
}

/** Clearing stops playback, so it is confirmed in a sheet (proposal 4) with the previous dialog's wording. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun QueueClearSheet(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = DistillSpacing.gutter, end = DistillSpacing.gutter, bottom = DistillSpacing.section),
            verticalArrangement = Arrangement.spacedBy(DistillSpacing.item)) {
            Text("Clear queue and stop playback?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("Remove all episodes from the queue, including the current episode. Your listening progress and downloaded files are kept.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = DistillSpacing.sm), horizontalArrangement = Arrangement.spacedBy(DistillSpacing.sm, Alignment.End)) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = onConfirm) { Text("Clear and stop") }
            }
        }
    }
}

/** Swipe left to remove (proposal 5.5); the row stays a plain row when removal has no Undo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SwipeToRemove(enabled: Boolean, generation: Int, onRemove: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val state = key(generation) { rememberSwipeToDismissBoxState() }
    val latestRemove by rememberUpdatedState(onRemove)
    LaunchedEffect(state.currentValue) { if (state.currentValue == SwipeToDismissBoxValue.EndToStart) latestRemove() }
    SwipeToDismissBox(state, enableDismissFromStartToEnd = false, backgroundContent = {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = DistillSpacing.section),
            horizontalArrangement = Arrangement.spacedBy(DistillSpacing.sm, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Text("Remove", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer)
        }
    }) { content() }
}

/**
 * Drag-to-reorder for Up next. While a finger is down only a local [preview] order changes; the drop then
 * issues single-step moves for the dragged id ([queueMoveSteps]), and [pending] keeps showing the dropped
 * order until the playlist catches up, so rows don't jump back between those steps.
 */
@Stable private class QueueDrag(private val list: androidx.compose.foundation.lazy.LazyListState) {
    var dragging by mutableStateOf<String?>(null); private set
    var pending by mutableStateOf<List<String>?>(null); private set
    private var preview by mutableStateOf<List<String>?>(null)
    private var startOffset = 0
    private var delta by mutableFloatStateOf(0f)

    fun order(actual: List<String>): List<String> =
        preview ?: pending?.takeIf { it.size == actual.size && it.toSet() == actual.toSet() } ?: actual

    suspend fun settle(actual: List<String>) {
        val waiting = pending ?: return
        if (waiting == actual) pending = null
        else { kotlinx.coroutines.delay(1500); pending = null }
    }

    fun start(id: String, actual: List<String>) {
        preview = order(actual); dragging = id; delta = 0f
        startOffset = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset ?: 0
    }

    fun drag(by: Float) {
        val id = dragging ?: return
        val order = preview ?: return
        delta += by
        val items = list.layoutInfo.visibleItemsInfo
        val item = items.firstOrNull { it.key == id } ?: return
        val centre = startOffset + delta + item.size / 2f
        val from = order.indexOf(id)
        // The layout can lag a swap by a frame; a target whose laid-out side disagrees with the preview is stale.
        val target = items.firstOrNull {
            it.key != id && it.key in order && centre >= it.offset && centre <= it.offset + it.size &&
                (it.offset > item.offset) == (order.indexOf(it.key) > from)
        } ?: return
        preview = order.moved(from, order.indexOf(target.key))
    }

    /** How far the dragged row is drawn from its laid-out slot, so it follows the finger. */
    fun displacement(id: String): Float =
        startOffset + delta - (list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset ?: startOffset)

    /** The dragged id and its dropped Up next order, or null when cancelled. */
    fun end(commit: Boolean): Pair<String, List<String>>? {
        val id = dragging; val order = preview
        dragging = null; preview = null; delta = 0f
        if (!commit || id == null || order == null) return null
        pending = order
        return id to order
    }
}

/** The drag handle. It has no TalkBack role: the row's Move up and Move down actions cover reordering. */
@Composable private fun QueueDragHandle(drag: QueueDrag, id: String, upNext: () -> List<String>, onDrop: (String, List<String>) -> Unit) {
    val latestDrop by rememberUpdatedState(onDrop)
    Box(Modifier.size(48.dp).testTag("queue-drag-$id").pointerInput(id) {
        detectVerticalDragGestures(
            onDragStart = { drag.start(id, upNext()) },
            onDragEnd = { drag.end(commit = true)?.let { (dragged, order) -> latestDrop(dragged, order) } },
            onDragCancel = { drag.end(commit = false) },
        ) { change, amount -> change.consume(); drag.drag(amount) }
    }, contentAlignment = Alignment.Center) {
        Icon(Icons.Default.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun QueueEpisodeCard(ep: EpisodeEntity, podcast: PodcastEntity?, player: PlaybackState, canMoveUp: Boolean, canMoveDown: Boolean,
    onOpen: (String) -> Unit, onPlay: (String) -> Unit, onToggle: () -> Unit, onMove: (String, Int) -> Unit, onRemove: (String) -> Unit,
    dragHandle: (@Composable () -> Unit)? = null) {
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
    // Flat rows on the page background (opaque, so a swipe reveals its action only beside the row);
    // the playing episode is an accent-container card.
    Surface(onClick = { onOpen(ep.id) }, modifier = Modifier.fillMaxWidth().testTag("queue-row-${ep.id}").semantics { customActions = actions },
        shape = if (current) MaterialTheme.shapes.medium else RectangleShape,
        color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
        contentColor = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
        @Composable fun controls() = Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (current && player.playing) onToggle() else onPlay(ep.id) }) { Icon(if (current && player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "${if (current && player.playing) "Pause" else "Play"} ${ep.title}") }
            QueueRowMenu(ep, canMoveUp, canMoveDown, onMove, onRemove)
        }
        val muted = if (current) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f) else MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.padding(start = if (dragHandle != null) 0.dp else DistillSpacing.item, top = 10.dp, bottom = if (stacked) 2.dp else 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                dragHandle?.invoke()
                Cover(ep.imageUrl ?: podcast?.imageUrl, Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(end = if (stacked) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(ep.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(podcast?.title ?: "Podcast", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = muted)
                    Text(queueTimeLabel(ep, player), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = muted)
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
    if(section=="overview") {SettingsOverview(account.connected,context.resources.getBoolean(dev.distillpod.android.R.bool.voice_enabled),rememberSettingsStatus(vm,prefs,version),vm::open);return}
    if(section=="diagnostics") {DiagnosticsSettings(vm);return}
    LaunchedEffect(account.connected,section) { if (account.connected && section=="ai") vm.graph.account.refreshModels() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if(section=="sync") SubscriptionSyncSettings(vm) { vm.open("backup") }
        if(section=="voice") VoiceSettings(vm)
        if(section=="playback") {
            PlaybackHeadphoneSettings(vm, prefs)
        }
        if(section=="ai") {
        Text(if (account.connected) "Connected" else "Not connected", color = if (account.connected) MaterialTheme.extras.success else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(account.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ShortExplanation("AI actions send selected text to OpenAI and use your plan allowance.", "AI data use",
            listOf("AI actions send selected transcript text, questions or interests to OpenAI. Suggestions share followed-show titles only if you select that option. Audio transcription runs on your phone. Requests use your plan allowance."))
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
        SettingsGroupCard {
        SettingSwitch("Transcribe when playing", if (prefs.transcribeOnPlayback) "Prepare transcripts for episodes you play." else "Existing transcript jobs keep running. Use Pause transcript to stop one.", prefs.transcribeOnPlayback,
            learnMore = "Automatically prepare transcripts for episodes you play. Uses your download and processing settings. Turning this off leaves existing jobs running; use Pause transcript to stop one.") { vm.flag("transcribeOnPlayback", it) }
        SettingsDivider()
        SettingSwitch("Look for existing online transcripts", "Checks PodcastTranscript.ai first; sends only the episode title.", prefs.onlineTranscripts,
            learnMore = "Before Whisper, search PodcastTranscript.ai for public shows. Sends the episode title; never uploads audio or starts cloud transcription. Off for unapproved private feeds.") { vm.flag("onlineTranscripts", it) }
        SettingsDivider()
        SettingSwitch("Only transcribe while charging", "Pause new transcription chunks until power is connected.", prefs.chargingOnly) { vm.flag("charging", it) }
        }
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
        SettingsGroupCard { SettingSwitch("Wi-Fi / unmetered downloads only", "Applies to queued audio, model downloads and transcript preparation that needs internet. Transfers already in progress continue; retries use your new setting.", prefs.wifiOnly) { vm.flag("wifi", it) } }
        StorageSettings(vm, prefs, episodes, models)
        }
        if(section=="library") {
        SettingsGroupCard { SettingSwitch("Refresh subscriptions", "Check your followed feeds periodically when Android permits.", prefs.refresh) { vm.flag("refresh", it) } }
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
/** A settings ListItem with a trailing switch; [learnMore] keeps the full explanation behind a sheet. */
@Composable private fun SettingSwitch(title: String, detail: String, checked: Boolean, learnMore: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Column {
            Text(detail, style = MaterialTheme.typography.bodySmall)
            if (learnMore != null) LearnMoreLink(title, listOf(learnMore))
        } },
        trailingContent = { Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title }) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent, supportingColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.fillMaxWidth())
}
