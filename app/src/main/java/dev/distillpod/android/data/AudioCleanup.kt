package dev.distillpod.android.data

import androidx.room.withTransaction
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class AudioCleanupKind { DOWNLOADED, UNFINISHED, CACHED }
internal enum class AudioCleanupStatus { ELIGIBLE, PROTECTED, UNAVAILABLE }
internal data class AudioCleanupCount(val files: Int = 0, val bytes: Long = 0)
internal data class AudioCleanupTotals(
    val downloaded: AudioCleanupCount = AudioCleanupCount(),
    val unfinished: AudioCleanupCount = AudioCleanupCount(),
    val cached: AudioCleanupCount = AudioCleanupCount(),
) {
    val files get() = downloaded.files + unfinished.files + cached.files
    val bytes get() = downloaded.bytes + unfinished.bytes + cached.bytes
}
internal data class AudioCleanupRow(val title: String, val kind: AudioCleanupKind,
    val status: AudioCleanupStatus, val files: Int, val bytes: Long, val reason: String? = null)
internal data class AudioCleanupResult(
    val removed: AudioCleanupTotals = AudioCleanupTotals(),
    val protectedUnits: Int = 0,
    val changedUnits: Int = 0,
    val failedUnits: Int = 0,
    val unavailableUnits: Int = 0,
    val reconciledEntries: Int = 0,
    val completedUnits: Int = 0,
    val totalUnits: Int = 0,
    val terminal: Boolean = false,
    val cancelled: Boolean = false,
    val errors: List<Throwable> = emptyList(),
)
internal class AudioCleanupPreview private constructor(
    val totals: AudioCleanupTotals,
    val rows: List<AudioCleanupRow>,
    val eligibleUnits: Int,
    val protectedUnits: Int,
    val unavailableUnits: Int,
    private val payload: Payload?,
) {
    internal constructor(totals: AudioCleanupTotals, rows: List<AudioCleanupRow>, eligibleUnits: Int,
        protectedUnits: Int = 0, unavailableUnits: Int = 0) :
        this(totals, rows, eligibleUnits, protectedUnits, unavailableUnits, null)

    private class Payload(val owner: Any, val units: List<AudioCleanupUnit>, val consumed: AtomicBoolean = AtomicBoolean())
    val canApply get() = eligibleUnits > 0
    @Volatile var latestResult: AudioCleanupResult = AudioCleanupResult()
        private set
    internal fun publish(result: AudioCleanupResult) { latestResult = result }
    internal fun claimAndConsume(owner: Any): List<AudioCleanupUnit> {
        val executable = payload
        require(executable != null && executable.owner === owner) { "Review audio again in this app session" }
        check(executable.consumed.compareAndSet(false, true)) { "This audio review has already been used" }
        return executable.units
    }
    companion object {
        internal fun issue(totals: AudioCleanupTotals, rows: List<AudioCleanupRow>, eligibleUnits: Int,
            protectedUnits: Int, unavailableUnits: Int, owner: Any, units: List<AudioCleanupUnit>) =
            AudioCleanupPreview(totals, rows.toList(), eligibleUnits, protectedUnits, unavailableUnits, Payload(owner, units.toList()))
    }
}

internal enum class AudioCleanupPathKind { ABSENT, FILE, UNSUPPORTED }
internal interface AudioCleanupFiles {
    fun kind(file: File): AudioCleanupPathKind
    fun length(file: File): Long
    fun delete(file: File): Boolean
    object SystemFiles : AudioCleanupFiles {
        override fun kind(file: File): AudioCleanupPathKind = try {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attributes.isRegularFile && !attributes.isSymbolicLink) AudioCleanupPathKind.FILE else AudioCleanupPathKind.UNSUPPORTED
        } catch (_: NoSuchFileException) { AudioCleanupPathKind.ABSENT }
        override fun length(file: File) = Files.size(file.toPath())
        override fun delete(file: File) = file.delete()
    }
}
internal data class AudioCleanupIdentity(val id: String, val podcastId: String, val guid: String,
    val audioUrl: String, val localFile: String?, val fingerprint: String, val age: Long) {
    companion object {
        fun of(row: EpisodeEntity) = AudioCleanupIdentity(row.id, row.podcastId, row.guid, row.audioUrl,
            row.localFile, row.mediaFingerprint, row.localDownloadedAt)
    }
}
internal data class AudioCleanupPath(
    val path: String,
    val rawPaths: Set<String>,
    val selection: AudioFileOwnership.Selection?,
    val presence: AudioCleanupPathKind,
    val bytes: Long,
    val references: Set<String>,
    val downloaded: Boolean,
    val partial: Boolean,
    val error: Throwable? = null,
) {
    val kind get() = if (downloaded) AudioCleanupKind.DOWNLOADED else if (partial) AudioCleanupKind.UNFINISHED else AudioCleanupKind.CACHED
}
internal data class AudioCleanupUnit(
    val paths: List<AudioCleanupPath>,
    val identities: Map<String, AudioCleanupIdentity>,
    val status: AudioCleanupStatus,
    val reason: String?,
)

/** Content-side-effect-free review followed by expected-generation guarded, short removal units. */
internal class AudioCleanupBackend(
    private val db: AppDatabase,
    private val directory: File,
    private val active: ActiveFiles,
    private val locked: suspend (List<String>, suspend () -> Unit) -> Unit,
    private val files: () -> AudioCleanupFiles,
) {
    private val owner = Any()
    private val dao get() = db.library()
    private data class State(val episodes: List<EpisodeEntity>, val queued: Set<String>,
        val transcripts: Map<String, String>, val automatic: Set<String>)

    private suspend fun state(): State {
        val episodes = dao.episodes()
        val subscribed = dao.podcasts().filter { it.subscribed }.map { it.id }.toSet()
        val automatic = mutableSetOf<String>()
        for (job in db.automation().jobs()) {
            if (job.stage != "ready" && job.podcastId in subscribed && db.automation().show(job.podcastId)?.initialized == true)
                automatic += job.episodeId
        }
        return State(episodes, dao.queue().map { it.episodeId }.toSet(),
            dao.transcripts().associate { it.episodeId to it.state }, automatic)
    }

    private fun paths(row: EpisodeEntity): List<File> = listOfNotNull(row.localFile?.let(::File), File(directory, "${row.id}.partial"))

    private fun protection(rows: List<EpisodeEntity>, state: State, copies: List<AudioCleanupPath>): String? {
        for (row in rows) {
            audioDeletionBlockReason(
                active = paths(row).any { active.busy(it.absolutePath) } || copies.any { active.busy(it.path) || it.rawPaths.any(active::busy) },
                queued = row.id in state.queued || paths(row).any { it.absolutePath in active.queued },
                transcribing = state.transcripts[row.id] in listOf("queued", "processing"),
                downloading = row.downloadState in listOf("queued", "downloading"),
            )?.let { return it.feedback }
        }
        if (rows.any { it.id in state.automatic }) return "Automatic preparation is in progress."
        if (copies.any { it.selection == null || active.busy(it.path) || it.rawPaths.any(active::busy) })
            return AudioDeletionBlockReason.ACTIVE.feedback
        return null
    }

    suspend fun preview(): AudioCleanupPreview = withContext(Dispatchers.IO) {
        val reviewContext = currentCoroutineContext()
        val snapshot = db.withTransaction { state() }
        val root = directory.canonicalFile
        val nodes = linkedMapOf<String, AudioCleanupPath>()
        val episodePaths = linkedMapOf<String, MutableSet<String>>()
        var unknownIdentity: Throwable? = null
        fun capture(file: File, episode: EpisodeEntity? = null, downloaded: Boolean = false) {
            val raw = file.absoluteFile
            var failure: Throwable? = null
            val canonical = try { raw.canonicalFile } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                unknownIdentity = error; failure = error; raw.normalize()
            }
            val key = canonical.path
            val old = nodes[key]
            // Register before stat, including an absent companion. No owner lease is acquired.
            val selection = active.audioOwnership.select(key)
            val presence = try {
                if (canonical.parentFile != root) AudioCleanupPathKind.UNSUPPORTED else files().kind(raw)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (failure == null) failure = error; AudioCleanupPathKind.UNSUPPORTED }
            val bytes = if (presence == AudioCleanupPathKind.FILE) files().length(raw).coerceAtLeast(0) else 0L
            val mergedPresence = if (old != null && old.presence != presence) AudioCleanupPathKind.UNSUPPORTED else presence
            nodes[key] = AudioCleanupPath(key, old?.rawPaths.orEmpty() + raw.path,
                if (old == null) selection else if (selection == null) null else old.selection,
                mergedPresence, maxOf(bytes, old?.bytes ?: 0), old?.references.orEmpty() + listOfNotNull(episode?.id),
                downloaded || old?.downloaded == true, (episode != null && !downloaded) || old?.partial == true,
                old?.error ?: failure)
            if (episode != null) episodePaths.getOrPut(episode.id) { linkedSetOf() } += key
        }
        val entries = directory.listFiles() ?: throw IOException("Audio storage could not be read")
        val existingPaths = entries.map { file ->
            try { file.canonicalPath } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { unknownIdentity = failure; file.absoluteFile.normalize().path }
        }.toSet()
        for (row in snapshot.episodes) {
            reviewContext.ensureActive()
            val partial = File(directory, "${row.id}.partial")
            val hasPartial = try { partial.canonicalPath in existingPaths }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { unknownIdentity = failure; true }
            val hasStatus = row.localFile != null || row.downloadState in listOf("paused", "failed") &&
                (row.downloadedBytes > 0 || row.etag != null || row.lastModified != null)
            if (!hasPartial && !hasStatus) continue
            row.localFile?.let { capture(File(it), row, true) }
            capture(partial, row)
        }
        entries.forEach { reviewContext.ensureActive(); capture(it) }
        // Without a complete canonical reference index even unrelated-looking files
        // cannot safely be called orphans. Report the original preview failure.
        unknownIdentity?.let { throw it }
        val byId = snapshot.episodes.associateBy { it.id }
        val seen = mutableSetOf<String>()
        val units = mutableListOf<AudioCleanupUnit>()
        for (start in nodes.keys) {
            reviewContext.ensureActive()
            if (start in seen) continue
            val pending = java.util.ArrayDeque<String>().apply { add(start) }
            val members = linkedSetOf<String>()
            while (pending.isNotEmpty()) {
                val path = pending.removeFirst()
                if (!seen.add(path)) continue
                members += path
                nodes.getValue(path).references.forEach { id -> episodePaths[id].orEmpty().forEach { if (it !in seen) pending += it } }
            }
            val copies = members.map(nodes::getValue)
            val ids = copies.flatMap { it.references }.toSet()
            val rows = ids.map(byId::getValue)
            val hasStatus = rows.any { it.localFile != null || it.downloadState in listOf("paused", "failed") &&
                (it.downloadedBytes > 0 || it.etag != null || it.lastModified != null) }
            if (copies.all { it.presence == AudioCleanupPathKind.ABSENT } && !hasStatus) continue
            val unavailable = unknownIdentity != null || copies.any { it.presence == AudioCleanupPathKind.UNSUPPORTED }
            val protected = protection(rows, snapshot, copies)
            val status = when { unavailable -> AudioCleanupStatus.UNAVAILABLE; protected != null -> AudioCleanupStatus.PROTECTED; else -> AudioCleanupStatus.ELIGIBLE }
            units += AudioCleanupUnit(copies, rows.associate { it.id to AudioCleanupIdentity.of(it) }, status,
                if (unavailable) "Audio storage could not be safely identified." else protected)
        }
        val display = units.flatMap { unit ->
            val title = unit.identities.keys.mapNotNull { byId[it]?.title }.distinct().take(3).joinToString(" · ")
                .ifEmpty { "Other managed audio cache" }
            val groups = unit.paths.groupBy { it.kind }
            groups.mapNotNull { (kind, paths) ->
                val present = paths.filter { it.presence == AudioCleanupPathKind.FILE }
                val missingKind = if (unit.paths.any { it.downloaded }) AudioCleanupKind.DOWNLOADED
                    else if (unit.identities.isNotEmpty()) AudioCleanupKind.UNFINISHED else AudioCleanupKind.CACHED
                val guardOnly = paths.all { it.presence == AudioCleanupPathKind.ABSENT } &&
                    (unit.paths.any { it.presence == AudioCleanupPathKind.FILE } || kind != missingKind)
                if (guardOnly) null
                else AudioCleanupRow(title, kind, unit.status, present.size, present.sumOf { it.bytes },
                    unit.reason ?: if (present.isEmpty()) "Missing audio status can be cleared." else null)
            }
        }
        AudioCleanupPreview.issue(total(units.flatMap { it.paths }.filter { it.presence == AudioCleanupPathKind.FILE }), display,
            units.count { it.status == AudioCleanupStatus.ELIGIBLE }, units.count { it.status == AudioCleanupStatus.PROTECTED },
            units.count { it.status == AudioCleanupStatus.UNAVAILABLE }, owner, units.toList())
    }

    suspend fun clear(preview: AudioCleanupPreview, onProgress: (AudioCleanupResult) -> Unit): AudioCleanupResult {
        val units = preview.claimAndConsume(owner)
        var result = AudioCleanupResult(protectedUnits = preview.protectedUnits, unavailableUnits = preview.unavailableUnits,
            completedUnits = units.count { it.status != AudioCleanupStatus.ELIGIBLE }, totalUnits = units.size,
            errors = units.flatMap { unit -> unit.paths.mapNotNull { it.error } }.take(8))
        fun publish(value: AudioCleanupResult) { result = value; preview.publish(value); onProgress(value) }
        publish(result)
        try {
            withContext(Dispatchers.IO) {
                for (unit in units.filter { it.status == AudioCleanupStatus.ELIGIBLE }) {
                    currentCoroutineContext().ensureActive()
                    locked(unit.identities.keys.sorted()) {
                        // Lock/network wait stays cancellable. Once this small unit starts, publish
                        // its physical ledger before returning through a cancellable dispatcher.
                        withContext(NonCancellable) {
                            val outcome = remove(unit)
                            publish(result.copy(removed = add(result.removed, outcome.removed),
                                protectedUnits = result.protectedUnits + if (outcome.status == OutcomeStatus.PROTECTED) 1 else 0,
                                changedUnits = result.changedUnits + if (outcome.status == OutcomeStatus.CHANGED) 1 else 0,
                                failedUnits = result.failedUnits + if (outcome.status == OutcomeStatus.FAILED) 1 else 0,
                                reconciledEntries = result.reconciledEntries + outcome.reconciled,
                                completedUnits = result.completedUnits + 1,
                                errors = (result.errors + listOfNotNull(outcome.failure)).take(8)))
                            outcome.cancellation?.let { throw it }
                        }
                    }
                }
            }
            publish(result.copy(terminal = true))
            return result
        } catch (cancelled: CancellationException) {
            publish(result.copy(terminal = true, cancelled = true))
            throw cancelled
        }
    }

    private enum class OutcomeStatus { REMOVED, PROTECTED, CHANGED, FAILED }
    private data class Outcome(val status: OutcomeStatus, val removed: AudioCleanupTotals = AudioCleanupTotals(),
        val reconciled: Int = 0, val failure: Throwable? = null, val cancellation: CancellationException? = null)

    private suspend fun remove(unit: AudioCleanupUnit): Outcome {
        var removed = AudioCleanupTotals()
        var heldReservation: AudioFileOwnership.Removal? = null
        return try {
            db.withTransaction {
                val current = state()
                val references = mutableMapOf<String, MutableSet<String>>()
                current.episodes.forEach { row -> paths(row).forEach { file ->
                    references.getOrPut(file.canonicalPath) { mutableSetOf() } += row.id
                } }
                val rows = current.episodes.filter { it.id in unit.identities }
                if (rows.size != unit.identities.size || rows.any { AudioCleanupIdentity.of(it) != unit.identities[it.id] } ||
                    unit.paths.any { references[it.path].orEmpty() != it.references }) return@withTransaction Outcome(OutcomeStatus.CHANGED)
                for (path in unit.paths) {
                    if (path.rawPaths.any { File(it).canonicalPath != path.path || files().kind(File(it)) != path.presence })
                        return@withTransaction Outcome(OutcomeStatus.CHANGED)
                }
                if (protection(rows, current, unit.paths) != null) return@withTransaction Outcome(OutcomeStatus.PROTECTED)
                val reservation = active.audioOwnership.reserveExpected(unit.paths.map { it.selection ?: return@withTransaction Outcome(OutcomeStatus.PROTECTED) })
                when (reservation) {
                    AudioFileOwnership.ExpectedReservation.Busy -> return@withTransaction Outcome(OutcomeStatus.PROTECTED)
                    AudioFileOwnership.ExpectedReservation.Stale -> return@withTransaction Outcome(OutcomeStatus.CHANGED)
                    is AudioFileOwnership.ExpectedReservation.Success -> {
                        // Keep the marker until withTransaction has actually committed/rolled back,
                        // not merely until its lambda has finished resetting metadata.
                        heldReservation = reservation.removal
                        for (path in unit.paths.sortedWith(compareBy<AudioCleanupPath> { !it.partial }.thenBy { it.path })) {
                            if (path.presence != AudioCleanupPathKind.FILE) continue
                            val file = File(path.path)
                            val bytes = files().length(file).coerceAtLeast(0)
                            if (!files().delete(file)) throw IOException("An audio file could not be removed")
                            removed = add(removed, total(listOf(path.copy(bytes = bytes))))
                        }
                        var reconciled = 0
                        for (row in rows) {
                            val identity = unit.identities.getValue(row.id)
                            if (dao.episode(row.id)?.let(AudioCleanupIdentity::of) != identity)
                                throw IOException("Audio metadata changed during cleanup")
                            val missing = if (row.localFile != null) unit.paths.first { row.id in it.references && it.downloaded &&
                                File(row.localFile).canonicalPath == it.path }.presence == AudioCleanupPathKind.ABSENT
                                else unit.paths.filter { row.id in it.references }.all { it.presence == AudioCleanupPathKind.ABSENT }
                            reset(row)
                            if (missing) reconciled++
                        }
                        Outcome(OutcomeStatus.REMOVED, removed, reconciled)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            Outcome(OutcomeStatus.REMOVED, removed, cancellation = cancelled)
        } catch (failure: Exception) { Outcome(OutcomeStatus.FAILED, removed, failure = failure) }
        finally { heldReservation?.close() }
    }

    private fun reset(row: EpisodeEntity) {
        val query = "UPDATE episodes SET localFile=NULL,localDownloadedAt=0,downloadState='none',downloadedBytes=0,totalBytes=0,etag=NULL,lastModified=NULL,error=NULL " +
            "WHERE id=? AND localFile IS ? AND audioUrl=? AND mediaFingerprint=? AND localDownloadedAt=?"
        db.openHelper.writableDatabase.compileStatement(query).use { statement ->
            val args = listOf(row.id, row.localFile, row.audioUrl, row.mediaFingerprint, row.localDownloadedAt)
            args.forEachIndexed { index, value -> when (value) {
                null -> statement.bindNull(index + 1)
                is Long -> statement.bindLong(index + 1, value)
                else -> statement.bindString(index + 1, value.toString())
            } }
            check(statement.executeUpdateDelete() == 1) { "Audio metadata changed during cleanup" }
        }
    }

    private fun total(paths: List<AudioCleanupPath>): AudioCleanupTotals {
        fun count(kind: AudioCleanupKind) = paths.filter { it.kind == kind }.let { AudioCleanupCount(it.size, it.sumOf { it.bytes }) }
        return AudioCleanupTotals(count(AudioCleanupKind.DOWNLOADED), count(AudioCleanupKind.UNFINISHED), count(AudioCleanupKind.CACHED))
    }
    private fun add(a: AudioCleanupTotals, b: AudioCleanupTotals): AudioCleanupTotals {
        fun sum(a: AudioCleanupCount, b: AudioCleanupCount) = AudioCleanupCount(a.files + b.files, a.bytes + b.bytes)
        return AudioCleanupTotals(sum(a.downloaded, b.downloaded), sum(a.unfinished, b.unfinished), sum(a.cached, b.cached))
    }
}
