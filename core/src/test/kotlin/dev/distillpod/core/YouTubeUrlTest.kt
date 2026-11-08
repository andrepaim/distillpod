package dev.distillpod.core

import org.junit.Assert.*
import org.junit.Test

class YouTubeUrlTest {
    @org.junit.Test fun `channels canonicalize to the latest videos tab without tracking parameters`() {
        org.junit.Assert.assertEquals("https://www.youtube.com/channel/UC1234567890123456789012/videos",
            YouTubeUrl.source("https://www.youtube.com/channel/UC1234567890123456789012?view=0"))
        org.junit.Assert.assertEquals("https://www.youtube.com/@portadosfundos/videos",
            YouTubeUrl.source("https://www.youtube.com/@portadosfundos/featured?si=tracking"))
        org.junit.Assert.assertNull(YouTubeUrl.source("https://www.youtube.com/@portadosfundos/unknown"))
        org.junit.Assert.assertNull(YouTubeUrl.source("https://youtu.be/@portadosfundos"))
    }

    @Test fun `shared links use a stable public video identity`() {
        val expected = "https://www.youtube.com/watch?v=heoWstJTwu4"
        listOf("https://youtu.be/heoWstJTwu4?si=tracking", "https://m.youtube.com/watch?v=heoWstJTwu4&t=60",
            "https://www.youtube.com/shorts/heoWstJTwu4", "https://YOUTU.BE/heoWstJTwu4",
            "https://www.youtube.com/live/heoWstJTwu4").forEach { assertEquals(expected, YouTubeUrl.source(it)) }
    }
    @Test fun `watch link with playlist imports the series`() {
        assertEquals("https://www.youtube.com/playlist?list=PLaE_mZALZ0V3lAEAvIfZC-aKf2ATIY3uy",
            YouTubeUrl.source("https://www.youtube.com/watch?v=heoWstJTwu4&list=PLaE_mZALZ0V3lAEAvIfZC-aKf2ATIY3uy"))
    }
    @Test fun `lookalike hosts credentials invalid ids and nonpublic protocols are rejected`() {
        listOf("https://youtube.com.evil.test/watch?v=heoWstJTwu4", "https://youtube.com@evil.test/watch?v=heoWstJTwu4",
            "https://name@youtube.com/watch?v=heoWstJTwu4", "file://youtube.com/watch?v=heoWstJTwu4",
            "https://youtube.com/watch?v=bad", "https://youtu.be/heoWstJTwu4/extra", "https://youtube.com:8443/watch?v=heoWstJTwu4",
            "https://youtube.com/shorts/extra/heoWstJTwu4", "https://youtube.com/live/extra/heoWstJTwu4")
            .forEach { assertNull(it, YouTubeUrl.source(it)) }
    }
}
