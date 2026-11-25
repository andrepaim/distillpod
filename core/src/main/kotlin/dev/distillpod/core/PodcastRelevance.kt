package dev.distillpod.core

import java.text.Normalizer
import java.util.Locale

/** A suggested podcast's own channel metadata, from its verified feed or directory entry (#307). */
data class PodcastMetadata(val title: String, val author: String = "", val description: String = "", val category: String = "")

/** [reason] names the words this podcast's own metadata shares with the request, or is empty when it shares none. */
data class PodcastMatch(val score: Int, val reason: String)

/**
 * Deterministic relevance of suggested podcasts to the user's interests (#307). Words are compared whole, ignoring
 * case, accents, a plural "s" and common filler words. A word scores by its strongest field: title 3, category 2,
 * author or description 1. Words from the user's own interests always outrank words only a generated directory
 * search added, so a feed that matched through one episode, with nothing in its channel metadata, ranks last.
 */
object PodcastRelevance {
    private val filler = setOf("a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how", "in", "into", "is", "it", "its",
        "of", "on", "or", "the", "to", "with", "about", "your", "you", "my", "our", "we", "what", "who", "why", "this", "that", "new",
        "podcast", "podcasts", "show", "shows", "episode", "episodes", "e", "o", "os", "um", "uma", "de", "da", "do", "das", "dos",
        "em", "no", "na", "nos", "nas", "para", "por", "com", "sem", "sobre", "que", "mais", "el", "la", "los", "las", "y", "en", "con", "del", "al")
    private val fields = listOf("title", "author", "category", "description")
    private val weight = mapOf("title" to 3, "category" to 2, "author" to 1, "description" to 1)

    private fun words(text: String): List<Pair<String, String>> = Normalizer.normalize(text.take(5000), Normalizer.Form.NFC)
        .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.mapNotNull { word ->
            val plain = Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            if (plain.length < 2 || plain in filler) null
            else (if (plain.length > 3 && plain.endsWith("s") && !plain.endsWith("ss")) plain.dropLast(1) else plain) to word.lowercase(Locale.ROOT)
        }

    private fun list(values: List<String>) = if (values.size == 1) values[0] else values.dropLast(1).joinToString(", ") + " and " + values.last()

    fun match(interests: String, searches: List<String>, podcast: PodcastMetadata): PodcastMatch {
        val own = words(interests).distinctBy { it.first }
        val terms = own + searches.flatMap(::words).distinctBy { it.first }.filter { term -> own.none { it.first == term.first } }
        val present = mapOf("title" to podcast.title, "author" to podcast.author, "category" to podcast.category, "description" to podcast.description)
            .mapValues { (_, text) -> words(text).map { it.first }.toSet() }
        val matched = terms.mapNotNull { (key, shown) -> fields.filter { key in present.getValue(it) }.takeIf { it.isNotEmpty() }?.let { Triple(key, shown, it) } }
        val strength = { key: String, places: List<String> -> if (own.any { it.first == key }) 1000 * places.maxOf { weight.getValue(it) } else places.maxOf { weight.getValue(it) } }
        val score = matched.sumOf { (key, _, places) -> strength(key, places) }
        val reason = matched.take(4).groupBy({ it.third }, { "“${it.second}”" }).entries.joinToString("; ") { (places, shown) ->
            "${list(places)} ${if (places.size == 1) "mentions" else "mention"} ${list(shown)}"
        }.replaceFirstChar { it.titlecase(Locale.ROOT) }
        return PodcastMatch(score, if (reason.isEmpty()) "" else "$reason.")
    }

    /** [podcasts] with their matches, most relevant first; equal scores keep their given (directory) order. */
    fun <T> rank(interests: String, searches: List<String>, podcasts: List<T>, metadata: (T) -> PodcastMetadata): List<Pair<T, PodcastMatch>> =
        podcasts.map { it to match(interests, searches, metadata(it)) }.sortedByDescending { it.second.score }
}
