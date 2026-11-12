package dev.distillpod.android.features

import dev.distillpod.android.data.EpisodeDetailsState
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.core.YouTubeUrl
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Local publication day; the year is added outside the current year because back catalogs span years. */
internal fun episodeDate(ms: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()): String {
    if (ms <= 0) return ""
    val day = Instant.ofEpochMilli(ms).atZone(zone)
    val pattern = if (day.year == Instant.ofEpochMilli(now).atZone(zone).year) "dd MMM" else "dd MMM yyyy"
    return DateTimeFormatter.ofPattern(pattern, locale).format(day)
}

/** YouTube videos can have their own source metadata checked when a refresh could not supply it. */
internal fun EpisodeEntity.needsSourceDetails(): Boolean =
    YouTubeUrl.videoId(audioUrl) != null && (publishedAt <= 0 || description.isBlank())

/** Episode-page metadata. [note] explains a missing description; it never substitutes source text. */
internal data class EpisodeMetadataText(val date: String, val description: String?, val note: String?, val retry: Boolean)

internal fun episodeMetadataText(episode: EpisodeEntity, state: EpisodeDetailsState?, now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): EpisodeMetadataText {
    val pending = episode.needsSourceDetails()
    val checking = pending && (state == null || state == EpisodeDetailsState.LOADING)
    val failed = pending && state == EpisodeDetailsState.FAILED
    val date = when {
        episode.publishedAt > 0 -> episodeDate(episode.publishedAt, now, zone, locale)
        checking -> "Checking publication date…"
        else -> "Publication date unavailable"
    }
    val description = episode.description.takeIf(String::isNotBlank)
    val note = when {
        description != null -> null
        checking -> "Loading the original description from YouTube…"
        failed -> "Couldn't load details from YouTube."
        else -> "No description provided by the source."
    }
    return EpisodeMetadataText(date, description, note, failed)
}
