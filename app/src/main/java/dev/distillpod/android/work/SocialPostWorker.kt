package dev.distillpod.android.work

import android.content.Context
import androidx.work.*
import dev.distillpod.android.appGraph
import dev.distillpod.android.ai.*
import dev.distillpod.android.data.*
import dev.distillpod.android.sharing.*
import kotlinx.coroutines.*
import org.json.JSONArray

object SocialPostJobs {
    fun enqueue(context: Context, id: String, attempt: String) {
        WorkManager.getInstance(context).enqueueUniqueWork("social:$id", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SocialPostWorker>().setInputData(workDataOf("post" to id, "attempt" to attempt))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun cancel(context: Context, id: String) { WorkManager.getInstance(context).cancelUniqueWork("social:$id") }
}
class SocialPostWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        graph.ready.await()
        val post = inputData.getString("post") ?: return Result.failure()
        val attempt = inputData.getString("attempt") ?: return Result.failure()
        val result = graph.socialResults.get(post) ?: return Result.success()
        if (result.attempt != attempt || result.stage !in listOf("queued", "preparing", "researching", "illustrating")) return Result.success()
        if (result.stage != "queued") {
            graph.socialResults.update(post,attempt) { it.copy(stage = "failed", error = "Creation was interrupted. Your last version is safe. Retry when ready.") }
            return Result.success()
        }
        return try {
            setForeground(workForeground(applicationContext, id, "Creating your post"))
            SocialPostGenerator(graph.socialResults, SocialComposer(graph.ai, graph.socialTools), graph.postAssets).run(post,attempt)
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { graph.socialResults.update(post,attempt) { it.copy(stage = "canceled") } }
            throw cancelled
        } catch (error: Exception) {
            graph.socialResults.update(post,attempt) { it.copy(stage = "failed", error = (if (error is SocialGenerationFailure) error.message.orEmpty() else safeFailure(error)).take(400)) }
            Result.success() // Never silently retry an expensive image or research request.
        }
    }
}
class SocialPostGenerator(private val repository: SocialResultRepository, private val composer: SocialComposer, private val assets: PostAssets) {
    suspend fun run(id: String, attempt: String) = withContext(Dispatchers.IO) {
        suspend fun step(stage: String): SocialResult {
            check(repository.update(id,attempt) { it.copy(stage = stage) }) { "This generation was superseded" }
            return repository.get(id)!!
        }
        var result = step("preparing")
        val artifact = if (result.format == "discussion") composer.discussion(result) else {
            val visualOnly = composer.visualChangeOnly(result)
            val research = if (visualOnly) WebEvidence(result.research,EvidenceSource.list(result.researchSources)) else {
                result = step("researching")
                composer.research(result).also { evidence ->
                    check(repository.update(id,attempt) { it.copy(research = evidence.text, researchSources = JSONArray(evidence.sources.map { s -> s.json() }).toString()) })
                }
            }
            result = step("illustrating")
            val (raw, brief) = if (visualOnly) result.artifact!!.brief.let { it to InfographicBrief.parse(it,research.sources) } else composer.brief(result)
            val image = composer.illustrate(brief, if (visualOnly) result.instruction else "")
            currentCoroutineContext().ensureActive()
            val png = InfographicRenderer.render(brief,image,research.sources,result.source.attribution())
            val hash = assets.put(png)
            val used = brief.nodes.flatMap { it.sources }.toSet()
            val sources = research.sources.filter { it.id in used }
            val description = listOf(brief.title,brief.subtitle,brief.nodes.joinToString("\n\n") { n -> n.title + ": " + n.detail + " " + n.sources.joinToString("") { "[$it]" } },
                brief.caveat, "Podcast: ${result.source.attribution()}", result.source.sourceUrl, sources.joinToString("\n") { "[${it.id}] ${it.title}: ${it.url}" }).filter(String::isNotBlank).joinToString("\n\n")
            SocialArtifact(image = hash, title = brief.title, description = description, sources = JSONArray(sources.map { it.json() }).toString(), brief = raw)
        }
        repository.update(id,attempt) { it.copy(stage = "ready", artifact = artifact, previous = it.artifact, error = "") }
    }
}
