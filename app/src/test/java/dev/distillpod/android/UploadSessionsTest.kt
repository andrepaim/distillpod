package dev.distillpod.android
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.sync.UploadSessions
import dev.distillpod.core.sync.SyncOwner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class UploadSessionsTest {
    @Test fun `restart resumes only the original owner generation and object`() {
        val context=ApplicationProvider.getApplicationContext<Context>();val hash="a".repeat(64);val owner=SyncOwner("alice","g1")
        val sessions=UploadSessions(context);sessions.clear(owner,hash)
        val uri=Uri.parse("https://example.test/resumable-upload/synthetic-session")
        sessions.save(owner,hash,uri)
        assertEquals(uri,UploadSessions(context).read(owner,hash))
        assertNull(sessions.read(SyncOwner("bob","g1"),hash));assertNull(sessions.read(SyncOwner("alice","g2"),hash))
        sessions.clear(owner,hash);assertNull(sessions.read(owner,hash))
    }
}
