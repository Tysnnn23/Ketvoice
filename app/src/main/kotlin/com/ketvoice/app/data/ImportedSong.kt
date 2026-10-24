package com.ketvoice.app.data

object ImportedSong {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    fun id(track: Track?): String? = track?.id?.takeIf { it.startsWith("kv:upload:") }
        ?.removePrefix("kv:upload:")?.takeIf(uuid::matches)?.lowercase()
    fun cacheTag(immutableMediaUrl: String): String = "upload:$immutableMediaUrl"
}
