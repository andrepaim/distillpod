package dev.distillpod.android.playback

import android.net.Uri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.distillpod.android.data.AudioFileOwnership
import java.io.File

internal data class AudioPlaybackSelection(val copy: AudioFileOwnership.Selection, val fallback: Uri)

/** All playlist mutations stay synchronous on the delegate's application looper. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OwnedAudioPlayer(
    delegate: Player,
    private val ownership: AudioFileOwnership,
    directory: String,
) : ForwardingPlayer(delegate) {
    private val managedDirectory = File(directory).normalize().absolutePath + "/"
    private val held = mutableMapOf<AudioFileOwnership.Selection, AudioFileOwnership.Lease>()
    private var mutationDepth = 0

    private fun admit(item: MediaItem): MediaItem {
        val config = item.localConfiguration ?: return item
        val selection = config.tag as? AudioPlaybackSelection
        if (selection != null && config.uri == Uri.fromFile(File(selection.copy.path))) {
            if (selection.copy in held) return item
            val lease = ownership.acquire(selection.copy)
            if (lease != null) {
                held[selection.copy] = lease
                return item
            }
            return item.buildUpon().setUri(selection.fallback).setTag(null).build()
        }
        check(config.uri.scheme != "file" ||
            !File(config.uri.path.orEmpty()).normalize().absolutePath.startsWith(managedDirectory)) {
            "Local audio must be prepared before playback"
        }
        return if (selection == null) item else item.buildUpon().setTag(null).build()
    }

    private fun change(incoming: List<MediaItem> = emptyList(), action: (List<MediaItem>) -> Unit) {
        mutationDepth++
        try {
            action(incoming.map(::admit))
        } finally {
            mutationDepth--
            if (mutationDepth == 0) {
                val actual = (0 until mediaItemCount).mapNotNull {
                    (getMediaItemAt(it).localConfiguration?.tag as? AudioPlaybackSelection)?.copy
                }.toSet()
                val retired = held.keys.filter { it !in actual }
                retired.forEach { held.remove(it)!!.close() }
            }
        }
    }

    override fun setMediaItem(item: MediaItem) = change(listOf(item)) { super.setMediaItem(it.single()) }
    override fun setMediaItem(item: MediaItem, startPositionMs: Long) =
        change(listOf(item)) { super.setMediaItem(it.single(), startPositionMs) }
    override fun setMediaItem(item: MediaItem, resetPosition: Boolean) =
        change(listOf(item)) { super.setMediaItem(it.single(), resetPosition) }
    override fun setMediaItems(items: List<MediaItem>) = change(items) { super.setMediaItems(it) }
    override fun setMediaItems(items: List<MediaItem>, resetPosition: Boolean) =
        change(items) { super.setMediaItems(it, resetPosition) }
    override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) =
        change(items) { super.setMediaItems(it, startIndex, startPositionMs) }
    override fun addMediaItem(item: MediaItem) = change(listOf(item)) { super.addMediaItem(it.single()) }
    override fun addMediaItem(index: Int, item: MediaItem) =
        change(listOf(item)) { super.addMediaItem(index, it.single()) }
    override fun addMediaItems(items: List<MediaItem>) = change(items) { super.addMediaItems(it) }
    override fun addMediaItems(index: Int, items: List<MediaItem>) =
        change(items) { super.addMediaItems(index, it) }
    override fun replaceMediaItem(index: Int, item: MediaItem) =
        change(listOf(item)) { super.replaceMediaItem(index, it.single()) }
    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, items: List<MediaItem>) =
        change(items) { super.replaceMediaItems(fromIndex, toIndex, it) }
    override fun moveMediaItem(currentIndex: Int, newIndex: Int) =
        change { super.moveMediaItem(currentIndex, newIndex) }
    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) =
        change { super.moveMediaItems(fromIndex, toIndex, newIndex) }
    override fun removeMediaItem(index: Int) = change { super.removeMediaItem(index) }
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) =
        change { super.removeMediaItems(fromIndex, toIndex) }
    override fun clearMediaItems() = change { super.clearMediaItems() }

    override fun release() {
        try {
            super.release()
        } finally {
            held.values.forEach { it.close() }
            held.clear()
        }
    }
}
