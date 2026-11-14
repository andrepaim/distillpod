package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable internal fun LocalSearch(value:String,onChange:(String)->Unit,label:String,clearLabel:String) {
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    OutlinedTextField(value=value,onValueChange={onChange(it.take(200))},label={Text(label)},singleLine=true,
        leadingIcon={Icon(Icons.Default.Search,null)},trailingIcon={if(value.isNotEmpty())IconButton(onClick={onChange("")}){Icon(Icons.Default.Close,clearLabel)}},
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={focus.clearFocus();keyboard?.hide()}),
        shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth())
}

/** Settings home (#188): destinations start under the top bar, in three announced groups. */
@Composable internal fun SettingsOverview(connected:Boolean,voiceEnabled:Boolean,open:(String)->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        SettingsGroupLabel("Your library")
        SettingsDestination("Backup & Restore","Save or bring back a portable copy",Icons.Default.Restore){open("backup")}
        SettingsDestination("Library sync","Google account and sync status",Icons.Default.Sync){open("settings:sync")}
        SettingsDestination("Library","Import, export and refresh subscriptions",Icons.Default.ImportExport){open("settings:library")}
        SettingsGroupLabel("Playback & transcripts")
        SettingsDestination("Playback & headphones","Button shortcuts and save feedback",Icons.Default.Headphones){open("settings:playback")}
        if (voiceEnabled)
            SettingsDestination("Voice","API key and spoken answers",Icons.Default.Mic){open("settings:voice")}
        SettingsDestination("Transcript preparation","Automatic preparation, lookup, models and charging",Icons.Default.Subtitles){open("settings:transcription")}
        SettingsDestination("ChatGPT",if(connected)"Connected · account and AI model" else "Connect for insights and questions",Icons.Default.AutoAwesome){open("settings:ai")}
        SettingsDestination("Downloads & storage","Download network policy, usage and cleanup",Icons.Default.Download){open("settings:storage")}
        SettingsGroupLabel("App access & privacy")
        SettingsDestination("Notification access","Playback controls and background progress",Icons.Default.Notifications){open("settings:notifications")}
        SettingsDestination("Privacy & diagnostics","Share technical reports and report a problem",Icons.Default.BugReport){open("settings:diagnostics")}
        SettingsDestination("About DistillPod","Version, licenses and source code",Icons.Default.Info){open("settings:about")}
    }
}
@Composable private fun SettingsGroupLabel(title:String) {
    Text(title,Modifier.fillMaxWidth().padding(top=10.dp,start=4.dp).semantics{heading()},
        style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable private fun SettingsDestination(title:String,detail:String,icon:ImageVector,onClick:()->Unit) {
    Surface(onClick=onClick,shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Icon(icon,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
