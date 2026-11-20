package com.ketvoice.app.data

import android.content.Context
import android.net.Uri
import coil.intercept.Interceptor
import coil.request.ImageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class PermanentImageInterceptor(context: Context) : Interceptor {
    private val dir = File(context.filesDir, "artwork-permanent").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()
    /** Addresses whose picture is saved, found once per session (7.5). */
    private val saved = ConcurrentHashMap<String, File>()
    /** Opens a connection; the host tests count them. */
    internal var open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = when (val data = chain.request.data) {
            is String -> data
            is Uri -> data.toString()
            else -> return chain.proceed(chain.request)
        }
        if (!url.startsWith("https://")) return chain.proceed(chain.request)
        // 7.5: a cover already saved goes on at once, on the caller's thread, so that Coil finds it in its memory
        // cache in the same frame instead of after a hop to the disk threads (the cover flashed empty, then faded in).
        saved[url]?.takeIf { it.isFile }?.let { return chain.proceed(chain.request.newBuilder().data(it).build()) }
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val local = withContext(Dispatchers.IO) {
            locks.getOrPut(key) { Mutex() }.withLock {
                val dest = File(dir, key)
                if (dest.isFile && dest.length() > 0) return@withLock dest.also { DiskBudget.touch(it); saved[url] = it }
                var c: HttpURLConnection? = null
                val tmp = File(dir, "$key.part")
                try {
                    c = open(URL(url)).apply {
                        connectTimeout = 8000; readTimeout = 12000
                        setRequestProperty("User-Agent", "Ketvoice/2.0")
                    }
                    if (c.responseCode !in 200..299) return@withLock null
                    val type = c.contentType.orEmpty()
                    if (!type.startsWith("image/") && type != "application/octet-stream") return@withLock null
                    c.inputStream.use { input -> tmp.outputStream().use { output ->
                        val buffer = ByteArray(16384)
                        var total = 0
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= 25_000_000)
                            output.write(buffer, 0, n)
                        }
                    } }
                    if (tmp.length() > 0 && tmp.renameTo(dest)) dest.also { saved[url] = it } else null
                } catch (_: Exception) { null }
                finally { c?.disconnect(); tmp.delete() }
            }
        }
        return chain.proceed(if (local != null) chain.request.newBuilder().data(local).build() else chain.request)
    }
}
