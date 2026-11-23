package dev.distillpod.android.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.ui.theme.DistillSpacing

/**
 * Home's "Your shows" row of two or more followed shows, in [homeShows] order (#276). Lazy keys keep the first
 * visible show in place when the order changes; a row still at its start shows the new first show instead, such
 * as one just pinned in Shows or with a newer episode.
 */
@Composable internal fun HomeShowRail(shows: List<PodcastEntity>, onOpen: (PodcastEntity) -> Unit, modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState()) {
    val order = remember(shows) { shows.map { it.id } }
    remember(order) {
        // Read without observing, so scrolling the row does not recompose it.
        Snapshot.withoutReadObservation { if (state.firstVisibleItemIndex == 0) state.requestScrollToItem(0) }
        order
    }
    LazyRow(modifier, state = state, contentPadding = PaddingValues(horizontal = DistillSpacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(shows, key = { it.id }) { p -> Column(Modifier.width(76.dp).clickable { onOpen(p) }) {
            PodcastCover(p.imageUrl, Modifier.size(76.dp))
            Text(p.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
        } }
    }
}
