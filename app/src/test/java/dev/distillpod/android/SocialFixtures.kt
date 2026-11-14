package dev.distillpod.android

import android.graphics.Bitmap
import dev.distillpod.android.ai.AiGateway
import dev.distillpod.android.ai.AiText
import dev.distillpod.android.data.*
import org.json.JSONArray
import org.json.JSONObject

/** Share-card content that satisfies every limit, and a scripted AI that records what it was sent. */
internal object SocialFixtures {
    const val EXCERPT = "Small steps make practice easier. When you repeat a little every day, the habit grows without a fight. " +
        "Big plans often stall because they ask too much at once. The trick is to choose a step so small that skipping it feels silly."
    val CARD = CardContent(quote = "Small steps make practice easier.", highlight = "Small steps", headline = "Small steps beat big plans",
        pull = "Big plans often stall because they ask too much at once.", takeawaysTitle = "Make practice stick",
        takeaways = listOf(CardTakeaway("Start small", "Pick a step you can repeat every day."),
            CardTakeaway("Repeat daily", "A little each day lets the habit grow."),
            CardTakeaway("Skip the big plan", "Plans that ask too much at once tend to stall.")), topic = "Habits")
    const val CAPTION = "Small steps beat big plans when you want a habit to last.\n\n" +
        "When you repeat a little every day, the habit grows without a fight. Big plans often stall because they ask too much at once, so start with a step you can keep.\n\n" +
        "Pick one small practice today and repeat it tomorrow."
    val SOURCE = PostContent(show = "Show", episode = "Episode", excerpt = EXCERPT, quote = "Small steps make practice easier.", takeaway = "Small steps beat big plans.")

    fun answer(card: CardContent = CARD, caption: String = CAPTION, hashtags: List<String> = listOf("#habits", "practice", "learning")): JSONObject =
        card.json().apply { listOf("style", "look", "cover", "tint").forEach(::remove) }.put("caption", caption).put("hashtags", JSONArray(hashtags))

    fun cardArtifact(card: CardContent = CARD, text: String = "$CAPTION\n\n🎧 Show · Episode\n\n#habits #practice #learning") =
        SocialArtifact(text = text, quote = card.quote, title = card.headline, card = card.json().toString())

    fun bitmap(width: Int, height: Int, colour: Int = 0xFFC0392B.toInt()): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }

    fun png(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val image = bitmap(width, height)
        return java.io.ByteArrayOutputStream().use { image.compress(format, 100, it); image.recycle(); it.toByteArray() }
    }
}

internal class ScriptedAi(private val reply: (call: Int, input: String) -> String) : AiGateway {
    val prompts = mutableListOf<String>()
    val inputs = mutableListOf<JSONObject>()
    override suspend fun complete(instructions: String, input: String, onDelta: (String) -> Unit): AiText {
        prompts += instructions; inputs += JSONObject(input)
        return AiText(reply(inputs.size, input), "fixture")
    }
}
