package dev.distillpod.android.features

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.distillpod.android.ui.theme.TextButton

@Composable internal fun LicenseAndSources(version: String) {
    val context = LocalContext.current
    var showing by remember { mutableStateOf(false) }
    Text("Android app and shared core: GNU GPL version 3. Provided without warranty. Third-party components retain their own licenses.", style = MaterialTheme.typography.bodySmall)
    Row {
        TextButton(onClick = { showing = true }) { Text("View GPL license") }
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/andrepaim/distillpod/releases/tag/v$version")))
        }) { Text("Source and notices") }
    }
    if (showing) {
        val license = remember { context.assets.open("licenses/GPL-3.0.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { showing = false }, title = { Text("GNU GPL version 3") },
            text = { SelectionContainer(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(license, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { showing = false }) { Text("Close") } })
    }
}
