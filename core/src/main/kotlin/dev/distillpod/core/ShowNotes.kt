package dev.distillpod.core

import java.net.URI

/** A run of show-note text; [url] is set only for an http(s) destination written by the source. */
data class ShowNotePart(val text: String, val url: String? = null)

/**
 * Show notes are stored as plain text in which each source link is written `[label](<https://…>)` (#308).
 * Plain descriptions stored by earlier versions are valid input unchanged, a reader that ignores the form
 * still sees the label and address, and neither the Room schema nor the backup format changes.
 */
object ShowNotes {
    private val hidden = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>|<!--.*?-->")
    private val tag = Regex("</?([A-Za-z][A-Za-z0-9]*)\\b[^>]*>")
    private val href = Regex("(?is)(?<![\\w-])href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")
    private val inline = setOf("a", "abbr", "b", "bdi", "bdo", "cite", "code", "em", "font", "i", "kbd", "mark", "q", "s", "small", "span",
        "strong", "sub", "sup", "time", "u", "var", "wbr")
    private val entity = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,7});")
    // ISO 8859-1 entity names in code point order from 160.
    private val latin1 = ("nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 acute micro " +
        "para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc " +
        "Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave " +
        "aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml " +
        "divide oslash ugrave uacute ucirc uuml yacute thorn yuml").split(' ').withIndex().associate { (i, name) -> name to 160 + i }
    private val named = latin1 + mapOf("amp" to 38, "lt" to 60, "gt" to 62, "quot" to 34, "apos" to 39, "ndash" to 8211, "mdash" to 8212,
        "lsquo" to 8216, "rsquo" to 8217, "sbquo" to 8218, "ldquo" to 8220, "rdquo" to 8221, "bdquo" to 8222, "bull" to 8226, "hellip" to 8230,
        "euro" to 8364, "trade" to 8482, "ensp" to 32, "emsp" to 32, "thinsp" to 32)
    private val link = Regex("\\[((?:\\\\[\\\\\\[\\]]|[^\\\\\\[\\]])*)]\\(<([^<>\\s]+)>\\)")
    private val address = Regex("(?i)(?<![\\w/.@-])https?://[^\\s<>\"]+")

    /** Feed HTML (or plain text) to the stored form: hidden content and tags removed, entities decoded once, http(s) anchors kept. */
    fun fromHtml(html: String): String {
        val out = StringBuilder()
        var label: StringBuilder? = null
        var destination: String? = null
        fun closeLink() {
            val text = label?.toString()?.replace(Regex("\\s+"), " ")?.trim() ?: return
            val url = destination
            when {
                url == null || text.isEmpty() -> out.append(text)
                text == url && linked(url) == listOf(ShowNotePart(url, url)) -> out.append(url)
                else -> out.append('[').append(text.replace(Regex("[\\\\\\[\\]]")) { "\\" + it.value }).append("](<").append(url).append(">)")
            }
            label = null; destination = null
        }
        val source = html.replace(hidden, " ")
        var at = 0
        for (match in tag.findAll(source)) {
            (label ?: out).append(decode(source.substring(at, match.range.first)))
            at = match.range.last + 1
            val name = match.groupValues[1].lowercase()
            val closing = match.value.startsWith("</")
            if (name == "a") {
                closeLink()
                if (!closing) {
                    label = StringBuilder()
                    destination = href.find(match.value)?.groupValues?.drop(1)?.firstOrNull(String::isNotEmpty)?.let { safeUrl(decode(it)) }
                }
            } else if (name !in inline) (label ?: out).append(' ')
        }
        (label ?: out).append(decode(source.substring(at)))
        closeLink()
        return out.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** Display parts: stored links, then bare http(s) addresses in the remaining text. Adjacent text is merged. */
    fun parts(stored: String): List<ShowNotePart> {
        val parts = mutableListOf<ShowNotePart>()
        fun add(part: ShowNotePart) {
            val last = parts.lastOrNull()
            if (part.url == null && last != null && last.url == null) parts[parts.lastIndex] = ShowNotePart(last.text + part.text) else if (part.text.isNotEmpty()) parts += part
        }
        var at = 0
        for (match in link.findAll(stored)) {
            linked(stored.substring(at, match.range.first)).forEach(::add)
            val url = safeUrl(match.groupValues[2])
            val text = match.groupValues[1].replace(Regex("\\\\([\\\\\\[\\]])"), "$1")
            add(if (url != null && text.isNotBlank()) ShowNotePart(text, url) else ShowNotePart(match.value))
            at = match.range.last + 1
        }
        linked(stored.substring(at)).forEach(::add)
        return parts
    }

    /** What a reader sees, without stored destinations; for search. */
    fun text(stored: String): String = if ("](<" in stored) parts(stored).joinToString("") { it.text } else stored

    /** The address exactly as written when it is an absolute http(s) URL with a host and no user information; never repaired. */
    fun safeUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() || it.isISOControl() || it in "<>\"" }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val authority = uri.rawAuthority
        return value.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !authority.isNullOrBlank() && '@' !in authority &&
            (uri.host ?: authority).isNotBlank() }
    }

    private fun linked(text: String): List<ShowNotePart> {
        val parts = mutableListOf<ShowNotePart>()
        var at = 0
        for (match in address.findAll(text)) {
            // Sentence punctuation, quotes and unbalanced closing brackets after an address are not part of it.
            var url = match.value
            while (url.isNotEmpty()) {
                val last = url.last()
                val kept = last.isLetterOrDigit() || last in "/-_=&#%~+$" ||
                    last == ')' && url.count { it == '(' } >= url.count { it == ')' } || last == ']' && url.count { it == '[' } >= url.count { it == ']' }
                if (kept) break else url = url.dropLast(1)
            }
            val safe = safeUrl(url) ?: continue
            if (match.range.first > at) parts += ShowNotePart(text.substring(at, match.range.first))
            parts += ShowNotePart(safe, safe)
            at = match.range.first + safe.length
        }
        if (at < text.length) parts += ShowNotePart(text.substring(at))
        return parts
    }

    private fun decode(text: String): String = if ('&' !in text) text else entity.replace(text) { match ->
        val name = match.groupValues[1]
        val code = when {
            name.startsWith("#x", true) -> name.substring(2).toIntOrNull(16)
            name.startsWith("#") -> name.substring(1).toIntOrNull()
            else -> named[name]
        }
        if (code == null || code == 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) match.value
        else if (code == 160) " " else String(Character.toChars(code))
    }
}
