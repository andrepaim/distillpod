package dev.distillpod.android

import dev.distillpod.android.data.ActiveFiles
import java.io.File

/** Fixture teardown must wait for both app pointers and Media3's canonical file leases. */
internal fun fixtureAudioReleased(active: ActiveFiles, files: List<File>): Boolean =
    files.all { !active.busy(it.absolutePath) && !active.busy(it.canonicalPath) }
