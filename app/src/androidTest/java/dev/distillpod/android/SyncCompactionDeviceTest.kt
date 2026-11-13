package dev.distillpod.android

import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import dev.distillpod.android.data.*
import dev.distillpod.android.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.UUID

/**
 * Real SDK and emulator rules for protocol-4 history compaction (#176): one installation compacts, an installation
 * with an offline edit and one with a lost acknowledgement both recover below the watermark without conflicts.
 * Needs the protocol-4 rules in firebase/firestore.rules; the deployed protocol-3 rules refuse compaction.
 */
@RunWith(AndroidJUnit4::class)
class SyncCompactionDeviceTest {
    @Test fun compactedAccountRecoversOfflineEditsAndLostAcknowledgements() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit isolated cloud emulator argument required",args.getString("syncEmulators")=="true")
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") || Build.HARDWARE.contains("ranchu")) { "Synthetic cloud tests require an Android emulator" }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val host=args.getString("syncEmulatorHost")?:"10.0.2.2"
        require(host in listOf("10.0.2.2","127.0.0.1"))
        for(port in listOf(8085,9099))Socket().use { it.connect(InetSocketAddress(host,port),3000) }
        val tag=UUID.randomUUID().toString()
        val options=FirebaseOptions.Builder().setProjectId("demo-distillpod-sync").setApplicationId("1:123456789:android:1234567890abcdef")
            .setApiKey("fake-api-key").build()
        val apps=listOf("a","b","c").map { FirebaseApp.initializeApp(context,options,"compaction-$it-$tag") }
        val auths=apps.map { FirebaseAuth.getInstance(it).apply { useEmulator(host,9099) } }
        val stores=apps.map { FirebaseFirestore.getInstance(it).apply {
            firestoreSettings=FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
            useEmulator(host,8085)
        } }
        val names=listOf("a","b","c").map { "sync-compaction-$it-$tag.db" }
        val dbs=names.map { Room.databaseBuilder(context,AppDatabase::class.java,it).build() }
        try {
            withTimeout(90_000) {
                val email="compaction-$tag@example.test";val password="fixture-password-123"
                val user=auths[0].createUserWithEmailAndPassword(email,password).await().user!!
                for(auth in auths.drop(1))auth.signInWithEmailAndPassword(email,password).await()
                val clouds=auths.indices.map { FirebaseSubscriptionCloud(auths[it],stores[it]) }
                val (a,b,c)=dbs
                val owner=clouds[0].head().owner
                for(db in listOf(a,b))SubscriptionSyncStore(db,isolatedDemo=true).bind(owner)
                fun podcast(id:String)=PodcastEntity(id,"https://example.test/$id","Synthetic $id",originalFeedUrl="https://example.test/$id")
                SubscriptionSyncStore(a,isolatedDemo=true).follow(podcast("first"),true)
                assertTrue(SubscriptionSyncCoordinator(a,clouds[0]).run());assertTrue(SubscriptionSyncCoordinator(b,clouds[1]).run())
                clouds[0].enableContextualMoments()
                // A's next commit reaches the cloud but its acknowledgement is lost: the operation stays uploading.
                SubscriptionSyncStore(a,isolatedDemo=true).follow(podcast("second"),true)
                val lost=a.sync().nextOperation(user.uid,owner.generation)!!
                a.sync().state(lost.operationId,"uploading")
                clouds[0].commit(lost.copy(state="uploading"))
                // B edits offline.
                SubscriptionSyncStore(b,isolatedDemo=true).follow(podcast("third"),true)
                // Fixture shortcut for a long history: the emulator's rules-bypassing owner token moves the head.
                val root=stores[0].collection("users").document(user.uid)
                val head=root.get(Source.SERVER).await().getLong("sequence")!!
                val jumped=head+10_050
                setHeadSequence(host,user.uid,jumped)
                // A third installation bootstraps at the head and compacts with no age wait.
                SubscriptionSyncStore(c,isolatedDemo=true).bind(owner)
                assertTrue(SubscriptionSyncCoordinator(c,clouds[2]).run())
                val compactor=SyncHistoryCompactor(c,clouds[2],SyncCompactionPolicy(true,retainedMs=0,intervalMs=0))
                assertEquals("the first run starts the retention clock",0,compactor.run())
                assertTrue(compactor.run()>0)
                val compacted=root.get(Source.SERVER).await()
                assertEquals(4L,compacted.getLong("protocol"));assertEquals(jumped-10_000,compacted.getLong("compactedThrough"))
                assertTrue(root.collection("changes").get(Source.SERVER).await().isEmpty)
                assertTrue(root.collection("receipts").get(Source.SERVER).await().isEmpty)
                assertEquals(2,root.collection("entities").get(Source.SERVER).await().size())
                // Both earlier installations are below the watermark and rebuild from entities.
                assertTrue(SubscriptionSyncCoordinator(b,clouds[1]).run())
                assertTrue(SubscriptionSyncCoordinator(a,clouds[0]).run())
                assertTrue(SubscriptionSyncCoordinator(b,clouds[1]).run())
                assertTrue(SubscriptionSyncCoordinator(c,clouds[2]).run())
                for(db in dbs) {
                    assertEquals(setOf("first","second","third"),db.library().podcasts().filter { it.subscribed }.map { it.id }.toSet())
                    assertEquals(0,db.sync().count());assertEquals("synced",db.sync().binding()!!.status)
                }
                val receipts=root.collection("receipts").get(Source.SERVER).await().documents
                assertEquals(1,receipts.size)
                assertTrue(receipts.single().getString("signature")!!.matches(Regex("[a-f0-9]{64}")))
            }
        } finally {
            dbs.forEach { it.close() };names.forEach { context.deleteDatabase(it) }
            stores.forEach { it.terminate().await() };apps.forEach { it.delete() }
        }
    }
    /** Emulator-only: the `owner` bearer token bypasses rules; production has no such token. */
    private suspend fun setHeadSequence(host:String,uid:String,sequence:Long)=withContext(Dispatchers.IO) {
        val name="projects/demo-distillpod-sync/databases/(default)/documents/users/$uid"
        val body=JSONObject().put("writes",org.json.JSONArray().put(JSONObject()
            .put("update",JSONObject().put("name",name).put("fields",JSONObject().put("sequence",JSONObject().put("integerValue",sequence.toString()))))
            .put("updateMask",JSONObject().put("fieldPaths",org.json.JSONArray().put("sequence"))))).toString()
        val connection=URL("http://$host:8085/v1/projects/demo-distillpod-sync/databases/(default)/documents:commit").openConnection() as HttpURLConnection
        try {
            connection.requestMethod="POST";connection.doOutput=true
            connection.setRequestProperty("Authorization","Bearer owner");connection.setRequestProperty("Content-Type","application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            check(connection.responseCode==200) { "Emulator head fixture failed: ${connection.responseCode}" }
        } finally { connection.disconnect() }
    }
}
