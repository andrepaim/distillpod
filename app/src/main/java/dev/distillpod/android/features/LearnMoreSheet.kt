package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.distillpod.android.ui.theme.DistillSpacing

/**
 * Full disclosure text behind a short on-screen line (#203, proposal 2.6 "state over explanation").
 * The paragraphs are shown verbatim; nothing is summarised away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LearnMoreSheet(title: String, paragraphs: List<String>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = DistillSpacing.gutter, end = DistillSpacing.gutter, bottom = DistillSpacing.section),
            verticalArrangement = Arrangement.spacedBy(DistillSpacing.item)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** An inline "Learn more" link that opens [LearnMoreSheet]; TalkBack hears what it is about. */
@Composable internal fun LearnMoreLink(title: String, paragraphs: List<String>, modifier: Modifier = Modifier, label: String = "Learn more") {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        modifier = modifier.semantics { contentDescription = "$label about $title" }) { Text(label) }
    if (open) LearnMoreSheet(title, paragraphs) { open = false }
}

/** One short muted line followed by a Learn more link holding the original text. */
@Composable internal fun ShortExplanation(summary: String, title: String, paragraphs: List<String>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LearnMoreLink(title, paragraphs)
    }
}
