package com.ketvoice.app.sync

/** The links Ketvoice opens for people: an invitation to a shared playlist and a profile page. */
object SharedLinks {
    /** The alphabet of invitation codes (no 0, 1, I or O). */
    private val code = Regex("[A-HJ-NP-Z2-9]{10}")
    private val join = Regex("ketvoice://join/([A-Za-z0-9]{1,32})/?(?:[?#].*)?", RegexOption.IGNORE_CASE)
    private val profile = Regex("ketvoice://u/@?([A-Za-z0-9_.]{3,24})/?(?:[?#].*)?", RegexOption.IGNORE_CASE)

    /** The code of a `ketvoice://join/<code>` link, upper-cased; null when the link is not an invitation link. */
    fun joinCode(link: String): String? = join.matchEntire(link.trim())?.groupValues?.get(1)?.uppercase()

    /** A code as typed: spaces and dashes dropped, upper-cased; null when it cannot be a code. */
    fun cleanCode(typed: String): String? = typed.filterNot { it.isWhitespace() || it == '-' }.uppercase().takeIf { code.matches(it) }

    /** The handle of a `ketvoice://u/<handle>` link, lower-cased. */
    fun profileHandle(link: String): String? = profile.matchEntire(link.trim())?.groupValues?.get(1)?.lowercase()

    fun profileLink(handle: String): String = "ketvoice://u/" + handle.removePrefix("@").lowercase()
}
