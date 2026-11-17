package com.ketvoice.app.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileNotFoundException

/** Covers handed to Android Auto: signed content URIs, only what Ketvoice gave out, scaled for the car. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoArtworkTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun signaturesAndEncodingRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val source = "https://img.example/cover.jpg?w=600&h=600"
        val token = AutoArtwork.sign(key, source)
        assertEquals(32, token.length)
        assertTrue(AutoArtwork.verify(key, source, token))
        assertFalse(AutoArtwork.verify(key, "$source&x", token))
        assertFalse(AutoArtwork.verify(ByteArray(32), source, token))
        assertEquals(source, AutoArtwork.decode(AutoArtwork.encode(source)))
        assertTrue(AutoArtwork.allowed(source))
        assertTrue(AutoArtwork.allowed("content://media/external/audio/albumart/12"))
        assertFalse(AutoArtwork.allowed("http://img.example/cover.jpg"))
        assertFalse(AutoArtwork.allowed("content://com.other.app/secret"))
        assertFalse(AutoArtwork.allowed("javascript:alert(1)"))
    }

    @Test fun theProviderServesOnlySignedCoversScaledForTheCar() {
        Robolectric.setupContentProvider(AutoArtworkProvider::class.java, AutoArtwork.authority(app))
        val big = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        val file = File(app.filesDir, "covers/big.png").apply { parentFile!!.mkdirs(); outputStream().use { big.compress(Bitmap.CompressFormat.PNG, 100, it) } }

        assertNull(AutoArtwork.uri(app, null))
        assertNull(AutoArtwork.uri(app, " "))
        assertEquals("android.resource://com.ketvoice.app/1", AutoArtwork.uri(app, "android.resource://com.ketvoice.app/1").toString())
        val uri = AutoArtwork.uri(app, Uri.fromFile(file).toString())!!
        assertEquals("content", uri.scheme)
        assertEquals(AutoArtwork.authority(app), uri.authority)
        assertEquals(Uri.fromFile(file).toString(), AutoArtwork.source(app, uri))

        val served = app.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
        assertEquals(AutoArtwork.MAX_SIDE, maxOf(served.width, served.height))
        assertEquals(1600f / 1200f, served.width.toFloat() / served.height, .02f)
        // Served again from the cache, without reading the source.
        file.delete()
        assertNotNull(app.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) })

        // A forged or altered URI, or a file outside the app's own folders, is never served.
        val forged = Uri.Builder().scheme("content").authority(AutoArtwork.authority(app))
            .appendPath("0".repeat(32)).appendPath(AutoArtwork.encode("https://evil.example/x.jpg")).build()
        assertNull(AutoArtwork.source(app, forged))
        assertThrows(FileNotFoundException::class.java) { app.contentResolver.openInputStream(forged) }
        val outside = AutoArtwork.uri(app, "file:///etc/hosts")!!
        assertThrows(FileNotFoundException::class.java) { app.contentResolver.openInputStream(outside) }
    }
}
