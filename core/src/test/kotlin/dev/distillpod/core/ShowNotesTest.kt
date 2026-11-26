package dev.distillpod.core
import org.junit.Assert.*
import org.junit.Test

class ShowNotesTest {
    private fun links(stored: String) = ShowNotes.parts(stored).filter { it.url != null }.map { it.text to it.url }

    @Test fun `an anchor with nested formatting keeps its label and exact encoded destination`() {
        val stored = ShowNotes.fromHtml("""<p>Buy: <a href="https://example.org/buy?id=1&amp;ref=a%20b"><strong>label</strong> <em>more</em></a>.</p>""")
        assertEquals(listOf("label more" to "https://example.org/buy?id=1&ref=a%20b"), links(stored))
        // Inline tags join their text as a browser does; block tags still separate it.
        assertEquals("Buy: label more.", ShowNotes.text(stored))
    }

    @Test fun `Escafandro episode 174 keeps its purchase and guest links from the feed`() {
        // Public show-note text of the real feed item, trimmed; the same HTML is CDATA in description and escaped in itunes:summary.
        val html = """<p>Como o escritor Tito Leite passou anos vivendo uma existência dividida entre duas paixões: o monastério e a boemia.</p><p><strong>Mergulhe mais fundo</strong></p><p>A Casa dos Malditos: memórias de um ex-monge beneditino <a href="https://link.amazon/B03dsWf9A">(link para compra)</a></p><p><strong>Entrevistado do episódio</strong></p><p><a href="https://www.instagram.com/titol_eite/">Tito Leite</a></p><p>Autor do livro &quot;A Casa dos Malditos: memórias de um ex-monge beneditino&quot; (Zahar, 2026).</p>"""
        val escaped = html.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val rss = """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Escafandro</title><item><title><![CDATA[174: O monge maldito]]></title><description><![CDATA[$html]]></description><guid isPermaLink="false">5237898b-f105-4520-be91-88a9e201c80f</guid><enclosure url="https://example.test/174.mp3" type="audio/mpeg"/><itunes:summary>$escaped</itunes:summary></item></channel></rss>"""
        val expected = listOf("(link para compra)" to "https://link.amazon/B03dsWf9A", "Tito Leite" to "https://www.instagram.com/titol_eite/")
        val fromDescription = parseFeed("https://example.test/rss", rss).episodes.single().description
        assertEquals(expected, links(fromDescription))
        assertTrue(ShowNotes.text(fromDescription).contains("A Casa dos Malditos: memórias de um ex-monge beneditino (link para compra) Entrevistado"))
        assertTrue(ShowNotes.text(fromDescription).contains("Autor do livro \"A Casa dos Malditos"))
        val summaryOnly = rss.replace(Regex("(?s)<description>.*?</description>"), "")
        assertEquals(expected, links(parseFeed("https://example.test/rss", summaryOnly).episodes.single().description))
    }

    @Test fun `plain addresses become links without swallowing trailing punctuation`() {
        val parts = ShowNotes.parts("Notes at https://example.org/a?b=1&c=%C3%A9. See (https://example.org/x_(y)) or https://example.org/end!")
        assertEquals(listOf("https://example.org/a?b=1&c=%C3%A9", "https://example.org/x_(y)", "https://example.org/end"), parts.mapNotNull { it.url })
        assertEquals(parts.filter { it.url != null }.map { it.url }, parts.filter { it.url != null }.map { it.text })
        assertEquals("Notes at https://example.org/a?b=1&c=%C3%A9. See (https://example.org/x_(y)) or https://example.org/end!", parts.joinToString("") { it.text })
    }

    @Test fun `unsupported, relative, empty and malformed destinations keep only their label`() {
        listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///etc/hosts", "/relative/page", "", "https://exa mple.org", "https://user@example.org/",
            "mailto:host@example.org", "https://").forEach { href ->
            val stored = ShowNotes.fromHtml("""Before <a href="$href">the label</a> after""")
            assertEquals(href, "Before the label after", stored)
            assertTrue(href, links(stored).isEmpty())
        }
        val missing = ShowNotes.fromHtml("""<a name="top">Anchor</a> <a data-href="https://example.org/">No href</a>""")
        assertEquals("Anchor No href", missing)
    }

    @Test fun `entities decode once and unknown ones stay as written`() {
        assertEquals("Tom & Jerry <3 “quoted” – é ç ã ' &lt; &bogus; é", ShowNotes.text(ShowNotes.fromHtml(
            "Tom &amp; Jerry &lt;3 &ldquo;quoted&rdquo; &ndash; &eacute; &ccedil; &atilde;&nbsp;&#39; &amp;lt; &bogus; &#xE9;")))
        assertEquals(listOf("A & B" to "https://example.org/?q=%26&x=1"), links(ShowNotes.fromHtml("""<a href='https://example.org/?q=%26&#38;x=1'>A &amp; B</a>""")))
    }

    @Test fun `a description without links is unchanged by the stored form`() {
        val plain = "Synthetic show notes with [brackets], a back\\slash and 5 < 6 > 4."
        assertEquals(listOf(ShowNotePart(plain)), ShowNotes.parts(plain))
        assertEquals(plain, ShowNotes.text(plain))
        assertEquals("Hello world", ShowNotes.fromHtml("<script>bad()</script><p>Hello</p><style>p{}</style>  <b>world</b>"))
    }

    @Test fun `labels with brackets and backslashes round trip and nothing is invented from surrounding text`() {
        val stored = ShowNotes.fromHtml("""See [1] <a href="https://example.org/a">[Part] \ two</a> then example.org/b and www.example.org""")
        assertEquals(listOf("[Part] \\ two" to "https://example.org/a"), links(stored))
        assertEquals("See [1] [Part] \\ two then example.org/b and www.example.org", ShowNotes.text(stored))
    }

    @Test fun `a label that is its own address is stored as the plain address`() {
        val stored = ShowNotes.fromHtml("""Site: <a href="https://example.org/page">https://example.org/page</a>""")
        assertEquals("Site: https://example.org/page", stored)
        assertEquals(listOf("https://example.org/page" to "https://example.org/page"), links(stored))
    }

    @Test fun `an unsafe address in stored text stays plain text`() {
        assertEquals(listOf(ShowNotePart("[x](<javascript:alert(1)>)")), ShowNotes.parts("[x](<javascript:alert(1)>)"))
        assertTrue(links("[x](<https://user@example.org/>)").isEmpty())
    }
}
