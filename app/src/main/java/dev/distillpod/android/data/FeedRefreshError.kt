package dev.distillpod.android.data

import java.io.IOException
import org.xml.sax.SAXException

/** A feed server answered with a non-success status. */
class FeedHttpException(val code: Int) : IllegalStateException("Feed refresh failed (HTTP $code)")

/**
 * A short reason for a failed feed refresh, shown with the show's name in the Library (#146).
 * Raw exception text can include URLs or provider details, so only known failure kinds are described.
 */
fun feedRefreshError(error: Throwable): String = when (error) {
    is FeedHttpException -> when (error.code) {
        404, 410 -> "The feed address no longer works (HTTP ${error.code})."
        401, 403 -> "The feed refused access (HTTP ${error.code})."
        in 500..599 -> "The feed server had a problem (HTTP ${error.code}). Try again later."
        else -> "The feed server answered HTTP ${error.code}."
    }
    is SAXException -> "The feed couldn't be read. It may not be a valid podcast feed."
    is IOException -> "Couldn't reach the feed. Check your connection and try again."
    else -> FEED_REFRESH_FAILED
}

private const val FEED_REFRESH_FAILED = "The feed couldn't be refreshed."
/** Stored by earlier versions; its second sentence repeats the Library notice. */
private const val LEGACY_FEED_REFRESH_FAILED = "Refresh failed. Saved episodes are still available."

/** The stored reason as shown to the user. */
fun feedErrorText(stored: String): String = if (stored == LEGACY_FEED_REFRESH_FAILED) FEED_REFRESH_FAILED else stored
