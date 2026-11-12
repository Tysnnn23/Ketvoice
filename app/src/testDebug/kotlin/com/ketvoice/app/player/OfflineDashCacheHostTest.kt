@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ketvoice.app.player

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.DownloadTask
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.security.MessageDigest
import org.json.JSONObject

/** Exercises Media3 download/cache behavior with fixtures, not a real decoder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineDashCacheHostTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manifestUrl = "https://ketvoice-lossless.onrender.com/tidal-mpd/42?quality=low"
    private lateinit var cache: SimpleCache
    private lateinit var store: OfflineDashCache
    private var networkReads = 0
    private var slowReads = false
    private var openedSegment = CompletableDeferred<Unit>()
    private var previousStore: Any? = null
    private var previousContext: Any? = null
    private var previousBusy = emptySet<String>()
    private var previousTasks = emptyMap<String, DownloadTask>()
    private var previousProgress = emptyMap<String, Int>()
    private var previousWifiOnly = false
    private var previousItems = emptyList<Downloader.Item>()
    private var previousQueueStore: Any? = null
    private var previousAudioCache: Any? = null
    private var previousAudioContext: Any? = null
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private val manifest = """
        <?xml version="1.0" encoding="UTF-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT2S" minBufferTime="PT1S">
          <Period duration="PT2S"><AdaptationSet mimeType="audio/mp4" codecs="flac">
            <Representation id="audio" bandwidth="1000000" audioSamplingRate="44100">
              <BaseURL>https://cdn.example/</BaseURL>
              <SegmentTemplate timescale="1" duration="1" startNumber="1" initialization="init.mp4" media="${'$'}Number${'$'}.m4s"/>
            </Representation>
          </AdaptationSet></Period>
        </MPD>
    """.trimIndent()

    private fun box(name: String, size: Int): ByteArray = ByteArray(size).also {
        name.toByteArray().copyInto(it, destinationOffset = 4)
    }

    @Before fun setup() {
        cache = SimpleCache(File(context.cacheDir, "dash-test-${UUID.randomUUID()}"), NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context))
        val assets = mapOf(manifestUrl to manifest.toByteArray(), "https://cdn.example/init.mp4" to box("ftyp", 1024),
            "https://cdn.example/1.m4s" to box("moof", 12_000), "https://cdn.example/2.m4s" to box("moof", 12_000))
        val upstream = DataSource.Factory {
            val delegate = ByteArrayDataSource(ByteArrayDataSource.UriResolver { uri ->
                networkReads++
                if (uri.path?.endsWith(".m4s") == true) openedSegment.complete(Unit)
                assets[uri.toString()] ?: throw IOException("Fixture not found")
            })
            object : DataSource by delegate {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (slowReads && delegate.uri?.path?.endsWith(".m4s") == true) Thread.sleep(10)
                    return delegate.read(buffer, offset, if (slowReads) minOf(length, 512) else length)
                }
            }
        }
        store = OfflineDashCache(cache, upstream)
        previousStore = field(OfflineDashStore, "instance").get(OfflineDashStore)
        previousContext = field(Downloader, "ctx").get(Downloader)
        previousQueueStore = field(Downloader, "queueStore").get(Downloader)
        previousItems = Downloader.items.value
        previousAudioCache = field(AudioCache, "cache").get(AudioCache)
        previousAudioContext = field(AudioCache, "context").get(AudioCache)
        previousBusy = Downloader.busy.value
        previousTasks = Downloader.tasks.value
        previousProgress = Downloader.progress.value
        previousWifiOnly = AppSettings.downloadsWifiOnly.value
        field(OfflineDashStore, "instance").set(OfflineDashStore, store)
        field(Downloader, "ctx").set(Downloader, null)
        field(Downloader, "queueStore").set(Downloader, null)
        AppSettings.downloadsWifiOnly.value = false
    }

    @After fun cleanup() {
        field(OfflineDashStore, "instance").set(OfflineDashStore, previousStore)
        field(Downloader, "ctx").set(Downloader, previousContext)
        field(Downloader, "queueStore").set(Downloader, previousQueueStore)
        (Downloader.items as MutableStateFlow<List<Downloader.Item>>).value = previousItems
        (Downloader.busy as MutableStateFlow<Set<String>>).value = previousBusy
        (Downloader.tasks as MutableStateFlow<Map<String, DownloadTask>>).value = previousTasks
        (Downloader.progress as MutableStateFlow<Map<String, Int>>).value = previousProgress
        AppSettings.downloadsWifiOnly.value = previousWifiOnly
        val audioCache = field(AudioCache, "cache").get(AudioCache)
        if (audioCache !== previousAudioCache) (audioCache as? SimpleCache)?.release()
        field(AudioCache, "cache").set(AudioCache, previousAudioCache)
        field(AudioCache, "context").set(AudioCache, previousAudioContext)
        @Suppress("UNCHECKED_CAST")
        (field(Downloader, "paused").get(Downloader) as MutableSet<String>).remove("paused-fixture")
        cache.release()
    }

    @Test fun completeDownloadCanBeVerifiedOfflineAndDeletionIsTrackScoped() {
        store.downloader("first", manifestUrl).download(null)
        store.downloader("second", manifestUrl).download(null)
        val before = networkReads
        assertTrue(store.verify("first", manifestUrl) > 10_000)
        assertTrue(store.verify("second", manifestUrl) > 10_000)
        assertEquals("Verification must use cached bytes only", before, networkReads)
        store.remove("first")
        assertEquals(0L, store.size("first"))
        assertTrue(store.verify("second", manifestUrl) > 10_000)
    }

    @Test fun protectedAndDynamicManifestsAreRejected() {
        val protected = manifest.replace("<Representation id=", "<ContentProtection schemeIdUri=\"test\"/><Representation id=")
        assertThrows(IOException::class.java) {
            store.manifestParser().parse(Uri.parse(manifestUrl), ByteArrayInputStream(protected.toByteArray()))
        }
        val live = manifest.replace("type=\"static\"", "type=\"dynamic\"")
        assertThrows(IOException::class.java) {
            store.manifestParser().parse(Uri.parse(manifestUrl), ByteArrayInputStream(live.toByteArray()))
        }
        val dtd = "<!DOCTYPE MPD [<!ENTITY x SYSTEM 'file:///secret'>]>" + manifest
        assertThrows(IOException::class.java) {
            store.manifestParser().parse(Uri.parse(manifestUrl), ByteArrayInputStream(dtd.toByteArray()))
        }
        val insecure = manifest.replace("https://cdn.example/", "http://cdn.example/")
        assertThrows(IOException::class.java) {
            store.manifestParser().parse(Uri.parse(manifestUrl), ByteArrayInputStream(insecure.toByteArray()))
        }
        val lossy = manifest.replace("codecs=\"flac\"", "codecs=\"mp4a.40.2\"")
        assertThrows(IOException::class.java) {
            store.manifestParser().parse(Uri.parse(manifestUrl), ByteArrayInputStream(lossy.toByteArray()))
        }
    }

    @Test fun signedOrUnknownManifestUrlsAreNotPersisted() {
        assertThrows(IllegalArgumentException::class.java) {
            store.downloader("first", "$manifestUrl&token=secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.downloader("first", "https://cdn.example/manifest.mpd")
        }
    }

    @Test fun downloaderPauseAndResumeDoesNotMarkAnIncompleteTrackComplete() = runBlocking {
        slowReads = true
        (Downloader.busy as MutableStateFlow<Set<String>>).value = setOf("paused-fixture")
        val job = async(kotlinx.coroutines.Dispatchers.IO) {
            Downloader.fetchDash(context, "paused-fixture", manifestUrl, Long.MAX_VALUE) { false }
        }
        withTimeout(5_000) { openedSegment.await() }
        Downloader.pause("paused-fixture")
        delay(400)
        assertFalse("A paused transfer must not be reported complete", job.isCompleted)
        Downloader.resume("paused-fixture")
        assertTrue(withTimeout(10_000) { job.await() }!! > 10_000)
        assertTrue(store.verify("paused-fixture", manifestUrl) > 10_000)
    }

    @Test fun exceedingTheBudgetDoesNotCreateACompleteOfflineItem() = runBlocking {
        val result = Downloader.fetchDash(context, "limited", manifestUrl, 500) { false }
        assertNull(result)
        assertThrows(Exception::class.java) { store.verify("limited", manifestUrl) }
        Unit
    }

    @Test fun downloadedDashIsRestoredAndDeletionNeverRemovesAnotherTrack() {
        val directory = File(context.filesDir, "offline-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        fun save(id: String) {
            store.downloader(id, manifestUrl).download(null)
            val stem = MessageDigest.getInstance("SHA-1").digest(id.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(24)
            val folder = File(directory, "downloads").apply { mkdirs() }
            val marker = File(folder, "$stem.mpd")
            marker.writeText("KETVOICE_DASH_OFFLINE_V1")
            File(folder, "$stem.json").writeText(JSONObject().put("id", id).put("title", "Song")
                .put("artist", "Artist").put("album", "Album").put("file", marker.name)
                .put("format", "DASH · FLAC").put("dashManifestUrl", manifestUrl).toString())
        }
        save("first")
        save("second")
        Downloader.init(isolated)
        val first = requireNotNull(Downloader.itemFor("first"))
        assertEquals(manifestUrl, first.dashManifestUrl)
        assertTrue(Downloader.storedBytes(first) > 10_000)
        val track = Track("first", "Song", "Artist", "Album")
        val stream = MusicApi.StreamInfo(manifestUrl, "dash", "lossless", mimeType = "application/dash+xml")
        val item = playbackMediaItem(track, manifestUrl, null, stream, offlineDash = true)
        val transferred = androidx.media3.common.MediaItem.fromBundle(item.toBundleIncludeLocalConfiguration())
        assertTrue(transferred.mediaMetadata.extras?.getBoolean(OFFLINE_DASH_EXTRA) == true)
        assertTrue(playbackMediaSourceFactory(isolated).createMediaSource(transferred) is androidx.media3.exoplayer.dash.DashMediaSource)
        assertTrue(Downloader.delete(first))
        assertNull(Downloader.itemFor("first"))
        assertTrue(store.verify("second", manifestUrl) > 10_000)
        assertNotNull(Downloader.itemFor("second"))
    }

    @Test fun retryNeverOverwritesAnUnreadableExistingDownload() {
        val directory = File(context.filesDir, "offline-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val id = "damaged"
        val stem = MessageDigest.getInstance("SHA-1").digest(id.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        val folder = File(directory, "downloads").apply { mkdirs() }
        val audio = File(folder, "$stem.flac").apply { writeText("original audio fixture") }
        val metadata = File(folder, "$stem.json").apply { writeText("{broken") }
        Downloader.init(isolated)
        assertNull(Downloader.itemFor(id))
        assertNull(Downloader.download(Track(id, "Song", "Artist", "Album")))
        assertEquals("original audio fixture", audio.readText())
        assertEquals("{broken", metadata.readText())
    }
}
