package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.distillpod.android.data.*
import dev.distillpod.android.ui.theme.TextButton
import dev.distillpod.android.ui.theme.FilterChip

@Composable internal fun ShowsPage(vm: AppViewModel) {
    val library by vm.shows.collectAsState()
    val state = library
    if (state == null) {
        Text("Loading shows…", Modifier.padding(20.dp))
    } else {
        ShowsContent(state, vm::showFormat, vm::showSort, vm::pinShow,
            onOpen = { vm.open("podcast:$it") }, onFind = { vm.root("search") })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ShowsContent(
    library: ShowLibraryState,
    onFormat: (ShowFormat) -> Unit,
    onSort: (ShowSort) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onFind: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    val preferences = library.preferences
    val shows = remember(library, query) { selectShows(library.shows, query, preferences) }
    val grid = rememberLazyGridState()
    LaunchedEffect(query, preferences.format, preferences.sort) { grid.scrollToItem(0) }
    LazyVerticalGrid(
        state = grid, columns = GridCells.Adaptive(144.dp),
        modifier = Modifier.fillMaxSize().testTag("shows-grid"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
            LocalSearch(query, { query = it }, "Search your shows", "Clear show search")
        }
        item(key = "format", span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShowFormat.entries.forEach { format ->
                    FilterChip(selected = preferences.format == format, onClick = { onFormat(format) },
                        label = { Text(format.label) }, modifier = Modifier.testTag("show-format-${format.name}"))
                }
            }
        }
        item(key = "sort", span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${shows.size} ${if (shows.size == 1) "show" else "shows"}",
                    modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    TextButton(onClick = { sortMenu = true }, modifier = Modifier.testTag("show-sort")
                        .semantics { contentDescription = "Sort shows: ${preferences.sort.label}" }) {
                        Icon(Icons.Default.Sort, null)
                        Spacer(Modifier.width(8.dp))
                        Text(preferences.sort.label)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        ShowSort.entries.forEach { sort ->
                            DropdownMenuItem(text = { Text(sort.label) },
                                leadingIcon = { RadioButton(selected = preferences.sort == sort, onClick = null) },
                                modifier = Modifier.semantics { selected = preferences.sort == sort },
                                onClick = { sortMenu = false; onSort(sort) })
                        }
                    }
                }
            }
        }
        if (shows.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val noSubscriptions = library.shows.isEmpty()
                Text(if (noSubscriptions) "Follow your first show" else "No matching shows", style = MaterialTheme.typography.titleLarge)
                if (!noSubscriptions) Text("Try another format or clear your search.")
                TextButton(onClick = {
                    if (noSubscriptions) onFind() else { query = ""; onFormat(ShowFormat.ALL) }
                }) { Text(if (noSubscriptions) "Find shows" else "Reset filters") }
            }
        }
        items(shows, key = { it.show.id }, contentType = { "show" }) { row ->
            ShowCard(row, row.pinKey in preferences.pinnedFeeds,
                onPin = { onPin(row.pinKey, it) }, onOpen = { onOpen(row.show.id) })
        }
    }
}

@Composable private fun ShowCard(row: ShowSummary, pinned: Boolean, onPin: (Boolean) -> Unit, onOpen: () -> Unit) {
    val show = row.show
    Surface(onClick = onOpen, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("show-card-${show.id}")) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                PodcastCover(show.imageUrl, Modifier.fillMaxWidth().aspectRatio(1f))
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    IconToggleButton(checked = pinned, onCheckedChange = onPin) {
                        Icon(if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "${if (pinned) "Unpin" else "Pin"} ${show.title}",
                            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(row.formatLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(show.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (show.author.isNotBlank()) Text(show.author, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val latest = latestEpisodeLabel(row)
            Text(latest, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.episodeCount > 0) Text("${row.unfinishedCount} unfinished", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
