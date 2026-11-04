package dev.distillpod.core.sync

import org.junit.Assert.*
import org.junit.Test

class CollectionRecordTest {
    private fun mutation(key:String,payload:Map<String,String>)=SyncMutation("op",SyncOwner("alice","g1"),key,0,payload)
    @Test fun `ordered queue can travel without playback or private paths`() {
        SyncProtocol.validate(mutation("queue_library",mapOf("items" to "episode2,episode1")))
        SyncProtocol.validate(mutation("queue_library",mapOf("items" to "")))
    }
    @Test fun `portable preferences use independent keys`() {
        for((name,value) in mapOf("model" to "tiny","language" to "pt","aiModel" to "gpt-6.1-sol"))
            SyncProtocol.validate(mutation("preference_$name",mapOf("value" to value)))
    }
    @Test fun `queue cannot carry locators duplicates or unbounded items`() {
        for(items in listOf("https://secret.test/audio?key=secret","one,one",(1..201).joinToString(","){"episode$it"},"one,,two"))
            assertThrows(IllegalArgumentException::class.java){SyncProtocol.validate(mutation("queue_library",mapOf("items" to items)))}
    }
    @Test fun `credentials network policy and job state are not portable preferences`() {
        for(key in listOf("token","wifi","charging","job_state","account"))
            assertThrows(IllegalArgumentException::class.java){SyncProtocol.validate(mutation("preference_$key",mapOf("value" to "secret")))}
    }
    @Test fun `invalid known preferences cannot poison restore`() {
        for((key,value) in listOf("model" to "unknown-model","language" to "invalid","aiModel" to "bad\nmodel"))
            assertThrows(IllegalArgumentException::class.java){SyncProtocol.validate(mutation("preference_$key",mapOf("value" to value)))}
    }
}
