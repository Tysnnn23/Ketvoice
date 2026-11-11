package com.ketvoice.app.data

internal object StreamQuality {
    fun rank(stream: MusicApi.StreamInfo): Int {
        val format = stream.format.lowercase()
        if (format.contains("flac") || format.contains("wav") ||
            stream.codec?.contains("flac", ignoreCase = true) == true) return 10000
        if (stream.quality == "YouTube Music") return 192
        return Regex("(\\d{2,4})\\s*k(?:b(?:it)?)?(?:ps|/s)?", RegexOption.IGNORE_CASE)
            .find(stream.quality)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 9999) ?: 128
    }
}
