package com.github.nols1000.hashiru.course.codec

import com.github.nols1000.hashiru.course.domain.CoursePoint
import com.github.nols1000.hashiru.course.domain.ParsedCourse

/**
 * Reads the positions out of a GPX 1.0 or 1.1 file: the track points of every track and segment in order, or the
 * route points where there is no track, with their elevation, and the first name of the file, its tracks or its
 * routes. Waypoints and extensions are skipped.
 *
 * A small scanner rather than an XML library: it takes tags with or without a namespace prefix, attributes in
 * either quotes, comments, CDATA and the predefined and numeric entities, and does not mind what it does not know.
 */
object GpxParser {
    private val gpxTag = Regex("""<(?:[\w.-]+:)?gpx[\s/>]""")

    /** True when [text] looks like a GPX file. */
    fun recognizes(text: String): Boolean = gpxTag.containsMatchIn(text.take(SNIFF_CHARS))

    /** The file's course, or null when [text] is not GPX. */
    fun parse(text: String): ParsedCourse? {
        if (!recognizes(text)) return null
        val track = mutableListOf<CoursePoint>()
        val route = mutableListOf<CoursePoint>()
        var name: String? = null
        val open = ArrayDeque<String>()
        // The point being read, from its start tag to its end tag.
        var lat: Double? = null
        var lon: Double? = null
        var ele: Double? = null
        val content = StringBuilder()

        fun parent(depth: Int) = open.getOrNull(open.size - 1 - depth)

        fun onText(text: String) {
            if (open.lastOrNull() in TEXT_TAGS) content.append(text)
        }

        fun onStart(tag: String, attributes: Map<String, String>) {
            if (tag == "trkpt" || tag == "rtept") {
                lat = attributes["lat"]?.trim()?.toDoubleOrNull()
                lon = attributes["lon"]?.trim()?.toDoubleOrNull()
                ele = null
            }
            if (tag in TEXT_TAGS) content.clear()
        }

        fun onEnd(tag: String) {
            when (tag) {
                "ele" -> if (parent(1) == "trkpt" || parent(1) == "rtept") ele = content.toString().trim().toDoubleOrNull()
                "name" -> if (name == null && parent(1) in NAMED) name = content.toString().trim().takeIf { it.isNotEmpty() }
                "trkpt", "rtept" -> {
                    val la = lat
                    val lo = lon
                    if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                        (if (tag == "trkpt") track else route) += CoursePoint(la, lo, ele)
                    }
                    lat = null
                    lon = null
                }
            }
        }

        var i = 0
        while (i < text.length) {
            if (text[i] != '<') {
                val end = text.indexOf('<', i).let { if (it < 0) text.length else it }
                onText(decode(text.substring(i, end)))
                i = end
                continue
            }
            when {
                text.startsWith("<!--", i) -> i = skipPast(text, i, "-->")
                text.startsWith("<![CDATA[", i) -> {
                    val end = text.indexOf("]]>", i).let { if (it < 0) text.length else it }
                    onText(text.substring(i + 9, end))
                    i = end + 3
                }
                text.startsWith("<?", i) -> i = skipPast(text, i, "?>")
                text.startsWith("<!", i) -> i = skipPast(text, i, ">")
                text.startsWith("</", i) -> {
                    val end = text.indexOf('>', i).let { if (it < 0) text.length else it }
                    val tag = localName(text.substring(i + 2, end).trim())
                    // Closes the innermost tag of that name; anything left open inside it is dropped.
                    val index = open.lastIndexOf(tag)
                    if (index >= 0) {
                        onEnd(tag)
                        while (open.size > index) open.removeLast()
                    }
                    i = end + 1
                }
                else -> {
                    val end = tagEnd(text, i)
                    val body = text.substring(i + 1, end)
                    val selfClosing = body.endsWith("/")
                    val inner = if (selfClosing) body.dropLast(1) else body
                    val nameEnd = inner.indexOfFirst { it.isWhitespace() }.let { if (it < 0) inner.length else it }
                    val tag = localName(inner.substring(0, nameEnd))
                    open.addLast(tag)
                    onStart(tag, attributes(inner.substring(nameEnd)))
                    if (selfClosing) {
                        onEnd(tag)
                        open.removeLast()
                    }
                    i = end + 1
                }
            }
        }
        return ParsedCourse(name, track.ifEmpty { route })
    }

    private fun skipPast(text: String, from: Int, marker: String) =
        text.indexOf(marker, from).let { if (it < 0) text.length else it + marker.length }

    /** The `>` that ends the tag starting at [from], ignoring any inside quoted attribute values. */
    private fun tagEnd(text: String, from: Int): Int {
        var quote: Char? = null
        for (i in from + 1 until text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
        }
        return text.length
    }

    private fun localName(qualified: String) = qualified.substringAfter(':').lowercase()

    private val attribute = Regex("""([\w.:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private fun attributes(text: String): Map<String, String> =
        attribute.findAll(text).associate { m -> localName(m.groupValues[1]) to decode(m.groupValues[2].ifEmpty { m.groupValues[3] }) }

    private val entity = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);""")

    private fun decode(text: String): String {
        if ('&' !in text) return text
        return entity.replace(text) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.let(::codePoint) ?: m.value
                else -> ENTITIES.getValue(e)
            }
        }
    }

    private fun codePoint(value: Int): String = when {
        value < 0 || value > 0x10FFFF -> ""
        value < 0x10000 -> value.toChar().toString()
        else -> {
            val v = value - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }

    private const val SNIFF_CHARS = 4096
    private val TEXT_TAGS = setOf("ele", "name")
    private val NAMED = setOf("metadata", "trk", "rte", "gpx")
    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")
}
