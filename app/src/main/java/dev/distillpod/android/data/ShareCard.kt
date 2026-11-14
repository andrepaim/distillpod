package dev.distillpod.android.data

import dev.distillpod.core.sourceQuoteMatches
import org.json.JSONArray
import org.json.JSONObject

/** Export sizes: 4:5 for LinkedIn and Instagram feeds, 9:16 for Stories. */
enum class CardSize(val width: Int, val height: Int) { Feed(1080, 1350), Story(1080, 1920) }

/** One short point on the Takeaways card. */
data class CardTakeaway(val title: String, val detail: String)

/**
 * Everything a share card shows, composed in one AI request. Every style and look is drawn on the
 * device from this value, so switching between them never needs another request.
 * See docs/android/social-share-cards.md.
 */
data class CardContent(
    /** The speaker's exact words. Shown large on the Quote style. */
    val quote: String,
    /** An exact substring of [quote] to emphasise, or blank. */
    val highlight: String = "",
    /** The idea in the listener's words. Shown large on the Big idea style. */
    val headline: String,
    /** A short exact quote under the headline, or blank. */
    val pull: String = "",
    val takeawaysTitle: String,
    val takeaways: List<CardTakeaway>,
    val style: String = STYLE_QUOTE,
    val look: String = LOOK_EMBER,
    /** PostAssets hash of the square cover thumbnail, or blank when the show has no usable artwork. */
    val cover: String = "",
    /** Opaque ARGB colour taken from the cover, or 0 when there is none. Looks derive their tones from it. */
    val tint: Int = 0,
    /** One or two words naming the subject, in the excerpt's language. The small label above headlines; blank omits it. */
    val topic: String = "",
) {
    fun json(): JSONObject = JSONObject().put("quote", quote).put("highlight", highlight).put("headline", headline)
        .put("pull", pull).put("takeawaysTitle", takeawaysTitle)
        .put("takeaways", JSONArray(takeaways.map { JSONObject().put("title", it.title).put("detail", it.detail) }))
        .put("style", style).put("look", look).put("cover", cover).put("tint", tint).put("topic", topic)

    /**
     * Fails unless the card can be drawn and every quoted word comes from [excerpt]. Messages name
     * the field and limit so they can be handed back to the model as a repair reason.
     */
    fun validated(excerpt: String): CardContent = apply {
        fun limit(name: String, value: String, max: Int, min: Int = 1) =
            require(value.length in min..max) { "$name has ${value.length} characters; allowed $min–$max." }
        limit("quote", quote, QUOTE_MAX)
        require(sourceQuoteMatches(quote, excerpt)) { "quote must be copied exactly from the excerpt." }
        limit("highlight", highlight, HIGHLIGHT_MAX, 0)
        require(highlight.isBlank() || quote.contains(highlight)) { "highlight must be an exact part of quote." }
        limit("headline", headline, HEADLINE_MAX)
        limit("pull", pull, PULL_MAX, 0)
        require(sourceQuoteMatches(pull, excerpt)) { "pull must be copied exactly from the excerpt." }
        limit("takeawaysTitle", takeawaysTitle, TAKEAWAYS_TITLE_MAX)
        require(takeaways.size == 3) { "takeaways needs exactly three items." }
        takeaways.forEachIndexed { index, item ->
            limit("takeaways[$index].title", item.title, TAKEAWAY_TITLE_MAX)
            limit("takeaways[$index].detail", item.detail, TAKEAWAY_DETAIL_MAX)
        }
        limit("topic", topic, TOPIC_MAX, 0)
        require(style in STYLES && look in LOOKS) { "Unknown card style or look." }
        require(cover.isEmpty() || cover.matches(Regex("[a-f0-9]{64}"))) { "Invalid cover reference." }
        require(look != LOOK_COVER || (cover.isNotEmpty() && tint != 0)) { "The cover look needs podcast artwork." }
    }

    companion object {
        const val STYLE_QUOTE = "quote"
        const val STYLE_IDEA = "idea"
        const val STYLE_TAKEAWAYS = "takeaways"
        val STYLES = listOf(STYLE_QUOTE, STYLE_IDEA, STYLE_TAKEAWAYS)

        const val LOOK_COVER = "cover"
        const val LOOK_EMBER = "ember"
        const val LOOK_SIGNAL = "signal"
        val LOOKS = listOf(LOOK_COVER, LOOK_EMBER, LOOK_SIGNAL)

        const val QUOTE_MAX = 240
        const val HIGHLIGHT_MAX = 40
        const val HEADLINE_MAX = 60
        const val PULL_MAX = 140
        const val TAKEAWAYS_TITLE_MAX = 48
        const val TAKEAWAY_TITLE_MAX = 32
        const val TAKEAWAY_DETAIL_MAX = 72
        const val TOPIC_MAX = 24
        /** A quote this short reads at a glance, so it leads; longer ones open on the Big idea style. */
        const val QUOTE_LEADS_MAX = 120
        /** Side of the stored square cover thumbnail, in pixels. */
        const val COVER_PX = 320

        fun defaultStyle(quote: String) = if (quote.length <= QUOTE_LEADS_MAX) STYLE_QUOTE else STYLE_IDEA
        fun defaultLook(cover: String) = if (cover.isNotEmpty()) LOOK_COVER else LOOK_EMBER

        fun parse(j: JSONObject): CardContent {
            val items = j.getJSONArray("takeaways")
            return CardContent(j.getString("quote"), j.optString("highlight"), j.getString("headline"), j.optString("pull"),
                j.getString("takeawaysTitle"),
                (0 until items.length()).map { items.getJSONObject(it).let { item -> CardTakeaway(item.getString("title"), item.getString("detail")) } },
                j.optString("style", STYLE_QUOTE), j.optString("look", LOOK_EMBER), j.optString("cover"), j.optInt("tint"), j.optString("topic"))
        }
    }
}
