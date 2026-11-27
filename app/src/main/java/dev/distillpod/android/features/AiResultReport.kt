package dev.distillpod.android.features

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.distillpod.android.data.*
import dev.distillpod.android.ui.theme.TextButton
import org.json.JSONArray

/**
 * A model-written result the user wants to report (#324, Google Play's AI-generated content policy).
 * It holds only what a report may contain: the feature, the result text and the model the result recorded.
 * DistillPod has no server; the report is an email the user sends from their own email app.
 */
internal data class AiResultReport(val feature: String, val result: String, val model: String? = null) {
    fun subject() = "DistillPod AI result report: $feature"

    /** The exact message shown before the email app opens. No transcript, audio, account or installation identity. */
    fun body(appVersion: String, includeResult: Boolean): String = buildString {
        append("What is wrong with this result?\n\n\n")
        append("Feature: $feature\n")
        model?.takeIf { it.isNotBlank() }?.let { append("Model: $it\n") }
        append("App version: $appVersion")
        if (includeResult && result.isNotBlank()) append("\n\nReported result:\n").append(result.trim())
    }

    /** Opens the user's email app with the message filled in; the app itself sends nothing. */
    fun emailIntent(appVersion: String, includeResult: Boolean): Intent {
        val body = body(appVersion, includeResult)
        return Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$ReportEmail?subject=${Uri.encode(subject())}&body=${Uri.encode(body)}"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(ReportEmail)).putExtra(Intent.EXTRA_SUBJECT, subject()).putExtra(Intent.EXTRA_TEXT, body)
    }
}

internal fun momentReport(moment: DistillationEntity) = AiResultReport("Saved moment",
    listOfNotNull(moment.insight, moment.quote?.let { "“$it”" }).joinToString("\n\n"), moment.model)

internal fun analysisReport(row: AnalysisEntity): AiResultReport {
    fun objects(json: String) = runCatching { val a = JSONArray(json); (0 until a.length()).map { a.getJSONObject(it) } }.getOrDefault(emptyList())
    val chapters = objects(row.chaptersJson).map { "${timestamp(it.optLong("startMs"))} · ${it.optString("title")}" }
    val ads = objects(row.adsJson).map { "${timestamp(it.optLong("startMs"))} – ${timestamp(it.optLong("endMs"))} · ${it.optString("reason")}" }
    val parts = listOfNotNull("Summary:\n${row.summary.trim()}", chapters.takeIf { it.isNotEmpty() }?.joinToString("\n", "Chapters:\n"),
        ads.takeIf { it.isNotEmpty() }?.joinToString("\n", "Suggested ads:\n"))
    return AiResultReport("Episode insights", parts.joinToString("\n\n"), row.model)
}

/** The reply only: the user's question stays out of the report. */
internal fun chatReport(reply: MessageEntity) = AiResultReport("Ask this episode", reply.text, reply.model)

internal fun explanationReport(row: ResearchEntity) = AiResultReport("Explain this idea", row.report,
    runCatching { JSONArray(row.sourcesJson).optJSONObject(0)?.optString("model")?.takeIf { it.isNotBlank() } }.getOrNull())

/** The words the post shows: the card's text and its caption, or an earlier format's text; never the source excerpt. */
internal fun postReport(result: SocialResult): AiResultReport? {
    val artifact = result.artifact ?: return null
    val card = runCatching { artifact.cardContent() }.getOrNull()
    val text = when {
        card != null -> listOf(cardDescription(card, card.style), artifact.text)
        artifact.image.isNotEmpty() -> listOf(artifact.description)
        else -> listOf(artifact.quote.takeIf { it.isNotBlank() }?.let { "“$it”" }.orEmpty(), artifact.text)
    }.filter { it.isNotBlank() }.joinToString("\n\n")
    return AiResultReport("Share post", text)
}

internal fun suggestionReport(suggestion: SuggestionEntity) = AiResultReport("Find new voices",
    listOf(listOf(suggestion.title, suggestion.author).filter { it.isNotBlank() }.joinToString(" · "), suggestion.reason)
        .filter { it.isNotBlank() }.joinToString("\n"))

internal fun voiceReport(answer: String) = AiResultReport("Voice answer", answer.trim())

@Composable internal fun rememberAppVersion(): String {
    val context = LocalContext.current
    return remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
}

private fun openEmail(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent); true
} catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

/**
 * Shows the exact message before anything leaves the phone; the result text can be removed. Without an email
 * app, the address and message are shown to copy instead, as other links explain a phone that cannot open them.
 */
@Composable internal fun ReportResultDialog(report: AiResultReport, onDismiss: () -> Unit, appVersion: String = rememberAppVersion()) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var includeResult by rememberSaveable { mutableStateOf(true) }
    var noEmailApp by rememberSaveable { mutableStateOf(false) }
    val subject = report.subject()
    val body = report.body(appVersion, includeResult)
    @Composable fun Message(text: String) = Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        SelectionContainer { Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall) }
    }
    if (!noEmailApp) AlertDialog(onDismissRequest = onDismiss, title = { Text("Report this result") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your email app opens with this message. Nothing is sent until you send it there.", style = MaterialTheme.typography.bodyMedium)
            if (report.result.isNotBlank()) Row(Modifier.fillMaxWidth().toggleable(includeResult, role = Role.Checkbox) { includeResult = it },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(includeResult, null); Spacer(Modifier.width(8.dp)); Text("Include the result text", Modifier.weight(1f))
            }
            Text("To: $ReportEmail", style = MaterialTheme.typography.labelLarge)
            Text("Subject: $subject", style = MaterialTheme.typography.labelLarge)
            Message(body)
        }
    }, confirmButton = {
        TextButton(onClick = { if (openEmail(context, report.emailIntent(appVersion, includeResult))) onDismiss() else noEmailApp = true }) { Text("Open email app") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    else AlertDialog(onDismissRequest = onDismiss, title = { Text("No email app found") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Send this report from any email account to:", style = MaterialTheme.typography.bodyMedium)
            SelectionContainer { Text(ReportEmail, style = MaterialTheme.typography.titleSmall) }
            Message("Subject: $subject\n\n$body")
        }
    }, confirmButton = {
        TextButton(onClick = { clipboard.setText(AnnotatedString("To: $ReportEmail\nSubject: $subject\n\n$body")); onDismiss() }) { Text("Copy report") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

/** A text button that opens [ReportResultDialog]; [report] is read when tapped so it reports what is shown then. */
@Composable internal fun ReportResultButton(report: () -> AiResultReport, modifier: Modifier = Modifier) {
    var reporting by remember { mutableStateOf<AiResultReport?>(null) }
    TextButton(onClick = { reporting = report() }, modifier = modifier) { Text("Report this result") }
    reporting?.let { ReportResultDialog(it, onDismiss = { reporting = null }) }
}
