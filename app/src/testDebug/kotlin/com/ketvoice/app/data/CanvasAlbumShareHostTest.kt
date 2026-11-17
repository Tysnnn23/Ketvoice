package com.ketvoice.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One Canvas for the whole album (6.2.2): what one title fetched is what the others find, unless they are not kept by their link. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CanvasAlbumShareHostTest {
    /** The repository keeps the first context it was given; each test starts from its own. */
    private fun freshRepo(context: Context) {
        CanvasRepo::class.java.getDeclaredField("appContext").apply { isAccessible = true }.set(CanvasRepo, null)
        CanvasRepo.init(context)
    }

    @Test fun aTitleOfTheSameAlbumFindsTheCanvasAnotherOneFetched() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        freshRepo(context)
        val dir = File(context.filesDir, "canvas").apply { mkdirs() }
        File(dir, "album-clip.mp4").writeBytes(ByteArray(20_000))
        context.getSharedPreferences("ketvoice_canvas", Context.MODE_PRIVATE).edit()
            .putString("album:the killers:hot fuss", "album-clip").apply()
        val found = CanvasRepo.albumFile("Hot Fuss", "The Killers, Someone")
        assertEquals("album-clip.mp4", found?.name)
        assertNull(CanvasRepo.albumFile("Sam's Town", "The Killers"))
        assertNull(CanvasRepo.albumFile("", "The Killers"))
    }

    @Test fun aKeptLinkIsNeverTakenForTheAlbumsCanvas() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        freshRepo(context)
        val dir = File(context.filesDir, "canvas").apply { mkdirs() }
        File(dir, "kvlink-1234.mp4").writeBytes(ByteArray(20_000))
        context.getSharedPreferences("ketvoice_canvas", Context.MODE_PRIVATE).edit()
            .putString("album:a:b", "kvlink-1234").apply()
        assertNull(CanvasRepo.albumFile("B", "A"))
    }
}
