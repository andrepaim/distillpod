package dev.distillpod.android.features

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.distillpod.android.data.PodcastCandidate
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.core.YouTubeUrl
import dev.distillpod.android.ui.theme.extras

@Composable internal fun SearchPage(vm: AppViewModel, podcasts: List<PodcastEntity>) {
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.search.collectAsStateWithLifecycle()
    val recent by vm.recentSearches.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val library by vm.librarySearch.collectAsStateWithLifecycle()
    SearchScreen(query, state, recent, podcasts, busy, vm::query,
        { term -> vm.query(term); vm.search() }, vm::clearSearches, vm::subscribe,
        { vm.open("podcast:$it") }, { vm.open("recommendations") }, vm::retrySearch, library, vm::open)
}

private val SEARCH_FORMATS = listOf("All", "Audio", "Video")

/** Extends content [by] into the horizontal padding on each side. */
private fun Modifier.bleed(by: androidx.compose.ui.unit.Dp) = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-by.roundToPx(), 0) }
}

private data class SearchTopic(val name: String, val icon: ImageVector)
private val topics = listOf(SearchTopic("Technology", Icons.Default.Memory), SearchTopic("Science", Icons.Default.Science),
    SearchTopic("History", Icons.Default.HistoryEdu), SearchTopic("Business", Icons.Default.TrendingUp),
    SearchTopic("Culture", Icons.Default.Palette), SearchTopic("Health", Icons.Default.SelfImprovement))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun SearchScreen(query: String, state: SearchState, recent: List<String>, podcasts: List<PodcastEntity>, busy: Set<String>,
    onQuery: (String) -> Unit, onSearch: (String) -> Unit, onClearRecent: () -> Unit, onFollow: (String) -> Unit,
    onOpenPodcast: (String) -> Unit, onExplore: () -> Unit, onRetryProvider: (String) -> Unit = {},
    library: LibrarySearchState = LibrarySearchState(), onOpenRoute: (String) -> Unit = {}) {
    var format by rememberSaveable { mutableStateOf("All") }
    fun accepts(provider: String) = format == "All" || (provider == "youtube") == (format == "Video")
    val librarySections = remember(library.matches, format) { librarySections(library.matches, format) }
    // Sections showing all their rows, for the current library search only.
    var expanded by rememberSaveable(library.terms?.joinToString(" ")) { mutableStateOf("") }
    val pending = state.loadingProviders.filter(::accepts).toSet()
    val displayed = state.copy(
        results = state.results.filter { format == "All" || (YouTubeUrl.source(it.feedUrl) != null) == (format == "Video") },
        loadingProviders = pending,
        failedProviders = state.failedProviders.filter(::accepts).toSet(),
        loading = pending.isNotEmpty() || (state.loading && state.loadingProviders.isEmpty()))
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var preview by remember { mutableStateOf<PodcastCandidate?>(null) }
    var rss by rememberSaveable { mutableStateOf(false) }
    var rssDraft by rememberSaveable { mutableStateOf("") }
    val adding = busy.any { it.startsWith("subscribe:") }
    val list = rememberLazyListState()
    // Back from a result keeps the list where it was; only a new search or filter starts at the top.
    val listKey = "${displayed.submittedQuery}|$format|${library.terms}"
    var scrolledFor by rememberSaveable { mutableStateOf(listKey) }
    LaunchedEffect(listKey) { if (scrolledFor != listKey) { list.scrollToItem(0); scrolledFor = listKey } }
    fun existing(candidate: PodcastCandidate) = podcasts.firstOrNull { it.subscribed && (it.feedUrl == candidate.feedUrl || it.originalFeedUrl == candidate.feedUrl) }
    fun submit(term: String) {
        if (term.trim().length < 2) return
        keyboard?.hide(); focus.clearFocus()
        if (isFeedAddress(term)) { rssDraft = term; rss = true } else onSearch(term)
    }
    fun follow(candidate: PodcastCandidate) { preview = null; onFollow(candidate.feedUrl) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // An M3 search bar field (proposal 5.5): the keyboard's search key submits, so there is no separate submit button.
            Surface(shape = SearchBarDefaults.inputFieldShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                SearchBarDefaults.InputField(query = query, onQueryChange = onQuery, onSearch = { submit(it) }, expanded = false, onExpandedChange = {},
                    placeholder = { Text("Search your library or find shows", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isFeedAddress(query)) IconButton(onClick = { submit(query) }) { Icon(Icons.Default.RssFeed, "Preview RSS feed", tint = MaterialTheme.colorScheme.primary) }
                            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "Clear search") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search your library and podcasts" })
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("search-format")) {
                SEARCH_FORMATS.forEachIndexed { index, option ->
                    SegmentedButton(selected = format == option, onClick = { format = option }, shape = SegmentedButtonDefaults.itemShape(index, SEARCH_FORMATS.size),
                        icon = {}, label = { Text(option, maxLines = 1) })
                }
            }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).testTag("Podcast search results"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (library.terms != null) librarySearchResults(library, query, librarySections, expanded.split(' ').toSet(),
                { expanded = "$expanded $it".trim() }, onOpenRoute)
            when {
                displayed.loading && displayed.results.isEmpty() -> item {
                    SearchMessage("Searching shows", "Looking for “${displayed.submittedQuery.orEmpty()}”…")
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                }
                displayed.error != null -> item {
                    SearchMessage("Search unavailable", displayed.error, "Try again", { submit(displayed.submittedQuery ?: query) })
                }
                displayed.submittedQuery != null && displayed.results.isEmpty() -> item {
                    SearchMessage(if (format == "Video") "No video shows found" else if (format == "Audio") "No audio podcasts found" else "No podcasts found", "No results for “${displayed.submittedQuery}”. Try a show name, creator or a broader subject.")
                    TextButton(onClick = { onQuery("") }) { Text("Browse topics") }
                }
                displayed.results.isNotEmpty() -> {
                    item { SectionHeading("Podcast directory", "${displayed.results.size} shows for “${displayed.submittedQuery ?: query}”") }
                    item { Text("TOP RESULT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary) }
                    item(key = "top:${displayed.results.first().feedUrl}") {
                        val candidate = displayed.results.first()
                        SearchResult(candidate, existing(candidate), true, adding, "subscribe:${candidate.feedUrl}" in busy,
                            { preview = candidate }, { follow(candidate) }, onOpenPodcast)
                    }
                    if (displayed.results.size > 1) item { SectionHeading("More shows") }
                    items(displayed.results.drop(1), key = { it.feedUrl }) { candidate -> SearchResult(candidate, existing(candidate), false, adding,
                        "subscribe:${candidate.feedUrl}" in busy, { preview = candidate }, { follow(candidate) }, onOpenPodcast) }
                }
                library.terms != null -> item(key = DIRECTORY_PROMPT_KEY) { DirectorySearchPrompt(query, !adding) { submit(query) } }
                else -> {
                    if (recent.isNotEmpty()) {
                        item { SectionHeading("Recent searches", action = "Clear", onAction = onClearRecent) }
                        // One group: the list's 14 dp item gap would otherwise split the rows apart. The rows bleed into the
                        // page gutter so their icons line up with the headings.
                        item(key = "recent-searches") { Column(Modifier.bleed(16.dp)) { recent.forEach { term ->
                            ListItem(headlineContent = { Text(term, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                trailingContent = { Icon(Icons.Default.NorthWest, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                                modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = "Search again") { submit(term) }
                                    .semantics { contentDescription = "Search again for $term" })
                        } } }
                    }
                    item { SectionHeading("Explore topics", "Start with something you're curious about") }
                    items(topics.chunked(2)) { row ->
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { topic ->
                                Surface(onClick = { submit(topic.name) }, color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    Column(Modifier.padding(16.dp).heightIn(min = 64.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(topic.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                                        Text(topic.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        OutlinedCard(onClick = onExplore, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .5f))) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.secondary)
                                Column(Modifier.weight(1f)) { Text("Explore by interest", style = MaterialTheme.typography.titleSmall); Text("Describe what you want to learn", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Icon(Icons.Default.ChevronRight, null)
                            }
                        }
                    }
                }
            }
            displayed.loadingProviders.forEach { provider -> item(key = "loading:$provider") {
                Text("Searching ${if (provider == "youtube") "YouTube" else "Apple Podcasts"}…", style = MaterialTheme.typography.bodySmall)
            } }
            displayed.failedProviders.forEach { provider -> item(key = "failed:$provider") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (provider == "youtube") "YouTube" else "Apple Podcasts"} is unavailable.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onRetryProvider(provider) }, enabled = !displayed.loading) { Text("Retry") }
                }
            } }
            item {
                TextButton(onClick = { keyboard?.hide(); rssDraft = query.takeIf(::isFeedAddress) ?: ""; rss = true }) {
                    Icon(Icons.Default.RssFeed, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add RSS feed")
                }
            }
        }
    }
    preview?.let { candidate ->
        val followed = existing(candidate)
        ModalBottomSheet(onDismissRequest = { preview = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SearchArtwork(candidate, Modifier.size(140.dp).align(Alignment.CenterHorizontally))
                Text(candidate.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (candidate.author.isNotBlank()) Text(candidate.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (candidate.category.isNotBlank()) StatusLabel(candidate.category)
                Text(if (followed != null) "Already in your library. Open the show to browse its episodes." else "Follow this show to browse its episodes and keep it in your library.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { if (followed != null) { preview = null; onOpenPodcast(followed.id) } else follow(candidate) }, enabled = !adding,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { contentDescription = if (followed != null) "Open previewed podcast" else "Follow previewed podcast" }) { Text(if (followed != null) "Open podcast" else "Follow podcast") }
            }
        }
    }
    if (rss) ModalBottomSheet(onDismissRequest = { rss = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.RssFeed, null, tint = MaterialTheme.colorScheme.secondary)
            Text("Add a podcast feed", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Paste a public RSS feed, YouTube channel, playlist or video link.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(rssDraft, { rssDraft = it.take(2048) }, label = { Text("RSS or YouTube URL") }, modifier = Modifier.fillMaxWidth().testTag("Feed address"), maxLines = 3)
            Button(onClick = { rss = false; onQuery(rssDraft.trim()); onFollow(rssDraft.trim()) }, enabled = isFeedAddress(rssDraft) && !adding,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Add feed") }
        }
    }
}

@Composable private fun SearchMessage(title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}

@Composable private fun SearchArtwork(candidate: PodcastCandidate, modifier: Modifier) {
    Surface(modifier.testTag("Search artwork ${candidate.title}"), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            val initials = candidate.title.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
            Text(initials.ifBlank { "DP" }, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            if (!candidate.imageUrl.isNullOrBlank()) AsyncImage(candidate.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SearchResult(candidate: PodcastCandidate, followed: PodcastEntity?, featured: Boolean, adding: Boolean, thisAdding: Boolean,
    onPreview: () -> Unit, onFollow: () -> Unit, onOpen: (String) -> Unit) {
    Card(onClick = onPreview, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = if (featured) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f) else MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Preview ${candidate.title}" }) {
        Column(Modifier.padding(if (featured) 16.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchArtwork(candidate, Modifier.size(if (featured) 88.dp else 64.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(candidate.title, maxLines = if (featured) 3 else 2, overflow = TextOverflow.Ellipsis, style = if (featured) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (candidate.author.isNotBlank()) Text(candidate.author, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!featured && candidate.category.isNotBlank()) Text(candidate.category, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    if (followed != null) Text("Following", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extras.success)
                }
                if (!featured) IconButton(onClick = { if (followed != null) onOpen(followed.id) else onFollow() }, enabled = !adding,
                    modifier = Modifier.semantics { contentDescription = if (followed != null) "Open ${candidate.title}" else "Follow ${candidate.title}" }) {
                    if (thisAdding) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(if (followed != null) Icons.Default.CheckCircle else Icons.Default.AddCircleOutline, null, tint = if (followed != null) MaterialTheme.extras.success else MaterialTheme.colorScheme.primary)
                }
            }
            if (featured) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = { if (followed != null) onOpen(followed.id) else onFollow() }, enabled = !adding) {
                    if (thisAdding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(if (followed != null) Icons.Default.Check else Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text(if (thisAdding) "Adding podcast…" else if (followed != null) "Open podcast" else "Follow podcast")
                }
                if (candidate.category.isNotBlank()) StatusLabel(candidate.category)
            }
        }
    }
}
