package android.net

import java.io.File
import java.net.URI

/** The parts of android.net.Uri the shared code reads: parse, fromFile, scheme, path. */
class Uri private constructor(private val text: String) {
    private val parsed: URI? = runCatching { URI(text) }.getOrNull()

    val scheme: String? get() = parsed?.scheme ?: text.substringBefore(':', "").ifEmpty { null }
    /** Decoded path, as on Android. */
    val path: String? get() = parsed?.path ?: text.substringAfter(':', "").substringBefore('?').substringBefore('#').ifEmpty { null }
    val host: String? get() = parsed?.host
    val lastPathSegment: String? get() = path?.trimEnd('/')?.substringAfterLast('/')?.ifEmpty { null }

    override fun toString(): String = text
    override fun equals(other: Any?): Boolean = other is Uri && other.text == text
    override fun hashCode(): Int = text.hashCode()

    companion object {
        @JvmStatic fun parse(uriString: String): Uri = Uri(uriString)
        @JvmStatic fun fromFile(file: File): Uri = Uri(file.toURI().toString().replaceFirst("file:/", "file:///").replace("file:////", "file:///"))
    }
}
