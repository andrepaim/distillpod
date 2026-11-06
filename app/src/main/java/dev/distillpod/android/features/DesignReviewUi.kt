package dev.distillpod.android.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.distillpod.android.data.PodcastEntity

@Composable internal fun LocalSearch(value:String,onChange:(String)->Unit,label:String,clearLabel:String) {
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    OutlinedTextField(value=value,onValueChange={onChange(it.take(200))},label={Text(label)},singleLine=true,
        leadingIcon={Icon(Icons.Default.Search,null)},trailingIcon={if(value.isNotEmpty())IconButton(onClick={onChange("")}){Icon(Icons.Default.Close,clearLabel)}},
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={focus.clearFocus();keyboard?.hide()}),
        shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth())
}

@Composable internal fun ShowsPage(vm:AppViewModel,podcasts:List<PodcastEntity>) {
    var query by rememberSaveable {mutableStateOf("")}
    val grid=rememberLazyGridState()
    LaunchedEffect(query) {grid.scrollToItem(0)}
    val shows=remember(podcasts,query){podcasts.filter {it.subscribed && (query.isBlank() || (it.title+" "+it.author).contains(query.trim(),ignoreCase=true))}.sortedBy {it.title.lowercase()}}
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        LocalSearch(query,{query=it},"Search your shows","Clear show search")
        Text("${shows.size} ${if(shows.size==1)"show" else "shows"} · A–Z",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(shows.isEmpty()) {
            Text(if(query.isBlank())"Follow your first show" else "No matching shows",style=MaterialTheme.typography.titleLarge)
            TextButton(onClick={if(query.isNotBlank())query="" else vm.root("search")}){Text(if(query.isNotBlank())"Clear search" else "Find podcasts")}
        }
        LazyVerticalGrid(state=grid,columns=GridCells.Adaptive(128.dp),contentPadding=PaddingValues(bottom=20.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            items(shows,key={it.id}) {show->
                Surface(onClick={vm.open("podcast:${show.id}")},shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        PodcastCover(show.imageUrl,Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(show.title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold,maxLines=3,overflow=TextOverflow.Ellipsis)
                        if(show.author.isNotBlank())Text(show.author,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable internal fun SettingsOverview(vm:AppViewModel,connected:Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Make it yours",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
        Text("Your library, your listening, your choices.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        SectionHeading("Your library")
        SettingsDestination("Backup & Restore","Save or bring back a portable copy",Icons.Default.Restore){vm.open("backup")}
        SettingsDestination("Library sync","Google account and sync status",Icons.Default.Sync){vm.open("settings:sync")}
        SectionHeading("Listening & insights")
        SettingsDestination("Playback & headphones","Button shortcuts and save feedback",Icons.Default.Headphones){vm.open("settings:playback")}
        if (vm.graph.context.resources.getBoolean(dev.distillpod.android.R.bool.voice_enabled))
            SettingsDestination("Voice","API key and spoken answers",Icons.Default.Mic){vm.open("settings:voice")}
        SettingsDestination("Local transcription","Models and language · on this phone",Icons.Default.Subtitles){vm.open("settings:transcription")}
        SettingsDestination("ChatGPT",if(connected)"Connected · account and AI model" else "Connect for insights and questions",Icons.Default.AutoAwesome){vm.open("settings:ai")}
        SettingsDestination("Downloads & storage","Network, charging and audio storage",Icons.Default.Download){vm.open("settings:storage")}
        SettingsDestination("Import & notifications","OPML subscriptions and background progress",Icons.Default.ImportExport){vm.open("settings:library")}
        TextButton(onClick={vm.open("settings:about")}){Text("About DistillPod")}
    }
}
@Composable private fun SettingsDestination(title:String,detail:String,icon:ImageVector,onClick:()->Unit) {
    Surface(onClick=onClick,shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Icon(icon,null,tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
