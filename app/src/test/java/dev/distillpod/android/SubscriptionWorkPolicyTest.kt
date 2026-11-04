package dev.distillpod.android

import dev.distillpod.android.sync.*
import org.junit.Assert.*
import org.junit.Test

class SubscriptionWorkPolicyTest {
    private val binding=SyncBindingEntity(uid="alice",generation="g",enabled=true,lease="lease")
    @Test fun `signed out work waits for explicit authentication rather than retrying forever`() {
        assertFalse(shouldRetrySubscriptionWork(false,binding,false,true))
    }
    @Test fun `paused work stays paused`() {assertFalse(shouldRetrySubscriptionWork(false,binding.copy(enabled=false),true,true))}
    @Test fun `unfinished bootstrap continues without local uploads`() {assertTrue(shouldRetrySubscriptionWork(false,binding.copy(status="pending"),true,false))}
    @Test fun `unresolved conflicts wait for user choice`() {assertFalse(shouldRetrySubscriptionWork(false,binding.copy(status="conflict"),true,false))}
    @Test fun `conflicts do not block unrelated pending changes`() {assertTrue(shouldRetrySubscriptionWork(false,binding.copy(status="conflict"),true,true))}
    @Test fun `completed work needs no retry`() {assertFalse(shouldRetrySubscriptionWork(true,binding,true,false))}
}
