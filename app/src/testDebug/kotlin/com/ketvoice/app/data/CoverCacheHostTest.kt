package com.ketvoice.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.size.Size
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 7.5: a saved cover opens again from the device without a request, and without leaving the caller's thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoverCacheHostTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class Chain(override val request: ImageRequest, val seen: MutableList<Pair<Any, Thread>>) : Interceptor.Chain {
        override val size: Size = Size.ORIGINAL
        override fun withSize(size: Size): Interceptor.Chain = this
        override fun withRequest(request: ImageRequest): Interceptor.Chain = Chain(request, seen)
        override suspend fun proceed(request: ImageRequest): ImageResult {
            seen += request.data to Thread.currentThread()
            return ErrorResult(null, request, IllegalStateException("not decoded in this test"))
        }
    }

    @Test fun theSecondOpeningIsServedFromTheDeviceOnTheCallersThread() = runBlocking {
        File(context.filesDir, "artwork-permanent").deleteRecursively()
        val requests = AtomicInteger(); val bytes = AtomicLong()
        val jpeg = ByteArray(48_000) { (it % 251).toByte() }
        val interceptor = PermanentImageInterceptor(context)
        interceptor.open = { url ->
            requests.incrementAndGet()
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentType() = "image/jpeg"
                override fun getInputStream() = ByteArrayInputStream(jpeg).also { bytes.addAndGet(jpeg.size.toLong()) }
            }
        }
        val url = "https://cdn-images.dzcdn.net/images/cover/legends/1000x1000-000000-80-0-0.jpg"
        val seen = mutableListOf<Pair<Any, Thread>>()
        val request = ImageRequest.Builder(context).data(url).build()
        interceptor.intercept(Chain(request, seen))
        val first = requests.get() to bytes.get()
        // As Coil does on the main thread: started in place; a saved cover must not suspend on the way.
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { interceptor.intercept(Chain(request, seen)) }
        val inPlace = job.isCompleted
        job.join()
        val second = (requests.get() - first.first) to (bytes.get() - first.second)
        println("cover-reopen: first open ${first.first} requests ${first.second} bytes; second open ${second.first} requests ${second.second} bytes; " +
            "second served ${if (inPlace) "in place, without suspending" else "after a hop to " + seen[1].second.name}")
        assertEquals(1, first.first)
        assertEquals(0, second.first)
        assertTrue("the saved file is what Coil decodes", seen[1].first is File)
        assertTrue("no hop to the disk threads for a saved cover", inPlace)
        assertSame(Thread.currentThread(), seen[1].second)
    }
}
