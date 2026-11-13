package dev.distillpod.android.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Library matches on the Search tab (#179), before any directory results. [expanded] holds the
 * sections showing more than their first rows; [sections] are already format-filtered.
 */
internal fun LazyListScope.librarySearchResults(state: LibrarySearchState, query: String, sections: List<LibrarySection>,
    expanded: Set<String>, onExpand: (String) -> Unit, onOpen: (String) -> Unit) {
    val matches = state.matches
    val terms = matches?.terms ?: state.terms.orEmpty()
    item(key = "library:heading") {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("In your library", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val detail = when {
                state.failed -> "Library search could not finish. Edit the search to try again."
                matches == null || state.loading && sections.isEmpty() -> "Searching your library…"
                sections.isEmpty() && !state.loading -> "Nothing in your library matches “${query.trim()}”."
                else -> "Results from your library stay on this phone."
            }
            Text(detail, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
                color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
    sections.forEach { section ->
        val open = section.key in expanded
        item(key = "library:${section.key}") {
            Text(section.title, Modifier.padding(top = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        items(visibleLibraryRows(section.rows, open), key = { "library:${section.key}:${rowKey(it)}" }) { row ->
            when (row) {
                is ShowMatch -> LibraryRow(row.title, row.author, row.imageUrl, "Open show") { onOpen("podcast:${row.id}") }
                is EpisodeMatch -> LibraryRow(row.title, listOfNotNull(row.showTitle, episodeDate(row.publishedAt).ifBlank { null }).joinToString(" · "),
                    row.imageUrl, "Open episode") { onOpen("episode:${row.id}") }
                is PassageMatch -> LibraryRow(row.episodeTitle, "${timestamp(row.startMs)} · ${row.matches} ${if (row.matches == 1) "match" else "matches"}",
                    row.imageUrl, "Open transcript at ${timestamp(row.startMs)}", highlighted(row.snippet, terms)) { onOpen(transcriptRoute(row.episodeId, row.startMs)) }
                is MomentMatch -> LibraryRow(row.text, listOfNotNull(row.episodeTitle, timestamp(row.startMs)).joinToString(" · "),
                    row.imageUrl, "Open saved moment", moment = true) { onOpen("capture:${row.id}") }
            }
        }
        if (section.rows.size > LIBRARY_SECTION_PREVIEW && !open) item(key = "library:${section.key}:more") {
            TextButton(onClick = { onExpand(section.key) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Show all ${section.rows.size} ${section.noun}") }
        }
        if (open && section.rows.size > LIBRARY_SECTION_CAP) item(key = "library:${section.key}:capped") {
            Text("Showing the first $LIBRARY_SECTION_CAP of ${section.rows.size}. Add a word to narrow the search.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun rowKey(row: Any) = when (row) {
    is ShowMatch -> row.id; is EpisodeMatch -> row.id; is PassageMatch -> row.episodeId; is MomentMatch -> row.id; else -> row.hashCode().toString()
}

/** Bold every occurrence of the search words in a passage. */
private fun highlighted(text: String, terms: List<String>): AnnotatedString = buildAnnotatedString {
    append(text)
    terms.forEach { term ->
        var at = text.indexOf(term, ignoreCase = true)
        while (term.isNotEmpty() && at >= 0) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), at, at + term.length)
            at = text.indexOf(term, at + term.length, ignoreCase = true)
        }
    }
}

@Composable private fun LibraryRow(title: String, detail: String, imageUrl: String?, action: String, passage: AnnotatedString? = null,
    moment: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = action, role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (moment) Surface(Modifier.size(48.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Bookmark, null, tint = MaterialTheme.colorScheme.secondary) }
        } else PodcastCover(imageUrl, Modifier.size(48.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, maxLines = if (moment) 3 else 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (passage != null) Text(passage, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (detail.isNotBlank()) Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Typing searches only the library; this sends the words to the public podcast directory. */
@Composable internal fun DirectorySearchPrompt(query: String, enabled: Boolean, onSearch: () -> Unit) {
    OutlinedCard(onClick = onSearch, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f)) {
                Text("Search the podcast directory for “${query.trim()}”", style = MaterialTheme.typography.titleSmall)
                Text("Find new shows and videos online", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}
