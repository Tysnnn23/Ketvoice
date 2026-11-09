package com.ketvoice.app.ui

import androidx.compose.ui.geometry.Rect
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.ui.motion.revealTransform
import org.junit.Assert.*
import org.junit.Test

class NavigationMotionTest {
    private val origin = Rect(30f, 200f, 180f, 380f)

    @Test fun revealArrivesExactlyWithoutStretching() {
        val start = revealTransform(origin, 400f, 900f, 0f)
        val end = revealTransform(origin, 400f, 900f, 1f)
        assertEquals(.375f, start.scale, .0001f)
        assertEquals(-95f, start.translateX, .001f)
        assertEquals(-160f, start.translateY, .001f)
        assertEquals(1f, end.scale, 0f)
        assertEquals(0f, end.translateX, 0f)
        assertEquals(0f, end.translateY, 0f)
        assertEquals(1f, end.alpha, 0f)
    }

    @Test fun reversalUsesTheSameGeometryAndClampsSpringOvershoot() {
        val forward = (0..10).map { revealTransform(origin, 400f, 900f, it / 10f) }
        assertEquals(forward, (10 downTo 0).map { revealTransform(origin, 400f, 900f, it / 10f) }.reversed())
        assertEquals(forward.first(), revealTransform(origin, 400f, 900f, -1f))
        assertEquals(forward.last(), revealTransform(origin, 400f, 900f, 2f))
        assertTrue(forward.zipWithNext().all { (a, b) -> b.scale >= a.scale })
    }

    @Test fun missingOriginAndZeroSizeNeverProduceNaN() {
        assertEquals(1f, revealTransform(null, 400f, 900f, .5f).scale, 0f)
        assertEquals(0f, revealTransform(origin, 0f, 0f, .5f).translateX, 0f)
    }

    @Test fun routeIdentityIncludesKindAndArtistNameButNotStaleRequestsOrOrigin() {
        val album = AppUi.OverlayEntry(AppUi.CATALOG, null, CatalogRequest("album", "same", "A"), null, "")
        val genre = album.copy(catalogRequest = CatalogRequest("genre", "same", "B"))
        assertNotEquals(navigationPageKey(0, album), navigationPageKey(0, genre))
        val artist = album.copy(overlay = AppUi.ARTIST, artistRequest = AppUi.ArtistRequest("", "Artist A"))
        assertNotEquals(navigationPageKey(0, artist), navigationPageKey(0, artist.copy(artistRequest = AppUi.ArtistRequest("", "Artist B"))))
        assertEquals(navigationPageKey(0, album), navigationPageKey(0, album.copy(origin = origin)))
        assertEquals("tab:2", navigationPageKey(2, album.copy(overlay = AppUi.NONE)))
    }

    @Test fun nestedBackRestoresRequestsAndCardOrigin() {
        AppUi.selectTab(AppUi.HOME)
        AppUi.openArtist("artist", "Artist", origin)
        AppUi.openCatalog(CatalogRequest("album", "album", "Album"), Rect.Zero)
        assertTrue(AppUi.closeOverlay())
        assertEquals(AppUi.ARTIST, AppUi.overlay.intValue)
        assertEquals("artist", AppUi.artistRequest.value?.id)
        assertEquals(origin, AppUi.revealOrigin.value)
        assertNull(AppUi.catalogRequest.value)
        AppUi.selectTab(AppUi.HOME)
    }
}
