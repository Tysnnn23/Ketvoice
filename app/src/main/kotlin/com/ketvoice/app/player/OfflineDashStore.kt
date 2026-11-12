@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ketvoice.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.PlaceholderDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.dash.offline.DashDownloader
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.RangedUri
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.ketvoice.app.data.CustomSources
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executor

/** Permanent user downloads are isolated from the evictable streaming cache. */
internal class OfflineDashCache(private val cache: Cache, private val upstream: DataSource.Factory) {
    private fun validHttps(uri: Uri): Boolean = runCatching {
        val parsed = java.net.URI(uri.toString())
        val host = parsed.host?.lowercase().orEmpty()
        parsed.scheme.equals("https", true) && parsed.userInfo == null && parsed.port in listOf(-1, 443) &&
            host.contains('.') && host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") &&
            !host.endsWith(".internal") && ':' !in host && !host.matches(Regex("[0-9.]+"))
    }.getOrDefault(false)
    private val verified = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun prefix(trackId: String) = hash(trackId) + ":"
    private fun keyFactory(trackId: String) = CacheKeyFactory { spec -> prefix(trackId) + hash(spec.key ?: spec.uri.toString()) }

    private val secureNetwork = DataSource.Factory {
        val delegate = upstream.createDataSource()
        object : DataSource by delegate {
            override fun open(dataSpec: DataSpec): Long {
                if (!validHttps(dataSpec.uri)) throw IOException("Public HTTPS required")
                return delegate.open(dataSpec)
            }
        }
    }

    private fun factory(trackId: String, offline: Boolean): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache).setCacheKeyFactory(keyFactory(trackId)).setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
        .setUpstreamDataSourceFactory(if (offline) PlaceholderDataSource.FACTORY else secureNetwork)
        .apply { if (offline) setCacheWriteDataSinkFactory(null) }

    private val parser = ParsingLoadable.Parser<DashManifest> { uri, input ->
        val bytes = readManifest(input)
        val text = String(bytes, Charsets.UTF_8)
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true))
            throw IOException("Unsupported DASH XML")
        if (Regex("<(?:[A-Za-z0-9_-]+:)?ContentProtection\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
            throw IOException("Protected DASH is not supported")
        val manifest = DashManifestParser().parse(uri, ByteArrayInputStream(bytes))
        plan(manifest)
        manifest
    }

    private data class Plan(val representation: Representation, val base: String, val init: RangedUri, val segments: List<RangedUri>)
    private fun plan(manifest: DashManifest): Plan {
        if (manifest.dynamic || manifest.periodCount != 1) throw IOException("Unsupported DASH timeline")
        val period = manifest.getPeriod(0)
        if (period.adaptationSets.size != 1 || period.adaptationSets.single().type != C.TRACK_TYPE_AUDIO)
            throw IOException("Only a single audio adaptation is supported")
        val representations = period.adaptationSets.single().representations
        if (representations.size != 1) throw IOException("Only a single audio representation is supported")
        val rep = representations.single()
        if (rep.format.drmInitData != null || rep.format.sampleMimeType != MimeTypes.AUDIO_FLAC)
            throw IOException("Only unprotected FLAC is supported")
        val base = rep.baseUrls.firstOrNull()?.url ?: throw IOException("Missing DASH base")
        if (!validHttps(Uri.parse(base))) throw IOException("Public HTTPS required")
        val init = rep.initializationUri ?: throw IOException("Missing initialization segment")
        val index = rep.index ?: throw IOException("Unsupported DASH index")
        val duration = manifest.getPeriodDurationUs(0)
        if (duration == C.TIME_UNSET || duration <= 0) throw IOException("Unknown duration")
        val count = index.getSegmentCount(duration)
        if (count !in 1..10_000) throw IOException("Invalid segment count")
        val segments = (0 until count).map { index.getSegmentUrl(index.firstSegmentNum + it) }
        if ((listOf(init) + segments).any { !validHttps(it.resolveUri(base)) })
            throw IOException("Public HTTPS required")
        return Plan(rep, base, init, segments)
    }

    private fun readManifest(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > 1_048_576) throw IOException("Manifest too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun downloader(trackId: String, manifestUrl: String): DashDownloader {
        require(CustomSources.reusableManifestUrl(manifestUrl))
        val item = MediaItem.Builder().setUri(manifestUrl).setMimeType(MimeTypes.APPLICATION_MPD).build()
        return DashDownloader(item, parser, factory(trackId, offline = false), Executor { it.run() })
    }

    fun playbackFactory(trackId: String): DataSource.Factory = factory(trackId, offline = true)
    fun manifestParser(): ParsingLoadable.Parser<DashManifest> = parser

    /** Every expected byte range must be cached; validation does not contact the network. */
    fun verify(trackId: String, manifestUrl: String): Long {
        require(CustomSources.reusableManifestUrl(manifestUrl))
        val source = factory(trackId, offline = true).createDataSource()
        val spec = DataSpec.Builder().setUri(manifestUrl).build()
        val manifest = DataSourceInputStream(source, spec).use { parser.parse(Uri.parse(manifestUrl), it) }
        val plan = plan(manifest)
        val factory = keyFactory(trackId)
        for ((index, range) in (listOf(plan.init) + plan.segments).withIndex()) {
            val part = DashUtil.buildDataSpec(plan.representation, plan.base, range, 0)
            val key = factory.buildCacheKey(part)
            val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            val length = if (range.length > 0) range.length else contentLength - range.start
            if (length <= 0 || !cache.isCached(key, range.start, length)) throw IOException("Incomplete DASH download")
            val headSize = minOf(length, 16_384L).toInt()
            val head = ByteArray(headSize)
            val reader = this.factory(trackId, offline = true).createDataSource()
            val headSpec = part.buildUpon().setLength(headSize.toLong()).build()
            DataSourceInputStream(reader, headSpec).use { input ->
                var used = 0
                while (used < head.size) {
                    val count = input.read(head, used, head.size - used)
                    if (count < 0) throw IOException("Incomplete DASH segment")
                    used += count
                }
            }
            val valid = if (index == 0) head.size >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp"
                else String(head, Charsets.ISO_8859_1).contains("moof")
            if (!valid) throw IOException("Invalid fragmented MP4")
        }
        val bytes = size(trackId)
        if (bytes < 10_000) throw IOException("DASH download too short")
        return bytes
    }

    fun verifyOnce(trackId: String, manifestUrl: String): Long {
        verified[trackId]?.takeIf { it.first == manifestUrl }?.let { return it.second }
        return verify(trackId, manifestUrl).also { verified[trackId] = manifestUrl to it }
    }

    fun size(trackId: String): Long = cache.keys.filter { it.startsWith(prefix(trackId)) }
        .sumOf { key -> cache.getCachedSpans(key).sumOf { it.length } }

    fun remove(trackId: String) {
        verified.remove(trackId)
        cache.keys.filter { it.startsWith(prefix(trackId)) }.forEach(cache::removeResource)
    }
}

internal object OfflineDashStore {
    private var instance: OfflineDashCache? = null

    @Synchronized fun get(context: Context): OfflineDashCache = instance ?: run {
        val app = context.applicationContext
        val cache = SimpleCache(File(app.filesDir, "downloads-dash-v1"), NoOpCacheEvictor(), StandaloneDatabaseProvider(app))
        val network = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false)
            .setConnectTimeoutMs(8_000).setReadTimeoutMs(12_000)
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
        OfflineDashCache(cache, network).also { instance = it }
    }
}
