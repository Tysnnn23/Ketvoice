package com.ketvoice.desktop.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Random-access bytes for the FFmpeg reader: a local file or an HTTP stream read by ranges. */
interface ByteSource : Closeable {
    /** Total length in bytes, or -1 when the server does not say. */
    val size: Long
    /** Reads up to [len] bytes at [position]; returns the count, or -1 at the end. Blocks while bytes are on their way. */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, len: Int): Int
}

class FileByteSource(file: File) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
        if (position >= size) return -1
        raf.seek(position)
        return raf.read(buffer, offset, len)
    }

    override fun close() = raf.close()
}
