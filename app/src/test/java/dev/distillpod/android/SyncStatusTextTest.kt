package dev.distillpod.android

import dev.distillpod.android.features.librarySyncStatus
import dev.distillpod.android.features.rejectedChangesText
import dev.distillpod.android.sync.SyncBindingEntity
import org.junit.Assert.*
import org.junit.Test

class SyncStatusTextTest {
    private val binding=SyncBindingEntity(uid="alice",generation="g",enabled=true,lease="lease")
    private fun status(state:String,pending:Int=0,rejected:Int=0)=librarySyncStatus(binding.copy(status=state),true,"alice",pending,rejected)
    @Test fun `rejected status explains refusal without record content`() {
        assertEquals("Some changes were not accepted by the cloud",status("rejected",pending=2,rejected=2))
    }
    @Test fun `waiting count excludes changes that are no longer sent automatically`() {
        assertEquals("1 changes waiting to sync",status("pending",pending=3,rejected=2))
    }
    @Test fun `existing states keep their wording`() {
        assertEquals("Library synced",status("synced"))
        assertEquals("Library changes need review",status("conflict",pending=1))
        assertEquals("Sync needs attention. Retry or reconnect.",status("error",pending=1))
        assertEquals("Waiting for a connection",status("offline",pending=1))
        assertEquals("Sync paused",librarySyncStatus(binding.copy(enabled=false),true,"alice",0,0))
        assertEquals("Not connected",librarySyncStatus(null,false,"",0,0))
        assertEquals("Sign in with this library's original account",librarySyncStatus(binding,true,"bob",0,0))
    }
    @Test fun `rejected guidance counts changes and keeps the local library`() {
        assertEquals("1 change was not accepted by the cloud and is no longer sent automatically. Your library on this phone is unchanged. Retry after updating DistillPod, or discard it to stop sending it.",rejectedChangesText(1))
        assertTrue(rejectedChangesText(3).startsWith("3 changes were not accepted by the cloud"))
    }
}
