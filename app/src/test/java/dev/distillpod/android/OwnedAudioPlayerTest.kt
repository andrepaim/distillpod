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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
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
            val delegate = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
                val incoming = args.orEmpty().flatMap {
                    when (it) {
                        is MediaItem -> listOf(it)
                        is List<*> -> it.filterIsInstance<MediaItem>()
                        else -> emptyList()
                    }
                }
                if (incoming.any { it.localConfiguration?.uri?.path == path }) {
                    assertNull("Ownership must precede delegate $name", ownership.reserve(listOf(path)))
                }
                try { method.invoke(engine, *args.orEmpty()) }
                catch (failure: InvocationTargetException) { throw failure.cause!! }
            } as Player
            val guarded = OwnedAudioPlayer(delegate, ownership, "/managed")
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

    @Test fun playbackUriAliasesRetainTheCanonicalOwnershipKey() {
        val ownership = AudioFileOwnership()
        val prepared = item(ownership)
        val alias = Uri.parse("file:///alias/one.audio")
        val token = (prepared.localConfiguration!!.tag as AudioPlaybackSelection).copy(uri = alias)
        val engine = ExoPlayer.Builder(context).build()
        val guarded = OwnedAudioPlayer(engine, ownership, "/managed")
        try {
            guarded.setMediaItem(prepared.buildUpon().setUri(alias).setTag(token).build())
            assertEquals(alias, engine.currentMediaItem!!.localConfiguration!!.uri)
            assertNull(ownership.reserve(listOf(path)))
            guarded.clearMediaItems()
            ownership.reserve(listOf(path))!!.close()
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

    @Test fun leaseReleaseNotificationCanReenterPlaylistMutation() {
        var reenter = false
        lateinit var guarded: OwnedAudioPlayer
        val ownership = AudioFileOwnership {
            if (reenter) {
                reenter = false
                guarded.clearMediaItems()
            }
        }
        val engine = ExoPlayer.Builder(context).build()
        guarded = OwnedAudioPlayer(engine, ownership, "/managed")
        val secondPath = "/managed/two.audio"
        val second = MediaItem.Builder().setMediaId("two").setUri("file://$secondPath")
            .setTag(AudioPlaybackSelection(ownership.select(secondPath)!!, Uri.parse("https://example.test/two"))).build()
        try {
            guarded.setMediaItems(listOf(item(ownership), second))
            reenter = true
            guarded.clearMediaItems()
            assertEquals(0, engine.mediaItemCount)
            ownership.reserve(listOf(path, secondPath))!!.close()
        } finally { guarded.release() }
    }

    @Test fun schemeLessManagedFileCannotBypassPreparation() {
        val ownership = AudioFileOwnership()
        val engine = ExoPlayer.Builder(context).build()
        val guarded = OwnedAudioPlayer(engine, ownership, "/managed")
        try {
            assertTrue(runCatching { guarded.setMediaItem(MediaItem.fromUri(path)) }.isFailure)
            assertEquals(0, engine.mediaItemCount)
        } finally { guarded.release() }
    }

    @Test fun releaseDuringAcquisitionRejectsMutationAndClosesTheNewLease() {
        var releaseOnAcquire = false
        var forwardedAfterRelease = false
        var released = false
        lateinit var guarded: OwnedAudioPlayer
        val ownership = AudioFileOwnership {
            if (releaseOnAcquire) {
                releaseOnAcquire = false
                guarded.release()
            }
        }
        val engine = ExoPlayer.Builder(context).build()
        val delegate = object : ForwardingPlayer(engine) {
            override fun setMediaItem(item: MediaItem) {
                if (released) forwardedAfterRelease = true
                super.setMediaItem(item)
            }
            override fun release() { released = true; super.release() }
        }
        guarded = OwnedAudioPlayer(delegate, ownership, "/managed")
        val prepared = item(ownership)
        releaseOnAcquire = true
        try {
            assertTrue(runCatching { guarded.setMediaItem(prepared) }.exceptionOrNull() is IllegalStateException)
            assertFalse("Terminal release must prevent later delegation", forwardedAfterRelease)
            assertFalse("Acquisition interrupted by release must not leak", ownership.busy(path))
        } finally { guarded.release() }
    }

    @Test fun nestedAcquisitionOfTheSameCopyDoesNotOverwriteAnOwner() {
        var nest = false
        lateinit var guarded: OwnedAudioPlayer
        lateinit var prepared: MediaItem
        val ownership = AudioFileOwnership {
            if (nest) { nest = false; guarded.setMediaItem(prepared) }
        }
        val engine = ExoPlayer.Builder(context).build()
        guarded = OwnedAudioPlayer(engine, ownership, "/managed")
        prepared = item(ownership)
        nest = true
        try {
            guarded.setMediaItem(prepared)
            assertTrue(ownership.busy(path))
            guarded.clearMediaItems()
            assertFalse("Nested admission must release every acquired owner", ownership.busy(path))
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
