package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.distillpod.android.ui.theme.DistillSpacing

/*
 * Episode insights before they exist (#203, proposal 5.5). The list names only what
 * AnalysisEntity stores today: a summary, chapters and ad ranges. There are no key quotes.
 */

private val insightItems = listOf(
    Triple(Icons.AutoMirrored.Filled.Notes, "Summary", "The main points in a few paragraphs"),
    Triple(Icons.AutoMirrored.Filled.FormatListBulleted, "Chapters", "Jump to each topic from its start"),
    Triple(Icons.Default.Campaign, "Ad markers", "Suggested ad ranges you can review and skip"),
)

/** A card listing what Generate insights produces, with the episode length when it is known. */
@Composable internal fun InsightsPreview(durationMs: Long, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(vertical = DistillSpacing.item)) {
            Text("What you'll get", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = DistillSpacing.gutter).semantics { heading() })
            insightItems.forEach { (icon, title, detail) -> InsightPreviewRow(icon, title, detail) }
            insightScope(durationMs)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = DistillSpacing.gutter))
            }
        }
    }
}

@Composable private fun InsightPreviewRow(icon: ImageVector, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = DistillSpacing.gutter, vertical = DistillSpacing.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DistillSpacing.item)) {
        SettingsIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun insightScope(durationMs: Long): String? {
    val minutes = ((durationMs + 30_000) / 60_000).takeIf { durationMs > 0 && it > 0 } ?: return null
    val length = if (minutes >= 60) "${minutes / 60} h" + (if (minutes % 60 > 0) " ${minutes % 60} min" else "") else "$minutes min"
    return "From an episode of about $length."
}

/** The analysis privacy and cost note as one muted line; the original sentences are in the sheet. */
@Composable internal fun InsightsPrivacyLine() = ShortExplanation(
    "Sends transcript parts to ChatGPT · uses your plan allowance.", "Episode insights and ChatGPT",
    listOf("Analysis sends transcript parts to ChatGPT and uses your plan allowance. Completed parts are reused after an interruption."))
