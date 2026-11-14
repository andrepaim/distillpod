package dev.distillpod.android

import dev.distillpod.android.video.streamNeedsResolving
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Opening an episode page resolves a stream only for a YouTube episode that would otherwise need one to play. */
class StreamWarmupTest {
    @get:Rule val folder = TemporaryFolder()
    private val youtube = "https://www.youtube.com/watch?v=GlYgs6v2YfU"

    @Test fun aStreamingYouTubeEpisodeIsResolved() {
        assertTrue(streamNeedsResolving(youtube, null))
        assertTrue(streamNeedsResolving(youtube, folder.root.resolve("missing.m4a").path))
    }

    @Test fun downloadsAndFeedAudioAreNot() {
        assertFalse(streamNeedsResolving(youtube, folder.newFile("episode.m4a").path))
        assertFalse(streamNeedsResolving("https://example.test/episode.mp3", null))
    }
}
