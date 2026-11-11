package dev.distillpod.android

import android.content.Context
import android.net.Uri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import dev.distillpod.android.data.AudioFileOwnership
import dev.distillpod.android.playback.AudioPlaybackSelection
import dev.distillpod.android.playback.OwnedAudioPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OwnedAudioPlayerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val path = "/managed/one.audio"
    private fun item(ownership: AudioFileOwnership): MediaItem =
        MediaItem.Builder().setMediaId("one").setUri(Uri.parse("file://$path"))
            .setTag(AudioPlaybackSelection(ownership.select(path)!!, Uri.parse("https://example.test/audio"))).build()

    @Test fun everyIncomingPlaylistOverloadClaimsBeforeDelegating() {
        val operations = listOf<Pair<String, (Player, MediaItem) -> Unit>>(
            "set item" to { p, m -> p.setMediaItem(m) },
            "set item position" to { p, m -> p.setMediaItem(m, 123L) },
            "set item reset" to { p, m -> p.setMediaItem(m, false) },
            "set list" to { p, m -> p.setMediaItems(listOf(m)) },
            "set list reset" to { p, m -> p.setMediaItems(listOf(m), false) },
            "set list position" to { p, m -> p.setMediaItems(listOf(m), 0, 123L) },
            "add item" to { p, m -> p.addMediaItem(m) },
            "add item index" to { p, m -> p.addMediaItem(0, m) },
            "add list" to { p, m -> p.addMediaItems(listOf(m)) },
            "add list index" to { p, m -> p.addMediaItems(0, listOf(m)) },
            "replace item" to { p, m -> p.setMediaItem(MediaItem.fromUri("https://example.test/old")); p.replaceMediaItem(0, m) },
            "replace list" to { p, m -> p.replaceMediaItems(0, 0, listOf(m)) },
        )
        for ((name, operation) in operations) {
            val ownership = AudioFileOwnership()
            val engine = ExoPlayer.Builder(context).build()
            val guarded = OwnedAudioPlayer(engine, ownership, "/managed")
            try {
                operation(guarded, item(ownership))
                assertEquals(name, path, engine.getMediaItemAt(0).localConfiguration!!.uri.path)
                assertNull(name, ownership.reserve(listOf(path)))
                guarded.clearMediaItems()
                ownership.reserve(listOf(path))!!.close()
            } finally { guarded.release() }
        }
    }

    @Test fun staleSelectionUsesFallbackWithSameMetadataAndStartPosition() {
        val ownership = AudioFileOwnership()
        val prepared = item(ownership).buildUpon().setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder().setTitle("Episode").build()).build()
        ownership.reserve(listOf(path))!!.close()
        val engine = ExoPlayer.Builder(context).build()
        val guarded = OwnedAudioPlayer(engine, ownership, "/managed")
        try {
            guarded.setMediaItems(listOf(prepared), 0, 1234)
            assertEquals(Uri.parse("https://example.test/audio"), engine.currentMediaItem!!.localConfiguration!!.uri)
            assertEquals("Episode", engine.currentMediaItem!!.mediaMetadata.title)
            assertEquals(1234L, engine.currentPosition)
            assertNull(engine.currentMediaItem!!.localConfiguration!!.tag)
        } finally { guarded.release() }
    }

    @Test fun duplicateMoveRemoveStopAndReleaseRetainTheRightOwners() {
        val ownership = AudioFileOwnership()
        val engine = ExoPlayer.Builder(context).build()
        val guarded = OwnedAudioPlayer(engine, ownership, "/managed")
        val media = item(ownership)
        guarded.setMediaItems(listOf(media, media, MediaItem.fromUri("https://example.test/other")))
        guarded.moveMediaItem(0, 2)
        guarded.moveMediaItems(1, 3, 0)
        guarded.stop()
        assertNull(ownership.reserve(listOf(path)))
        guarded.removeMediaItem(0)
        assertNull(ownership.reserve(listOf(path)))
        guarded.removeMediaItems(0, guarded.mediaItemCount)
        ownership.reserve(listOf(path))!!.close()
        guarded.setMediaItem(item(ownership))
        guarded.release()
        ownership.reserve(listOf(path))!!.close()
    }

    @Test fun partialMutationFailureStillProtectsTheActualPlaylist() {
        val ownership = AudioFileOwnership()
        val engine = ExoPlayer.Builder(context).build()
        val expected = IllegalArgumentException("Synthetic mutation failure")
        val failing = object : ForwardingPlayer(engine) {
            override fun setMediaItems(items: List<MediaItem>) {
                super.setMediaItems(items)
                throw expected
            }
        }
        val guarded = OwnedAudioPlayer(failing, ownership, "/managed")
        try {
            assertSame(expected, runCatching { guarded.setMediaItems(listOf(item(ownership))) }.exceptionOrNull())
            assertEquals(1, engine.mediaItemCount)
            assertNull(ownership.reserve(listOf(path)))
            guarded.clearMediaItems()
            ownership.reserve(listOf(path))!!.close()
        } finally { guarded.release() }
    }

    @Test fun nestedMutationKeepsProvisionalOwnershipUntilTheOutermostCallReturns() {
        val ownership = AudioFileOwnership()
        val engine = ExoPlayer.Builder(context).build()
        lateinit var guarded: OwnedAudioPlayer
        val nested = object : ForwardingPlayer(engine) {
            override fun setMediaItems(items: List<MediaItem>) {
                super.setMediaItems(items)
                guarded.clearMediaItems()
                assertNull(ownership.reserve(listOf(path)))
            }
        }
        guarded = OwnedAudioPlayer(nested, ownership, "/managed")
        try {
            guarded.setMediaItems(listOf(item(ownership)))
            assertEquals(0, engine.mediaItemCount)
            ownership.reserve(listOf(path))!!.close()
        } finally { guarded.release() }
    }

    @Test fun unpreparedManagedCopyIsRejectedWhileDiagnosticClipRemainsPlayable() {
        val ownership = AudioFileOwnership()
        val engine = ExoPlayer.Builder(context).build()
        val guarded = OwnedAudioPlayer(engine, ownership, "/managed")
        try {
            assertTrue(runCatching { guarded.setMediaItem(MediaItem.fromUri("file://$path")) }.isFailure)
            assertEquals(0, engine.mediaItemCount)
            val clip = MediaItem.Builder().setMediaId("imported-clip").setUri("file:///cache/clip.wav").build()
            guarded.setMediaItem(clip)
            assertEquals(clip, engine.currentMediaItem)
        } finally { guarded.release() }
    }
}
