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

@Composable internal fun SubscriptionSyncSettings(vm:AppViewModel) {
    val account=vm.graph.syncAccount
    val state by account.state.collectAsStateWithLifecycle()
    val binding by account.store.binding.collectAsStateWithLifecycle(initialValue=null)
    var reviewing by remember { mutableStateOf(false) }
    val approvals by account.store.approvals.collectAsStateWithLifecycle(initialValue=emptyList())
    val podcasts by vm.podcasts.collectAsStateWithLifecycle()
    val conflicts by account.conflicts.collectAsStateWithLifecycle(initialValue=emptyList())
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
    if(reviewing) {
        val approved=approvals.map {it.podcastId}.toSet()
        val feeds=podcasts.filter {it.subscribed && (binding==null || it.id !in approved)}
        PublicFeedReview(feeds,if(binding==null)approved else emptySet(),binding==null,{selected->
            reviewing=false
            if(binding==null)signIn(selected) else vm.action("approve-sync-feeds"){account.approveFeeds(selected)}
        },{reviewing=false})
    }
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Subscription sync",style=MaterialTheme.typography.titleLarge)
        Text("Connect Google to sync public podcast subscriptions between your phones. Listening progress, saved moments and transcripts still use Backup & Restore in this version.",style=MaterialTheme.typography.bodySmall)
        Text("Your library works offline. Only approved feeds and public directory follows sync. Other feeds, downloads and credentials stay on this phone.",style=MaterialTheme.typography.bodySmall)
        if(!state.configured) {
            Text("Cloud sync is unavailable in this build.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            if(state.email.isNotEmpty())Text(state.email)
            val status=when {
                binding==null->"Not connected"
                !state.connected || state.uid!=binding?.uid->"Sign in with this library's original account"
                binding?.enabled!=true->"Sync paused"
                binding?.status=="synced"&&pending==0->"Subscriptions synced"
                binding?.status=="syncing"->"Syncing subscriptions…"
                binding?.status=="conflict"->"Subscription changes need review"
                binding?.status=="error"->"Sync needs attention. Retry or reconnect."
                binding?.status=="offline"->"Waiting for a connection"
                else->"$pending changes waiting to sync"
            }
            Text(status,color=MaterialTheme.colorScheme.secondary)
            if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            else if(!state.connected || binding==null || state.uid!=binding?.uid) {
                Button(onClick={if(binding==null)reviewing=true else signIn()}) {Text("Connect Google & sync subscriptions")}
            } else {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={vm.action("sync-toggle"){if(binding?.enabled==true)account.pause() else account.resume()}}) {Text(if(binding?.enabled==true)"Pause sync" else "Resume sync")}
                    TextButton(onClick={account.kick()},enabled=binding?.enabled==true){Text("Sync now")}
                }
                if(binding?.enabled==true && podcasts.any {p->p.subscribed && approvals.none {it.podcastId==p.id}}) {
                    TextButton(onClick={reviewing=true}){Text("Add existing subscriptions to sync")}
                }
                TextButton(onClick={vm.action("sync-signout"){account.disconnect()}}){Text("Sign out of sync")}
            }
            conflicts.forEach { conflict ->
                Card {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        val title=podcasts.firstOrNull { dev.distillpod.android.sync.SubscriptionSyncStore.key(it)==conflict.entityKey }?.title ?: "Subscription"
                        Text(title,style=MaterialTheme.typography.titleSmall)
                        Text("This subscription changed on another phone.")
                        Text("Choose which subscription state to keep. Playback and saved episodes are preserved.",style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={vm.action("resolve-sync"){account.store.resolve(conflict.entityKey,true);account.kick()}}){Text("Keep this phone's choice")}
                        TextButton(onClick={vm.action("resolve-sync"){account.store.resolve(conflict.entityKey,false);account.kick()}}){Text("Use the cloud choice")}
                    }
                }
            }
            if(state.message.isNotBlank())Text(state.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            if(binding!=null)Text("This library remains bound to its original Google account when signed out.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
