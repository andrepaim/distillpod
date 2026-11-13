package dev.distillpod.core.sync

import org.junit.Assert.*
import org.junit.Test

class SyncCompactionTest {
    private val day=24L*60*60*1000
    @Test fun `protocol four receipts store a sha256 of the canonical signature`() {
        val canonical="{\"key\":\"subscription_a\",\"baseVersion\":0}"
        assertEquals(canonical,SyncCompaction.receiptSignature(canonical,3))
        val hashed=SyncCompaction.receiptSignature(canonical,4)
        assertTrue(hashed.matches(Regex("[a-f0-9]{64}")))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",SyncCompaction.receiptSignature("abc",4))
    }
    @Test fun `stored receipts match in either form and nothing else`() {
        val canonical="{\"payload\":{\"title\":\"Show\"}}"
        assertTrue(SyncCompaction.receiptMatches(canonical,canonical))
        assertTrue(SyncCompaction.receiptMatches(SyncCompaction.receiptSignature(canonical,4),canonical))
        assertFalse(SyncCompaction.receiptMatches(SyncCompaction.receiptSignature("other",4),canonical))
        assertFalse(SyncCompaction.receiptMatches(null,canonical))
        assertFalse(SyncCompaction.receiptMatches(SyncCompaction.receiptSignature(canonical,4).uppercase(),canonical))
    }
    @Test fun `first observation only starts the retention clock`() {
        val mark=SyncCompaction.observe(null,head=50_000,now=10*day)
        assertEquals(SyncCompaction.Mark(50_000,10*day,0),mark)
    }
    @Test fun `history becomes eligible only after the retention age`() {
        val mark=SyncCompaction.Mark(50_000,10*day,0)
        assertEquals(mark,SyncCompaction.observe(mark,head=60_000,now=39*day))
        assertEquals(SyncCompaction.Mark(70_000,40*day,50_000),SyncCompaction.observe(mark,head=70_000,now=40*day))
    }
    @Test fun `clock or account anomalies restart observation without losing earlier eligibility`() {
        val mark=SyncCompaction.Mark(50_000,10*day,20_000)
        assertEquals(SyncCompaction.Mark(60_000,5*day,20_000),SyncCompaction.observe(mark,head=60_000,now=5*day))
        // A head below the mark is a different history; nothing earlier is trusted.
        assertEquals(SyncCompaction.Mark(100,50*day,0),SyncCompaction.observe(mark,head=100,now=50*day))
    }
    @Test fun `watermark keeps the larger of the sequence and age windows and this device's cursor`() {
        // Sequence window binds.
        assertEquals(15_000L,SyncCompaction.watermark(head=25_000,current=0,eligible=20_000,cursor=25_000))
        // Age window binds.
        assertEquals(12_000L,SyncCompaction.watermark(head=25_000,current=0,eligible=12_000,cursor=25_000))
        // Never past what this installation itself replayed.
        assertEquals(11_000L,SyncCompaction.watermark(head=25_000,current=0,eligible=20_000,cursor=11_000))
    }
    @Test fun `watermark never moves backwards or below the retained window`() {
        assertNull(SyncCompaction.watermark(head=9_999,current=0,eligible=9_000,cursor=9_999))
        assertNull(SyncCompaction.watermark(head=10_000,current=0,eligible=10_000,cursor=10_000))
        assertNull(SyncCompaction.watermark(head=25_000,current=15_000,eligible=20_000,cursor=25_000))
        assertNull(SyncCompaction.watermark(head=25_000,current=15_000,eligible=14_000,cursor=25_000))
        assertNull(SyncCompaction.watermark(head=25_000,current=0,eligible=0,cursor=25_000))
    }
    @Test fun `operation already present in the cloud is recognized only by equal state`() {
        val payload=mapOf("title" to "Show")
        assertTrue(SyncCompaction.alreadyApplied(baseVersion=1,deleted=false,payload=payload,current=RemoteRecord(2,payload)))
        assertTrue(SyncCompaction.alreadyApplied(baseVersion=1,deleted=true,payload=emptyMap(),current=RemoteRecord(5,emptyMap(),true)))
        assertFalse(SyncCompaction.alreadyApplied(baseVersion=1,deleted=false,payload=payload,current=RemoteRecord(2,mapOf("title" to "Other"))))
        assertFalse(SyncCompaction.alreadyApplied(baseVersion=1,deleted=false,payload=payload,current=RemoteRecord(2,emptyMap(),true)))
        assertFalse(SyncCompaction.alreadyApplied(baseVersion=1,deleted=true,payload=emptyMap(),current=RemoteRecord(2,payload)))
        assertFalse(SyncCompaction.alreadyApplied(baseVersion=2,deleted=false,payload=payload,current=RemoteRecord(2,payload)))
        assertFalse(SyncCompaction.alreadyApplied(baseVersion=0,deleted=false,payload=payload,current=null))
    }
}
