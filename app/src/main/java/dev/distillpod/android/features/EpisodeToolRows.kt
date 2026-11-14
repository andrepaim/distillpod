package dev.distillpod.android.features

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.distillpod.android.data.EpisodeEntity
import dev.distillpod.android.ui.theme.DistillSpacing

/**
 * A grouped list (proposal section 4): an uppercase muted label over a surfaceContainerLow container
 * whose rows are separated by outlineVariant dividers. Callers place [EpisodeToolDivider]s between rows.
 */
@Composable internal fun EpisodeToolGroup(label: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DistillSpacing.sm)) {
        if (label != null) Text(label.uppercase(), Modifier.padding(start = DistillSpacing.xs).semantics { heading() },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.8.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

@Composable internal fun EpisodeToolDivider() =
    HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant)

/**
 * One navigational row: a leading icon in a 30 dp accentContainer square, status as supporting text and a
 * trailing chevron unless [trailing] supplies a menu or info button. The whole row is the touch target.
 */
@Composable internal fun EpisodeToolRow(title: String, supporting: String?, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClickLabel: String? = null, extra: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null) {
    val alpha = if (enabled) 1f else .38f
    val colors = MaterialTheme.colorScheme
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = if (supporting == null && extra == null) null else { {
            Column {
                supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                extra?.invoke(this)
            }
        } },
        leadingContent = {
            Box(Modifier.size(30.dp).background(colors.primaryContainer.copy(alpha = if (enabled) 1f else .5f), MaterialTheme.shapes.extraSmall),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = colors.onPrimaryContainer.copy(alpha = alpha))
            }
        },
        trailingContent = trailing ?: { Icon(Icons.Default.ChevronRight, null, tint = colors.onSurfaceVariant.copy(alpha = alpha)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = colors.onSurface.copy(alpha = alpha),
            supportingColor = colors.onSurfaceVariant.copy(alpha = alpha)),
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick),
    )
}

/** The episode page's transcript, insight and Ask entries (proposal 5.2). */
@Composable internal fun EpisodeToolsGroup(vm: AppViewModel, ep: EpisodeEntity) {
    EpisodeToolGroup("Explore") {
        TranscriptListItem(vm, ep)
        EpisodeToolDivider()
        EpisodeUnderstandingListItems(vm, ep)
    }
}
