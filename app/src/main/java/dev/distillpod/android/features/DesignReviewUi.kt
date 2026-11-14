package dev.distillpod.android.features

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.distillpod.android.data.Preferences
import dev.distillpod.android.data.whisperModels
import dev.distillpod.android.sync.SyncBindingEntity
import dev.distillpod.android.ui.theme.DistillSpacing
import dev.distillpod.android.ui.theme.extras

@Composable internal fun LocalSearch(value:String,onChange:(String)->Unit,label:String,clearLabel:String) {
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    OutlinedTextField(value=value,onValueChange={onChange(it.take(200))},label={Text(label)},singleLine=true,
        leadingIcon={Icon(Icons.Default.Search,null)},trailingIcon={if(value.isNotEmpty())IconButton(onClick={onChange("")}){Icon(Icons.Default.Close,clearLabel)}},
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={focus.clearFocus();keyboard?.hide()}),
        shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth())
}

/** Short current values shown at the end of overview rows; each comes from an existing flow. */
@Immutable internal data class SettingsStatus(
    val sync: String? = null,
    val subscriptions: Int? = null,
    val transcripts: String? = null,
    val wifiOnly: Boolean? = null,
    val version: String? = null,
)

private data class SettingsRow(val title: String, val detail: String, val icon: ImageVector, val route: String,
    val value: String? = null, val positive: Boolean = false)

/** Settings home (#188, #203): three announced groups of ListItems with their current values. */
@Composable internal fun SettingsOverview(connected:Boolean,voiceEnabled:Boolean,status:SettingsStatus=SettingsStatus(),open:(String)->Unit) {
    val library = listOf(
        SettingsRow("Backup & Restore","Save or bring back a portable copy",Icons.Default.Restore,"backup"),
        SettingsRow("Library sync","Google account and sync status",Icons.Default.Sync,"settings:sync",status.sync),
        SettingsRow("Library","Import, export and refresh subscriptions",Icons.Default.ImportExport,"settings:library",
            status.subscriptions?.let { if (it == 1) "1 show" else "$it shows" }))
    val listening = listOfNotNull(
        SettingsRow("Playback & headphones","Button shortcuts and save feedback",Icons.Default.Headphones,"settings:playback"),
        if (voiceEnabled) SettingsRow("Voice","API key and spoken answers",Icons.Default.Mic,"settings:voice") else null,
        SettingsRow("Transcript preparation","Automatic preparation, lookup, models and charging",Icons.Default.Subtitles,"settings:transcription",status.transcripts),
        SettingsRow("ChatGPT",if(connected)"Account and AI model" else "Connect for insights and questions",Icons.Default.AutoAwesome,"settings:ai",
            if (connected) "Connected" else null, positive = connected),
        SettingsRow("Downloads & storage","Download network policy, usage and cleanup",Icons.Default.Download,"settings:storage",
            status.wifiOnly?.let { if (it) "Wi-Fi only" else "Any network" }))
    val access = listOf(
        SettingsRow("Notification access","Playback controls and background progress",Icons.Default.Notifications,"settings:notifications"),
        SettingsRow("Privacy & diagnostics","Share technical reports and report a problem",Icons.Default.BugReport,"settings:diagnostics"),
        SettingsRow("About DistillPod","Version, licenses and source code",Icons.Default.Info,"settings:about",status.version?.takeIf { it.isNotBlank() }))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=DistillSpacing.gutter,vertical=DistillSpacing.item),
        verticalArrangement=Arrangement.spacedBy(DistillSpacing.sm)) {
        SettingsGroup("Your library", library, open)
        SettingsGroup("Playback & transcripts", listening, open)
        SettingsGroup("App access & privacy", access, open)
    }
}
@Composable private fun SettingsGroup(title:String, rows:List<SettingsRow>, open:(String)->Unit) {
    // Shown in capitals, announced in sentence case so TalkBack does not spell it out.
    Text(title.uppercase(),Modifier.fillMaxWidth().padding(top=DistillSpacing.item,start=DistillSpacing.xs,bottom=DistillSpacing.xs)
        .clearAndSetSemantics{heading();text=AnnotatedString(title)},
        style=MaterialTheme.typography.labelSmall,letterSpacing=0.8.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    SettingsGroupCard {
        rows.forEachIndexed { index, row ->
            if (index > 0) SettingsDivider(inset=62.dp)
            SettingsDestination(row) { open(row.route) }
        }
    }
}
@Composable private fun SettingsDestination(row:SettingsRow,onClick:()->Unit) {
    ListItem(
        headlineContent={Text(row.title,style=MaterialTheme.typography.titleSmall)},
        supportingContent={Text(row.detail,style=MaterialTheme.typography.bodySmall)},
        leadingContent={SettingsIcon(row.icon)},
        trailingContent={
            if (row.value != null) Text(row.value,Modifier.widthIn(max=128.dp),maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.End,
                style=MaterialTheme.typography.labelMedium,
                color=if(row.positive)MaterialTheme.extras.success else MaterialTheme.colorScheme.onSurfaceVariant)
            else Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors=ListItemDefaults.colors(containerColor=Color.Transparent,supportingColor=MaterialTheme.colorScheme.onSurfaceVariant),
        modifier=Modifier.fillMaxWidth().clickable(onClick=onClick))
}
/** A grouped-list container (proposal section 4): rows inside sit on surfaceContainerLow. */
@Composable internal fun SettingsGroupCard(content:@Composable ColumnScope.()->Unit) {
    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) { Column(content=content) }
}
@Composable internal fun SettingsDivider(inset:Dp=16.dp) = HorizontalDivider(Modifier.padding(start=inset),color=MaterialTheme.colorScheme.outlineVariant)

/** The 30 dp accent-container square that leads settings rows (proposal section 4). */
@Composable internal fun SettingsIcon(icon:ImageVector) {
    Box(Modifier.size(30.dp).clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center) {
        Icon(icon,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** "Synced 2 min ago", "Paused", "Off": a few words read from the existing sync binding. */
internal fun settingsSyncSummary(configured:Boolean,binding:SyncBindingEntity?,connected:Boolean,uid:String,pending:Int,now:Long):String=when {
    !configured->"Unavailable"
    binding==null->"Off"
    !connected || uid!=binding.uid->"Sign in"
    !binding.enabled->"Paused"
    binding.status=="synced"&&pending==0->syncedAgo(binding.lastSyncedAt,now)
    binding.status=="syncing"->"Syncing…"
    binding.status in setOf("conflict","rejected","error","upgrade_required")->"Needs attention"
    binding.status=="offline"->"Offline"
    pending>0->"$pending waiting"
    else->"Waiting"
}
private fun syncedAgo(at:Long,now:Long):String {
    if (at <= 0 || at > now) return "Synced"
    val minutes=(now-at)/60_000
    return when {
        minutes < 1 -> "Synced just now"
        minutes < 60 -> "Synced $minutes min ago"
        minutes < 1_440 -> "Synced ${minutes/60} h ago"
        else -> "Synced ${minutes/1_440} d ago"
    }
}
/** "Base · auto": the selected Whisper model and whether playback prepares transcripts. */
internal fun transcriptSettingSummary(model:String,automatic:Boolean):String {
    val mode=if(automatic)"auto" else "manual"
    val name=whisperModels.firstOrNull{it.id==model}?.title?.substringBefore(" · ")
    return if(name==null) mode else "$name · $mode"
}

/** Collects the overview values from flows the app already exposes; no I/O happens here. */
@Composable internal fun rememberSettingsStatus(vm:AppViewModel,prefs:Preferences,version:String):SettingsStatus {
    val sync=vm.graph.syncAccount
    val state by sync.state.collectAsStateWithLifecycle()
    val binding by sync.store.binding.collectAsStateWithLifecycle(initialValue=null)
    val pending by sync.pending.collectAsStateWithLifecycle(initialValue=0)
    val podcasts by vm.podcasts.collectAsStateWithLifecycle()
    val subscribed=remember(podcasts){podcasts.count{it.subscribed}}
    val syncText=settingsSyncSummary(state.configured,binding,state.connected,state.uid,pending,System.currentTimeMillis())
    return SettingsStatus(syncText,subscribed,transcriptSettingSummary(prefs.model,prefs.transcribeOnPlayback),prefs.wifiOnly,version)
}
