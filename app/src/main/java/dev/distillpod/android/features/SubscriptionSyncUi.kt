package dev.distillpod.android.features

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import dev.distillpod.android.sync.SubscriptionSyncStore

@Composable internal fun SubscriptionSyncSettings(vm:AppViewModel, onOpenBackup: () -> Unit) {
    val account=vm.graph.syncAccount
    val state by account.state.collectAsStateWithLifecycle()
    val binding by account.store.binding.collectAsStateWithLifecycle(initialValue=null)
    var upgradeMoments by remember { mutableStateOf(false) }
    val contextual by vm.graph.db.sync().observeView("__contextual_moments").collectAsStateWithLifecycle(initialValue=null)
    var reviewing by remember { mutableStateOf(false) }
    val approvals by account.store.approvals.collectAsStateWithLifecycle(initialValue=emptyList())
    val podcasts by vm.podcasts.collectAsStateWithLifecycle()
    val conflicts by account.conflicts.collectAsStateWithLifecycle(initialValue=emptyList())
    val fullScope by account.fullScope.collectAsStateWithLifecycle(initialValue=null)
    val excludedQueue by vm.graph.db.sync().observeView("__queue_excluded").collectAsStateWithLifecycle(initialValue=null)
    val contentStates by account.contentStates.collectAsStateWithLifecycle(initialValue=emptyList())
    val pending by account.pending.collectAsStateWithLifecycle(initialValue=0)
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    fun signIn(selected:Set<String>?=null) {
        scope.launch {
            var current=context
            while(current is ContextWrapper && current !is Activity)current=current.baseContext
            (current as? Activity)?.let { account.connect(it,selected) }
        }
    }
    if(upgradeMoments) AlertDialog(onDismissRequest={upgradeMoments=false},
        title={Text("Upgrade saved-moment sync?")},
        text={Text("Update DistillPod on your other phones first. Older app versions will stop syncing after this account upgrade. Selected passages and takeaways will sync; full transcripts use the separate finished-content scope when available, or file backup.")},
        confirmButton={TextButton(onClick={upgradeMoments=false;vm.action("moment-sync-upgrade"){account.enableContextualMoments()}}){Text("Upgrade sync")}},
        dismissButton={TextButton(onClick={upgradeMoments=false}){Text("Not now")}})
    if(reviewing) {
        val approved=approvals.map {it.podcastId}.toSet()
        val feeds=podcasts.filter {it.subscribed && (binding==null || it.id !in approved)}
        PublicFeedReview(feeds,if(binding==null)approved else emptySet(),binding==null,{selected->
            reviewing=false
            if(binding==null)signIn(selected) else vm.action("approve-sync-feeds"){account.approveFeeds(selected)}
        },{reviewing=false})
    }
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Library sync",style=MaterialTheme.typography.titleLarge)
        DataProtectionInfo(state.configured, account.contentConfigured, fullScope != null, false, onOpenBackup)
        Text("Your library works offline. On a new phone, connect the same Google account for supported synced records; restore your backup file for other library text.", style=MaterialTheme.typography.bodySmall)
        Text(DataProtectionGuidance.binding, style=MaterialTheme.typography.bodySmall)
        if(!state.configured) {
            Text("Cloud sync is unavailable in this build.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val ownedContent=contentStates.filter {it.uid==binding?.uid && it.generation==binding?.generation}
            val pendingContent=ownedContent.count {it.state in setOf("prepared","downloaded","waiting")}
            if((excludedQueue?.sequence?:0)>0)Text("${excludedQueue?.sequence} queue entries stay on this phone because their sources are not shared.",style=MaterialTheme.typography.bodySmall)
            if(account.contentConfigured && fullScope!=null && pendingContent>0)Text("$pendingContent saved content uploads or restores pending",color=MaterialTheme.colorScheme.secondary)
            ownedContent.filter {account.contentConfigured && it.state=="alternate"}.forEachIndexed {index,item->
                Text("${vm.episodes.value.firstOrNull {it.id==item.episodeId}?.title?:"Saved source"} · saved source version ${index+1}. This version needs review. Your current source is preserved.",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={vm.action("select-content"){account.useSavedContent(item.hash)}}){Text("Use saved source version ${index+1}")}
            }
            if(state.email.isNotEmpty())Text(state.email)
            val status=when {
                binding==null->"Not connected"
                !state.connected || state.uid!=binding?.uid->"Sign in with this library's original account"
                binding?.enabled!=true->"Sync paused"
                binding?.status=="synced"&&pending==0->"Library synced"
                binding?.status=="syncing"->"Syncing library…"
                binding?.status=="conflict"->"Library changes need review"
                binding?.status=="error"->"Sync needs attention. Retry or reconnect."
                binding?.status=="upgrade_required"->"New distills are saved locally. Upgrade saved-moment sync to send them."
                binding?.status=="offline"->"Waiting for a connection"
                else->"$pending changes waiting to sync"
            }
            Text(status,color=MaterialTheme.colorScheme.secondary)
            if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            else if(!state.connected || binding==null || state.uid!=binding?.uid) {
                Button(onClick={if(binding==null)reviewing=true else signIn()}) {Text("Connect Google & sync library")}
            } else {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={vm.action("sync-toggle"){if(binding?.enabled==true)account.pause() else account.resume()}}) {Text(if(binding?.enabled==true)"Pause sync" else "Resume sync")}
                    TextButton(onClick={account.kick()},enabled=binding?.enabled==true){Text("Sync now")}
                }
                if(binding?.enabled==true && podcasts.any {p->p.subscribed && approvals.none {it.podcastId==p.id}}) {
                    TextButton(onClick={reviewing=true}){Text("Add existing subscriptions to sync")}
                }
                if(contextual==null) OutlinedButton(onClick={upgradeMoments=true},enabled=binding?.enabled==true) { Text("Upgrade saved-moment sync") }
                if(fullScope==null) {
                    Text("Optional: sync queue and " + DataProtectionGuidance.preferences + ".",style=MaterialTheme.typography.bodySmall)
                    Text(DataProtectionGuidance.expanded,style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={vm.action("full-library-sync"){account.enableFullScope()}},enabled=binding?.enabled==true){Text("Sync queue & preferences")}
                    if(!account.contentConfigured)Text("Transcripts and other finished text stay local. Use Backup & Restore to transfer them.",style=MaterialTheme.typography.bodySmall)
                }
                if(fullScope!=null) Text(DataProtectionGuidance.expanded,style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={vm.action("sync-signout"){account.disconnect()}}){Text("Sign out of sync")}
            }
            conflicts.forEach { conflict ->
                Card {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        val key=conflict.entityKey
                        val remote by remember(key,binding?.uid,binding?.generation) {
                            vm.graph.db.sync().observeRemote(binding?.uid.orEmpty(),binding?.generation.orEmpty(),key)
                        }.collectAsStateWithLifecycle(initialValue=null)
                        val cloudChoice=runCatching {SubscriptionSyncStore.decode(remote?.payload?:"{}")} .getOrDefault(emptyMap())
                        val portable=dev.distillpod.core.sync.PortableRecord.isPortable(key) || dev.distillpod.core.sync.CollectionRecord.supports(key) || key.startsWith("artifact_")
                        val local=runCatching {SubscriptionSyncStore.decode(conflict.payload)}.getOrDefault(emptyMap())
                        val title=when {key=="queue_library"->"Queue";key.startsWith("preference_")->"Listening preference";portable->local["title"]?:"Saved content";else-> podcasts.firstOrNull {SubscriptionSyncStore.key(it)==key}?.title?:"Subscription"}
                        Text(title,style=MaterialTheme.typography.titleSmall)
                        Text(when {key=="queue_library"->"Queue order changed on another phone.";key.startsWith("preference_")->"This preference changed on another phone.";key.startsWith("artifact_")->"Saved content changed on another phone.";key.startsWith("progress_")->"Listening positions differ between phones.";key.startsWith("moment_")->"This saved moment changed on another phone.";key.startsWith("completion_")->"Played status changed on another phone.";else->"This subscription changed on another phone."})
                        if(key.startsWith("progress_")) {
                            Text("This phone: ${timestamp(local["positionMs"]?.toLongOrNull()?:0)}",style=MaterialTheme.typography.bodySmall)
                            Text("Other phone: ${timestamp(cloudChoice["positionMs"]?.toLongOrNull()?:0)}",style=MaterialTheme.typography.bodySmall)
                        }
                        if(key.startsWith("completion_")) {
                            Text("This phone: ${if(local["completed"]=="true")"Played" else "Unplayed"}",style=MaterialTheme.typography.bodySmall)
                            Text("Other phone: ${if(cloudChoice["completed"]=="true")"Played" else "Unplayed"}",style=MaterialTheme.typography.bodySmall)
                        }
                        if(key.startsWith("moment_")) {
                            fun preview(p:Map<String,String>,deleted:Boolean)=if(deleted)"Deleted" else p["insight"].orEmpty().ifBlank {p["excerpt"].orEmpty()}.ifBlank {"Saved interval"}.take(180)
                            Text("This phone: ${preview(local,conflict.deleted)}",style=MaterialTheme.typography.bodySmall)
                            Text("Other phone: ${preview(cloudChoice,remote?.deleted==true)}",style=MaterialTheme.typography.bodySmall)
                        }
                        Text("Both choices are retained until you decide.",style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={vm.action("resolve-sync"){
                            if(portable)vm.graph.portableSync.resolve(key,true) else account.store.resolve(key,true)
                            account.kick()
                        }}){Text("Keep this phone's choice")}
                        TextButton(onClick={vm.action("resolve-sync"){
                            if(portable) {
                                if(key=="queue_library") {
                                    vm.graph.playback.chooseCloudQueue(
                                        resolve={vm.graph.portableSync.resolve(key,false)},
                                        imported={vm.graph.importedQueue.value=it})
                                }
                                if(key!="queue_library")vm.graph.portableSync.resolve(key,false)
                                if(key.startsWith("progress_")) {
                                    val id=local["episodeId"]
                                    if(vm.graph.playback.state.value.episodeId==id && id!=null)vm.graph.db.library().episode(id)?.let {vm.graph.playback.seek(it.positionMs)}
                                }
                            } else account.store.resolve(key,false)
                            account.kick()
                        }}){Text("Use the cloud choice")}

                    }
                }
            }
            if(state.message.isNotBlank())Text(state.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            if(binding!=null)Text("This library remains bound to its original Google account when signed out.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
