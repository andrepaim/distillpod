package dev.distillpod.android

import dev.distillpod.android.features.VideoPresentation
import org.junit.Assert.*
import org.junit.Test

class VideoPresentationTest {
    @Test fun `automatic landscape respects rotation lock`() {
        assertFalse(VideoPresentation().orientation(true, false).fullscreen)
        assertTrue(VideoPresentation().orientation(true, true).fullscreen)
        assertFalse(VideoPresentation().orientation(true, true).orientation(false, true).fullscreen)
    }
    @Test fun `manual entry works locked and ignores initial portrait sensor event`() {
        val entered = VideoPresentation().enter()
        assertTrue(entered.orientation(false, false).fullscreen)
        assertTrue(entered.orientation(false, true).fullscreen)
        assertFalse(entered.orientation(true, true).orientation(false, true).fullscreen)
    }
    @Test fun `explicit exit suppresses landscape reentry until phone returns upright`() {
        val exited = VideoPresentation().orientation(true, true).exit()
        assertFalse(exited.orientation(true, true).fullscreen)
        assertTrue(exited.orientation(false, true).orientation(true, true).fullscreen)
    }
}
