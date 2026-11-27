package dev.distillpod.android.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.ui.theme.TextButton

@Composable internal fun PublicFeedReview(feeds:List<PodcastEntity>,preselected:Set<String>,newAccount:Boolean,
    onConfirm:(Set<String>)->Unit,onDismiss:()->Unit) {
    var selected by remember { mutableStateOf(preselected.intersect(feeds.map { it.id }.toSet())) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Choose public feeds to sync")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Selected public feed details, listening progress and saved moments will be stored in your cloud account. Leave private access links unselected. Password and query-bearing feed links stay on this phone; episode audio links are shared without their query.")
            if(feeds.isEmpty())Text(if(newAccount)"Your cloud subscriptions, progress and saved moments will be restored after sign-in." else "Your existing public subscriptions are already selected.")
            else {
                TextButton(onClick={selected=feeds.map {it.id}.toSet()}){Text("Select all as public")}
                LazyColumn(Modifier.heightIn(max=280.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(feeds,key={it.id}) { feed ->
                        Row(Modifier.fillMaxWidth().clickable {selected=if(feed.id in selected)selected-feed.id else selected+feed.id},verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(feed.id in selected,{checked->selected=if(checked)selected+feed.id else selected-feed.id},
                                Modifier.semantics {contentDescription="Sync ${feed.title}"})
                            Column(Modifier.weight(1f)) {
                                Text(feed.title,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(feed.feedUrl,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Text("Future follows from public directory results sync automatically. You can add other feeds later.",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick={onConfirm(selected)},enabled=newAccount||selected.isNotEmpty()) {
        Text(if(newAccount)"Continue with Google" else "Add selected feeds")
    }},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
