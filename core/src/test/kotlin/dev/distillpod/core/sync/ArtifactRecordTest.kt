package dev.distillpod.core.sync
import org.junit.Assert.*
import org.junit.Test
class ArtifactRecordTest {
    private val identity=mapOf("episodeId" to "e","podcastId" to "p","feedUrl" to "https://example.test/feed","originalFeedUrl" to "https://example.test/feed","podcastTitle" to "Show","podcastAuthor" to "Author","guid" to "guid","title" to "Episode","audioUrl" to "https://example.test/audio","durationMs" to "10000","fingerprint" to "sha")
    private fun value(payload:Map<String,String>)=SyncMutation("op",SyncOwner("alice","g1"),"artifact_e",0,payload)
    @Test fun `manifest carries public identity and verified content descriptor`() {SyncProtocol.validate(value(identity+mapOf("hash" to "a".repeat(64),"expandedBytes" to "100","schema" to "1")))}
    @Test fun `manifest cannot claim an unsupported format or excessive expansion`() {
        for(extra in listOf(mapOf("hash" to "a".repeat(64),"expandedBytes" to "33554433","schema" to "1"),mapOf("hash" to "bad","expandedBytes" to "100","schema" to "1")))
            assertThrows(IllegalArgumentException::class.java){SyncProtocol.validate(value(identity+extra))}
    }
}
