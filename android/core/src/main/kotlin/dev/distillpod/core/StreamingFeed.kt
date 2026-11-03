package dev.distillpod.core

import java.io.FilterInputStream
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import org.w3c.dom.Element
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

/** Read only recent entries, with independent byte, tree, depth and text limits. */
fun parseFeedStream(identity: String, input: InputStream, baseUrl: String, maximumEpisodes: Int = 200): Feed {
    require(maximumEpisodes in 1..1000)
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
    val stack = mutableListOf<Element>()
    val mappings = linkedMapOf<String, String>()
    var episodes = 0
    var textSize = 0
    var nodes = 0
    class EnoughEntries : SAXException()
    val handler = object : DefaultHandler2() {
        override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("Feed DTDs are prohibited") }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("External entities are prohibited") }
        override fun startPrefixMapping(prefix: String, uri: String) { mappings[prefix] = uri }
        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            require(stack.size < 64 && ++nodes <= 200000) { "Feed structure exceeds the supported size" }
            val element = document.createElementNS(uri.takeIf { it.isNotBlank() }, qName.ifBlank { localName })
            mappings.forEach { (prefix, namespace) -> element.setAttributeNS("http://www.w3.org/2000/xmlns/", if (prefix.isBlank()) "xmlns" else "xmlns:$prefix", namespace) }
            mappings.clear()
            repeat(attributes.length) { i -> element.setAttributeNS(attributes.getURI(i).takeIf { it.isNotBlank() }, attributes.getQName(i).ifBlank { attributes.getLocalName(i) }, attributes.getValue(i)) }
            if (stack.isEmpty()) document.appendChild(element) else stack.last().appendChild(element)
            stack.add(element)
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            textSize += length
            require(textSize <= 10_000_000) { "Recent feed content exceeds the supported size" }
            if (stack.isNotEmpty()) stack.last().appendChild(document.createTextNode(String(ch, start, length)))
        }
        override fun endElement(uri: String, localName: String, qName: String) {
            val item = stack.removeAt(stack.lastIndex)
            val name = item.localName ?: item.tagName
            val parent = stack.lastOrNull()?.let { it.localName ?: it.tagName }
            if ((name == "item" && parent == "channel" || name == "entry" && parent == "feed") && ++episodes >= maximumEpisodes) throw EnoughEntries()
        }
    }
    val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
    reader.contentHandler = handler
    reader.entityResolver = handler
    reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
    val bounded = object : FilterInputStream(input) {
        private var bytes = 0L
        private fun count(n: Int): Int { if (n > 0) bytes += n; require(bytes <= 40_000_000) { "Recent feed response exceeds 40 MB" }; return n }
        override fun read(): Int { val byte = super.read(); if (byte >= 0) count(1); return byte }
        override fun read(b: ByteArray, off: Int, len: Int): Int = count(`in`.read(b, off, len))
    }
    try { reader.parse(InputSource(bounded)) } catch (_: EnoughEntries) { }
    require(document.documentElement != null) { "Empty podcast feed" }
    return feedFromDocument(identity, document, baseUrl)
}
