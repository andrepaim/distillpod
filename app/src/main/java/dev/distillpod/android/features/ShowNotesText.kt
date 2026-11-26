package dev.distillpod.android.features

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.core.net.toUri
import dev.distillpod.core.ShowNotes

/**
 * Stored show notes (#308) with their source links and bare http(s) addresses as underlined accent links.
 * Each is a [LinkAnnotation.Url], so TalkBack lists it as a link; [onLink] receives the exact source address.
 */
@Composable internal fun ShowNotesText(stored: String, onLink: (String) -> Unit, modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current, maxLines: Int = Int.MAX_VALUE, onTextLayout: (TextLayoutResult) -> Unit = {}) {
    val open by rememberUpdatedState(onLink)
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(stored, linkColor) {
        val links = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        buildAnnotatedString {
            ShowNotes.parts(stored).forEach { part ->
                val url = part.url
                if (url == null) append(part.text) else withLink(LinkAnnotation.Url(url, links) { open(url) }) { append(part.text) }
            }
        }
    }
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = style, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis, onTextLayout = onTextLayout)
}

/** Opens a show-note address in a browser; false, instead of a crash, when no app on the phone can open it. */
internal fun openExternalLink(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
