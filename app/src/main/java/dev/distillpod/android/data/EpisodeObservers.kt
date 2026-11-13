package dev.distillpod.android.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Room invalidates whole tables, so these queries still re-run after a progress checkpoint, but they
// read a few columns and emit only actual changes; the expensive work behind them does not re-run (#141).

/** The playing item's stored local file. */
internal fun LibraryDao.observeLocalSource(id: String): Flow<String?> = observeEpisodeLocalFile(id).distinctUntilChanged()

/** Downloaded episodes with a succeeded analysis, the candidates for reviewed ad skipping. */
internal fun LibraryDao.observeAdSourceRows(): Flow<List<AdSourceRow>> = observeAdSources().distinctUntilChanged()

/** Media-browser rows; last-played time changes them at most once a minute. */
internal fun LibraryDao.observeBrowseSummary(): Flow<List<BrowseRow>> =
    observeBrowseRows().map { rows -> rows.map { it.copy(lastPlayedAt = it.lastPlayedAt / 60_000) } }.distinctUntilChanged()
