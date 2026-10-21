package com.ketvoice.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.MusicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Stable per-recording/provider keys: refreshed signed URLs reuse the exact cached bytes. */
@OptIn(UnstableApi::class)
object AudioCache {
    const val STEP = 500_000_000L
    const val MAX = 10_000_000_000L
    private var cache: SimpleCache? = null
    private var context: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writers = ConcurrentHashMap<String, CacheWriter>()
    @Volatile private var epoch = 0

    @Synchronized fun init(ctx: Context) {
        if (cache != null) return
        context = ctx.applicationContext
        cache = SimpleCache(File(ctx.filesDir, "songs-v2"), DynamicLru(), StandaloneDatabaseProvider(ctx))
    }
    fun key(trackId: String, stream: MusicApi.StreamInfo): String {
        val raw = "$trackId|${stream.cacheTag.ifBlank { stream.url }}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun source(ctx: Context): CacheDataSource.Factory {
        init(ctx)
        return CacheDataSource.Factory().setCache(cache!!).setUpstreamDataSourceFactory(
            DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000).setReadTimeoutMs(12000)
                .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
        ).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
    fun factory(context: Context): DataSource.Factory = source(context)

    /** Cache a real initial audio block before switching quality. */
    suspend fun warm(trackId: String, stream: MusicApi.StreamInfo, positionMs: Long = 0, durationMs: Long = 0): Boolean = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext false
        val key = key(trackId, stream)
        val length = ContentMetadata.getContentLength(cache!!.getContentMetadata(key))
        // Approximate the byte region only for prefetch; the media engine performs the real seek.
        val offset = if (durationMs > 0 && length > 0) (length * (positionMs.toDouble() / durationMs)).toLong().coerceAtLeast(0) else 0L
        val count = if (length > 0) minOf(384 * 1024L, (length - offset).coerceAtLeast(1)) else 384 * 1024L
        if (cache!!.getCachedBytes(key, offset, count) >= count) return@withContext true
        val writer = CacheWriter(source(ctx).createDataSource(), DataSpec.Builder().setUri(stream.url).setKey(key)
            .setPosition(offset).setLength(count).build(), null, null)
        val token = epoch
        writers["warm:$key"] = writer
        try { writer.cache() } catch (_: Exception) { /* EOF on a short file can still be usable. */ }
        finally { writers.remove("warm:$key", writer) }
        token == epoch && cache!!.getCachedBytes(key, offset, count) >= minOf(count, 64 * 1024L)
    }

    /** Finish caching the song in the background, bounded by the user's LRU budget. */
    fun cacheWhole(trackId: String, stream: MusicApi.StreamInfo) {
        val ctx = context ?: return
        val key = key(trackId, stream)
        val token = epoch
        scope.launch {
            val writer = CacheWriter(source(ctx).createDataSource(), DataSpec.Builder().setUri(stream.url).setKey(key).build(), null, null)
            if (writers.putIfAbsent(key, writer) != null) return@launch
            try {
                writer.cache()
                if (token == epoch) {
                    ctx.getSharedPreferences("audio-index-v2", Context.MODE_PRIVATE).edit()
                        .putString("$trackId|${tier(stream)}", org.json.JSONObject().put("key", key).put("url", stream.url)
                            .put("format", stream.format).put("quality", stream.quality).put("tag", stream.cacheTag).toString()).apply()
                }
            } catch (_: Exception) { /* Playback may still use the partial spans. */ }
            finally { writers.remove(key, writer) }
        }
    }
    private fun tier(s: MusicApi.StreamInfo) = if (s.format.contains("flac", true)) "flac" else "audio"
    fun complete(trackId: String, lossless: Boolean): MusicApi.StreamInfo? {
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences("audio-index-v2", Context.MODE_PRIVATE)
        val tiers = if (lossless) listOf("flac", "audio") else listOf("audio", "flac")
        for (tier in tiers) {
            val s = runCatching {
                val j = org.json.JSONObject(prefs.getString("$trackId|$tier", null) ?: return@runCatching null)
                val key = j.getString("key")
                val length = ContentMetadata.getContentLength(cache!!.getContentMetadata(key))
                if (length > 0 && cache!!.isCached(key, 0, length)) MusicApi.StreamInfo(j.getString("url"),
                    j.getString("format"), j.getString("quality"), j.getString("tag")) else null
            }.getOrNull()
            if (s != null) return s
        }
        return null
    }
    fun clear(context: Context) {
        init(context)
        epoch++
        writers.values.forEach { it.cancel() }
        cache!!.keys.toList().forEach { key -> runCatching { cache!!.removeResource(key) } }
        context.getSharedPreferences("audio-index-v2", Context.MODE_PRIVATE).edit().clear().apply()
        // Legacy 1.x audio cache only. Never touch downloads, lyrics, canvas or image archives.
        File(context.cacheDir, "songs").deleteRecursively()
    }
    fun trimTo(context: Context, maxBytes: Long) {
        init(context)
        synchronized(cache!!) {
            val spans = cache!!.keys.flatMap { cache!!.getCachedSpans(it) }.sortedBy { it.lastTouchTimestamp }
            for (span in spans) {
                if (cache!!.cacheSpace <= maxBytes) break
                runCatching { cache!!.removeSpan(span) }
            }
        }
    }
    fun sizeBytes(context: Context): Long {
        init(context)
        val legacy = File(context.cacheDir, "songs").takeIf { it.exists() }
            ?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        return cache!!.cacheSpace + legacy
    }

    private class DynamicLru : CacheEvictor {
        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() = Unit
        private fun evict(cache: Cache, incoming: Long) {
            val limit = AppSettings.cacheBytes.value.coerceIn(STEP, MAX)
            if (cache.cacheSpace + incoming <= limit) return
            val spans = cache.keys.flatMap { cache.getCachedSpans(it) }.sortedBy { it.lastTouchTimestamp }
            for (span in spans) {
                if (cache.cacheSpace + incoming <= limit) break
                cache.removeSpan(span)
            }
        }
        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) = evict(cache, if (length == C.LENGTH_UNSET.toLong()) 0 else length)
        override fun onSpanAdded(cache: Cache, span: CacheSpan) = evict(cache, 0)
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) = Unit
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
    }
}
