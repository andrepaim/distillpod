package dev.distillpod.android

import androidx.test.core.app.ApplicationProvider
import com.google.firebase.firestore.FirebaseFirestoreException
import dev.distillpod.android.data.AppDatabase
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.sync.*
import dev.distillpod.core.sync.SyncOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SubscriptionSyncFailureTest {
    private fun failure(code:FirebaseFirestoreException.Code)=FirebaseFirestoreException("synthetic",code)
    private fun checkFailure(error:Exception,expected:String)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("library.db")
        val db=AppDatabase.open(context)
        try {
            val store=SubscriptionSyncStore(db)
            store.bind(SyncOwner("alice","g1"))
            store.follow(PodcastEntity("p","https://example.test/rss","Synthetic",subscribed=true),true)
            val before=db.sync().operations("alice","g1")
            val cloud=object:SubscriptionCloud {
                override val uid="alice"
                override suspend fun head():CloudHead=throw error
                override suspend fun entities(after:String):List<CloudSubscription> = error("Unexpected entities")
                override suspend fun changes(after:Long,through:Long):List<CloudSubscription> = error("Unexpected changes")
                override suspend fun commit(operation:SyncOutboxEntity):CloudSubscription = error("Unexpected commit")
            }
            try { SubscriptionSyncCoordinator(db,cloud).run();fail("Failure must propagate") }
            catch(actual:Exception) { assertSame(error,actual) }
            assertEquals(expected,db.sync().binding()!!.status)
            assertEquals(before,db.sync().operations("alice","g1"))
            assertTrue(db.library().podcast("p")!!.subscribed)
        } finally {db.close()}
    }
    @Test fun onlyTransportFirestoreCodesAreOffline() {
        FirebaseFirestoreException.Code.values().filter {it!=FirebaseFirestoreException.Code.OK}.forEach {code->
            val expected=if(code in setOf(FirebaseFirestoreException.Code.UNAVAILABLE,FirebaseFirestoreException.Code.DEADLINE_EXCEEDED))"offline" else "error"
            assertEquals(code.name,expected,syncFailureStatus(failure(code)))
        }
    }
    @Test fun onlyStorageAuthorizationDenialRejectsUpload() {
        assertTrue(storageUploadRejected(com.google.firebase.storage.StorageException.fromExceptionAndHttpCode(null,403)!!))
        for(code in listOf(401,404,408,410,429,500,503))
            assertFalse(code.toString(),storageUploadRejected(com.google.firebase.storage.StorageException.fromExceptionAndHttpCode(null,code)!!))
        assertFalse(storageUploadRejected(com.google.firebase.storage.StorageException.fromException(java.io.IOException("synthetic"))!!))
    }
    @Test fun rejectionDiagnosticsAreContentFree() {
        val refused=SyncOperationRejected(FirebaseFirestoreException("SECRET record title",FirebaseFirestoreException.Code.PERMISSION_DENIED))
        assertEquals("rejected",dev.distillpod.android.diagnostics.Diagnostics.reason(refused))
        assertFalse(refused.message!!.contains("SECRET"))
    }
    @Test fun unavailableIsOffline()=checkFailure(failure(FirebaseFirestoreException.Code.UNAVAILABLE),"offline")
    @Test fun deadlineIsOffline()=checkFailure(failure(FirebaseFirestoreException.Code.DEADLINE_EXCEEDED),"offline")
    @Test fun permissionDeniedRemainsError()=checkFailure(failure(FirebaseFirestoreException.Code.PERMISSION_DENIED),"error")
    @Test fun ioRemainsOffline()=checkFailure(java.io.IOException("synthetic"),"offline")
    @Test fun cancellationRemainsPending()=checkFailure(CancellationException("synthetic"),"pending")
}
