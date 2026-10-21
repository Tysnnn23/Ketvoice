package com.ketvoice.app.data

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Native TTML timestamps only. No interpolation, generated word times or phonetic splitting. */
object LyricsFormats {
    fun timeMs(value: String): Long? {
        val v = value.trim()
        if (v.isBlank()) return null
        return runCatching {
            when {
                v.endsWith("ms") -> v.dropLast(2).toDouble().toLong()
                v.endsWith("s") -> (v.dropLast(1).toDouble() * 1000).toLong()
                else -> (v.split(':').fold(0.0) { total, part -> total * 60 + part.toDouble() } * 1000).toLong()
            }.takeIf { it >= 0 }
        }.getOrNull()
    }

    fun ttml(xml: String): List<MusicApi.LyricLine> {
        if (xml.isBlank() || xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true)) return emptyList()
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            val nativeTranslations = mutableMapOf<String, MutableMap<String, String>>()
            val translations = doc.getElementsByTagNameNS("*", "translation")
            for (i in 0 until translations.length) {
                val translation = translations.item(i) as Element
                val language = translation.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang").lowercase()
                if (language.isBlank()) continue
                val texts = translation.getElementsByTagNameNS("*", "text")
                for (j in 0 until texts.length) {
                    val entry = texts.item(j) as Element
                    val key = entry.getAttribute("for")
                    if (key.isNotBlank() && entry.textContent.isNotBlank())
                        nativeTranslations.getOrPut(key) { mutableMapOf() }[language] = entry.textContent.trim()
                }
            }
            val paragraphs = doc.getElementsByTagNameNS("*", "p")
            (0 until paragraphs.length).mapNotNull { i ->
                val p = paragraphs.item(i) as Element
                val start = timeMs(p.getAttribute("begin")) ?: return@mapNotNull null
                val text = StringBuilder()
                val words = mutableListOf<MusicApi.LyricWord>()
                var nestedSyllables = false
                fun visit(node: Node, timedParent: Boolean = false) {
                    if (node.nodeType == Node.TEXT_NODE) {
                        text.append(node.nodeValue)
                        return
                    }
                    if (node !is Element) return
                    val role = node.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "role")
                    if (role.contains("translation") || role.contains("roman")) return
                    val begin = timeMs(node.getAttribute("begin"))
                    val end = timeMs(node.getAttribute("end"))
                    val timedChildren = (0 until node.childNodes.length).any { j ->
                        val child = node.childNodes.item(j)
                        child is Element && child.hasAttribute("begin")
                    }
                    if (node.localName == "span" && begin != null && !timedChildren) {
                        val segment = node.textContent
                        if (segment.isNotEmpty()) {
                            text.append(segment)
                            words += MusicApi.LyricWord(begin, segment, end ?: -1L)
                            if (timedParent) nestedSyllables = true
                        }
                    } else {
                        for (j in 0 until node.childNodes.length) {
                            val before = text.length
                            visit(node.childNodes.item(j), timedParent || (node.localName == "span" && begin != null))
                            // Preserve the source's spaces between timed spans.
                            if (words.isNotEmpty() && text.length > before &&
                                node.childNodes.item(j).nodeType == Node.TEXT_NODE
                            ) {
                                val tail = text.substring(before)
                                if (tail.isBlank()) {
                                    val last = words.last()
                                    words[words.lastIndex] = last.copy(text = last.text + tail)
                                }
                            }
                        }
                    }
                }
                visit(p)
                val full = text.toString().trim()
                val aligned = align(full, words)
                // Adjacent native timed spans inside one written word are
                // genuine sub-word segments, not a locally generated split.
                val subWord = aligned.zipWithNext().any { (a, b) ->
                    a.text.lastOrNull()?.isLetter() == true && b.text.firstOrNull()?.isLetter() == true
                }
                val key = (0 until p.attributes.length).map { p.attributes.item(it) }
                    .firstOrNull { it.localName == "key" }?.nodeValue.orEmpty()
                if (full.isBlank()) null else MusicApi.LyricLine(start, full, aligned,
                    aligned.isNotEmpty() && (nestedSyllables || subWord), nativeTranslations[key].orEmpty())
            }.sortedBy { it.timeMs }
        }.getOrDefault(emptyList())
    }

    /** Align existing segments to the exact line text, inserting only whitespace/punctuation, never timings. */
    fun align(text: String, segments: List<MusicApi.LyricWord>): List<MusicApi.LyricWord> {
        if (segments.isEmpty()) return emptyList()
        val out = mutableListOf<MusicApi.LyricWord>()
        var offset = 0
        for (word in segments) {
            val token = word.text.trim()
            if (token.isEmpty()) continue
            val at = text.indexOf(token, offset)
            if (at < 0) return emptyList()
            val gap = text.substring(offset, at)
            if (gap.any { it.isLetterOrDigit() }) return emptyList()
            if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(text = out.last().text + gap)
            out += word.copy(text = (if (out.isEmpty()) gap else "") + token)
            offset = at + token.length
        }
        if (out.isEmpty()) return emptyList()
        val tail = text.substring(offset)
        if (tail.any { it.isLetterOrDigit() }) return emptyList()
        out[out.lastIndex] = out.last().copy(text = out.last().text + tail)
        return out
    }
}
