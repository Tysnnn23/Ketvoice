package com.ketvoice.app.data

internal object DownloadValidation {
    fun complete(actualBytes: Long, expectedBytes: Long): Boolean =
        actualBytes >= 10_000 && (expectedBytes <= 0 || actualBytes == expectedBytes)

    fun audio(header: ByteArray): Boolean {
        if (header.size < 4) return false
        fun starts(value: String) = header.take(value.length).toByteArray()
            .contentEquals(value.toByteArray(Charsets.US_ASCII))
        val frame = (header[0].toInt() and 0xFF) == 0xFF &&
            (header[1].toInt() and 0xE0) == 0xE0
        val mp4 = header.size >= 8 && header.copyOfRange(4, 8)
            .contentEquals("ftyp".toByteArray(Charsets.US_ASCII))
        return starts("fLaC") || starts("ID3") || frame || mp4 || starts("OggS") || starts("RIFF")
    }
}
