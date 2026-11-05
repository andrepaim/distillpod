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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Runner invokes seed, force-stops this package, then invokes resume in a new process. */
@RunWith(AndroidJUnit4::class)
class SubscriptionRestartDeviceTest {
    @Test fun offlineIntentSurvivesActualProcessRestart()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val host=args.getString("syncEmulatorHost")?:"10.0.2.2"
        require(host in listOf("10.0.2.2","127.0.0.1"))
        assumeTrue(args.getString("syncEmulators")=="true" && args.getString("syncRestartPhase") in listOf("seed","resume"))
        check(Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=context.getFileStreamPath("sync-restart-fixture.json")
        val options=FirebaseOptions.Builder().setProjectId("demo-distillpod-sync").setApplicationId("1:123456789:android:1234567890abcdef").setApiKey("fake-api-key").build()
        val app=FirebaseApp.initializeApp(context,options,"sync-restart-client")
        val auth=FirebaseAuth.getInstance(app).apply {useEmulator(host,9099)}
        val firestore=FirebaseFirestore.getInstance(app).apply {
            firestoreSettings=FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
            useEmulator(host,8085)
        }
        try { withTimeout(60000) {
            val cloud=FirebaseSubscriptionCloud(auth,firestore)
            if(args.getString("syncRestartPhase")=="seed") {
                check(!fixture.exists()) {"A previous restart fixture needs recovery"}
                val token=UUID.randomUUID().toString()
                auth.createUserWithEmailAndPassword("restart-$token@example.test","synthetic-password-123").await()
                val name="restart-$token.db"
                val db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
                try {
                    SubscriptionSyncStore(db,isolatedDemo=true).bind(cloud.head().owner)
                    firestore.disableNetwork().await()
                    SubscriptionSyncStore(db,isolatedDemo=true).follow(PodcastEntity("restart-podcast","https://example.test/restart","Restart fixture"),true)
                    assertEquals(1,SubscriptionSyncStore(db,isolatedDemo=true).pendingCount())
                    fixture.writeText(JSONObject().put("name",name).put("uid",auth.currentUser!!.uid).toString())
                } finally {db.close()}
            } else {
                val state=JSONObject(fixture.readText())
                assertEquals(state.getString("uid"),auth.currentUser?.uid)
                val name=state.getString("name")
                val db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
                try {
                    assertEquals(1,SubscriptionSyncStore(db,isolatedDemo=true).pendingCount())
                    assertTrue(SubscriptionSyncCoordinator(db,cloud).run())
                    assertEquals(0,SubscriptionSyncStore(db,isolatedDemo=true).pendingCount())
                    val head=cloud.head()
                    val remote=cloud.entities("")
                    assertEquals("Restart fixture",remote.single().payload["title"])
                    assertEquals(1L,head.sequence)
                } finally {db.close();context.deleteDatabase(name)}
                fixture.delete();auth.signOut()
            }
        } } finally {firestore.terminate().await();app.delete()}
    }
}
