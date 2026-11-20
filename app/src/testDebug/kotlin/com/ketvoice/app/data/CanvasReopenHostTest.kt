package com.ketvoice.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 7.5 (owner: « je dois toujours attendre aussi longtemps pour charger le Canvas de Legends Never Dies »): the
 * second opening of an album shows the Canvas kept on the device without any request, even while another
 * title's lookup holds the network. A fake network counts the requests and the bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CanvasReopenHostTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val requests = AtomicInteger()
    private val bytes = AtomicLong()
    private var gate: CompletableDeferred<Unit>? = null

    private val intro = Track("t-intro", "Anxiety (Intro)", "Juice WRLD", "Legends Never Dies", spotifyId = "sp-intro")
    private val conversations = Track("t-conv", "Conversations", "Juice WRLD", "Legends Never Dies", spotifyId = "sp-conv")
    private val other = Track("t-other", "Lucid Dreams", "Juice WRLD", "Goodbye & Good Riddance", spotifyId = "sp-other")
    private val link = "https://cdn.example.org/clips/legends.mp4"
    private val published = Track("t-link", "Righteous", "Juice WRLD", "Legends Never Dies (Edition)", canvasUrl = link)

    /** A new session of the app: what is in memory is gone, what is on the device stays. */
    private fun restart() {
        val repo = CanvasRepo
        CanvasRepo::class.java.getDeclaredField("appContext").apply { isAccessible = true }.set(repo, null)
        @Suppress("UNCHECKED_CAST")
        (CanvasRepo::class.java.getDeclaredField("_paths").apply { isAccessible = true }.get(repo)
            as kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>).value = emptyMap()
        runCatching { (CanvasRepo::class.java.getDeclaredField("shared").apply { isAccessible = true }.get(repo) as MutableSet<*>).clear() }
        CanvasRepo.resetAuth()
        CanvasRepo.init(context)
        requests.set(0); bytes.set(0)
    }

    private fun clip(dest: File): File {
        val body = ByteArray(200_000).also { b -> "\u0000\u0000\u0000\u0018ftypmp42".toByteArray(Charsets.ISO_8859_1).copyInto(b) }
        dest.parentFile?.mkdirs(); dest.writeBytes(body); bytes.addAndGet(body.size.toLong()); return dest
    }

    @Before fun fakeNetwork() {
        File(context.filesDir, "canvas").deleteRecursively()
        context.getSharedPreferences("ketvoice_canvas", Context.MODE_PRIVATE).edit().clear().commit()
        CanvasRepo.mayFetch = { _, _ -> true }
        AppSettings.canvasWithMobileData.value = true
        CanvasRepo.lookup = { track ->
            requests.incrementAndGet()
            gate?.takeIf { track.id == other.id }?.await()
            delay(50)
            when (track.spotifyId) {
                "sp-conv" -> KetvoiceApi.CanvasResult("https://canvaz.example/conv.cnvs.mp4", "sp-conv")
                "sp-other" -> KetvoiceApi.CanvasResult("https://canvaz.example/other.cnvs.mp4", "sp-other")
                else -> null // « Anxiety (Intro) » has no Canvas of its own.
            }
        }
        CanvasRepo.fetchFile = { _, dest, _ -> requests.incrementAndGet(); clip(dest) }
        restart()
    }

    @After fun realNetwork() {
        CanvasRepo.lookup = { KetvoiceApi.canvas(it) }
        AppSettings.canvasWithMobileData.value = false
        gate?.complete(Unit)
    }

    @Test fun theAlbumCanvasShowsAgainWithoutAnyRequest() = runBlocking {
        // First session: a title of the album brings its Canvas, the intro (the header's title) has none.
        CanvasRepo.resolve(conversations)
        CanvasRepo.resolve(intro)
        val first = requests.get() to bytes.get()
        // Second opening, after a restart: the header asks for the intro again.
        restart()
        CanvasRepo.resolve(intro)
        val second = requests.get() to bytes.get()
        println("canvas-reopen: first open ${first.first} requests ${first.second} bytes; second open ${second.first} requests ${second.second} bytes")
        assertEquals("the album's Canvas stands in for the intro", "sp-conv.mp4", CanvasRepo.paths.value[intro.id]?.let { File(it).name })
        assertEquals("no request on the second opening", 0, second.first)
        assertEquals(0L, second.second)
    }

    @Test fun aKeptCanvasShowsAtOnceWhileAnotherLookupHoldsTheNetwork() = runBlocking {
        CanvasRepo.resolve(conversations)
        CanvasRepo.resolve(published) // the published link plays from its address and is kept meanwhile
        delay(400)
        restart()
        CanvasRepo.paths.value.let { assertTrue(it.containsKey(conversations.id)) }
        // Another title's lookup is slow (the network) and holds the lock.
        gate = CompletableDeferred()
        val slow = async(kotlinx.coroutines.Dispatchers.IO) { CanvasRepo.resolve(other) }
        delay(100)
        val started = System.nanoTime()
        val shown = async(kotlinx.coroutines.Dispatchers.IO) { CanvasRepo.resolve(intro); CanvasRepo.resolve(published) }
        delay(300)
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        val introShown = CanvasRepo.paths.value[intro.id]
        val linkShown = CanvasRepo.paths.value[published.id]
        gate!!.complete(Unit); slow.await(); shown.await()
        println("canvas-reopen: kept Canvas checked after $waitedMs ms while another lookup held the network")
        assertNotNull("the album's Canvas does not wait for another title's lookup", introShown)
        assertEquals("the kept link Canvas stays shown", CanvasRepo.linkName(link) + ".mp4", linkShown?.let { File(it).name })
    }

    @Test fun aCanvasInUseIsTouchedSoTheLimitKeepsIt() = runBlocking {
        CanvasRepo.resolve(conversations)
        val file = File(CanvasRepo.paths.value.getValue(conversations.id))
        file.setLastModified(System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000)
        restart()
        CanvasRepo.resolve(conversations)
        assertTrue("used lately", System.currentTimeMillis() - file.lastModified() < 60_000)
    }
}
