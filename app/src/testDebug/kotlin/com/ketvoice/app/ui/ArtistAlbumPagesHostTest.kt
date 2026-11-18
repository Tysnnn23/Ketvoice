package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.Album
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtistPage
import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.CatalogPage
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.MusicArtist
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.adaptive.LargeScreen
import com.ketvoice.app.ui.adaptive.LocalLargeScreen
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * The redone Artist and Album pages (6.1), drawn on the host (not a device) with made-up catalogue data:
 * a phone and a tablet window, dark. Pictures are drawn by the test (gradients standing in for photos and
 * covers); glass draws flat here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtistAlbumPagesHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val artist = MusicArtist("UCqaNovaLune", "Nova Lune", "fixture:portrait")
    private val titles = listOf("Lumière froide", "Nuit d'encre", "Les heures bleues", "Septembre", "Métropole", "Écho",
        "Sans retour", "Rivière", "Comète", "Dernier train", "Silence radio", "Ailleurs")
    private val tracks = titles.mapIndexed { i, title ->
        Track("qa:nova:$i", title, if (i == 2) "Nova Lune, Iris Vale" else "Nova Lune", "Horizons",
            durationMs = 151_000L + i * 13_400L, explicit = i % 4 == 1, coverUrl = "fixture:cover:${i % 5}",
            artistId = artist.id, albumId = "MPREqaHorizons")
    }
    private val albums = listOf(
        Album("MPREqaHorizons", "Horizons", "Nova Lune", "Album · 2025", "fixture:cover:0"),
        Album("MPREqaVille", "Ville lumière", "Nova Lune", "Album · 2023", "fixture:cover:1"),
        Album("MPREqaPremiers", "Premiers pas", "Nova Lune", "Album · 2020", "fixture:cover:2"),
        Album("MPREqaComete", "Comète", "Nova Lune", "Single · 2026", "fixture:cover:3"),
        Album("MPREqaSilence", "Silence radio", "Nova Lune", "EP · 2024", "fixture:cover:4"),
        Album("MPREqaSeptembre", "Septembre", "Nova Lune", "Single · 2022", "fixture:cover:5"))
    private val related = listOf("Iris Vale", "Les Phares", "Mina Sol", "Orage", "Kaëlle", "Tessa Nord").mapIndexed { i, name ->
        MusicArtist("UCqaRelated$i", name, "fixture:artist:$i")
    }
    private val page = ArtistPage(artist,
        "Nova Lune est une autrice-compositrice française née à Lyon. Entre pop électronique et chanson, ses albums " +
            "racontent la ville la nuit, les trains du dernier soir et les amitiés qui durent. Révélée en 2020 avec Premiers pas, " +
            "elle a depuis rempli les salles de toute la francophonie et signé plusieurs duos remarqués.",
        tracks, albums, related, "1,2 M auditeurs mensuels", 1f)

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        AppSettings.reduceDynamicBlur.value = true
        AppSettings.artistPhotos.value = false
        AppSettings.artistSignatures.value = false
        ArtistPrefs.replaceForTest()
        pictures()
    }

    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.artistPhotos.value = true
        ArtistPrefs.replaceForTest()
    }

    @Test fun artistPageOnAPhone() {
        frame { com.ketvoice.app.ui.sub.ArtistPageFrame(AppUi.ArtistRequest(artist.id, artist.name), page, false, false, 0, {}, {}) }
        compose.onAllNodesWithText("Nova Lune")[0].assertIsDisplayed()
        compose.onNodeWithText("Écouter").assertIsDisplayed()
        compose.onNodeWithTag("artist-follow").assertIsDisplayed()
        image("artist-phone-1-hero")
        // Five popular titles, then all of them.
        compose.onNodeWithText("Titres populaires").performScrollTo()
        compose.onNodeWithText("Métropole").assertExists()
        compose.onAllNodesWithText("Écho").assertCountEquals(0)
        compose.onNodeWithTag("artist-list").performScrollToKey("artist-popular-title")
        image("artist-phone-2-popular")
        compose.onNodeWithTag("artist-show-all").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Afficher moins").assertExists()
        compose.onNodeWithTag("artist-list").performScrollToKey("artist-latest")
        compose.onNodeWithTag("artist-latest").assertExists()
        image("artist-phone-3-latest")
        compose.onNodeWithTag("artist-list").performScrollToKey("artist-shelf-albums")
        compose.onNodeWithTag("artist-shelf-albums").assertExists()
        compose.onNodeWithTag("artist-shelf-singles").assertExists()
        image("artist-phone-4-discography")
        compose.onNodeWithTag("artist-list").performScrollToKey("artist-footer")
        compose.onNodeWithTag("artist-about").assertExists()
        compose.onNodeWithTag("artist-bar-title").assertExists()
        image("artist-phone-5-about")
        // The favourite and the block go through data/ArtistPrefs.
        compose.onNodeWithTag("artist-page-block").performClick()
        assertTrue(ArtistPrefs.isBlocked("Nova Lune"))
        compose.onNodeWithTag("artist-page-block").performClick()
        assertFalse(ArtistPrefs.isBlocked("Nova Lune"))
    }

    @Test fun theFavouriteIsKeptInArtistPrefs() {
        frame { com.ketvoice.app.ui.sub.ArtistPageFrame(AppUi.ArtistRequest(artist.id, artist.name), page, false, false, 0, {}, {}) }
        compose.onNodeWithTag("artist-follow").performClick()
        assertTrue(ArtistPrefs.isFavourite("Nova Lune"))
        compose.onNodeWithContentDescription("Retirer des favoris").assertExists()
    }

    @Test fun anArtistWithoutReleasesShowsNoEmptySections() {
        val bare = ArtistPage(MusicArtist("UCqaBare", "Solène", null), "", tracks.take(3), emptyList(), emptyList(), "", 1f)
        frame { com.ketvoice.app.ui.sub.ArtistPageFrame(AppUi.ArtistRequest("UCqaBare", "Solène"), bare, false, false, 0, {}, {}) }
        compose.onNodeWithText("Titres populaires").assertExists()
        compose.onAllNodesWithTag("artist-show-all").assertCountEquals(0)
        listOf("artist-latest", "artist-shelf-albums", "artist-shelf-singles", "artist-similar", "artist-about").forEach {
            compose.onAllNodesWithTag(it).assertCountEquals(0)
        }
        image("artist-phone-bare")
    }

    @Test fun albumPageOnAPhone() {
        kotlinx.coroutines.runBlocking { com.ketvoice.app.data.ArtistCache.put(artist.id, artist.name, page) }
        val album = tracks.take(10)
        frame {
            com.ketvoice.app.ui.sub.AlbumPage(CatalogRequest("album", "MPREqaHorizons", "Horizons"),
                CatalogPage("Horizons", "Nova Lune", "fixture:cover:0", album, "2025-03-14"), album, false, {})
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("album-artwork-loaded").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("album-title").assertIsDisplayed()
        image("album-phone-1-hero")
        compose.onNodeWithTag("album-list").performScrollToIndex(2)
        compose.onAllNodesWithTag("album-track-explicit", useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        // The artists of a title show only when they are not the album's own.
        compose.onNodeWithText("Nova Lune, Iris Vale").assertExists()
        image("album-phone-2-tracks")
        compose.onNodeWithTag("album-list").performScrollToKey("album-footer")
        compose.onNodeWithText("14 mars 2025").assertExists()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("album-more").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("album-list").performScrollToKey("album-more")
        compose.onNodeWithText("Plus de Nova Lune").assertExists()
        // This album is not offered again in its own shelf.
        compose.onAllNodes(hasTestTag("release-MPREqaHorizons")).assertCountEquals(0)
        image("album-phone-3-footer")
    }

    @Test @Config(qualifiers = "w1194dp-h834dp-xlarge-land-mdpi")
    fun artistPageOnATablet() {
        // Quest keeps its own window layout and never sees a large-screen one (AGENTS.md).
        org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST)
        frame(large = true) { com.ketvoice.app.ui.sub.ArtistPageFrame(AppUi.ArtistRequest(artist.id, artist.name), page, false, false, 0, {}, {}) }
        compose.onNodeWithTag("artist-list-pane").assertIsDisplayed()
        // The latest release sits beside the popular titles.
        val latest = compose.onNodeWithTag("artist-latest").fetchSemanticsNode().boundsInRoot
        val popular = compose.onNodeWithTag("artist-popular").fetchSemanticsNode().boundsInRoot
        assertTrue("latest ${latest.right} left of popular ${popular.left}", latest.right <= popular.left)
        image("artist-tablet-1-hero")
        compose.onNodeWithTag("artist-list").performScrollToKey("artist-similar")
        image("artist-tablet-2-discography")
        compose.onNodeWithTag("artist-list").performScrollToKey("artist-footer")
        image("artist-tablet-3-about")
    }

    @Test @Config(qualifiers = "w1194dp-h834dp-xlarge-land-mdpi")
    fun albumPageOnATablet() {
        // Quest keeps its own window layout and never sees a large-screen one (AGENTS.md).
        org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST)
        kotlinx.coroutines.runBlocking { com.ketvoice.app.data.ArtistCache.put(artist.id, artist.name, page) }
        val album = tracks.take(10)
        frame(large = true) {
            com.ketvoice.app.ui.sub.AlbumPage(CatalogRequest("album", "MPREqaHorizons", "Horizons"),
                CatalogPage("Horizons", "Nova Lune", "fixture:cover:0", album, "2025-03-14"), album, false, {})
        }
        val art = compose.onNodeWithTag("album-hero-visual").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("album-title").fetchSemanticsNode().boundsInRoot
        assertTrue("the artwork stands beside the title", art.right < title.left)
        image("album-tablet-1")
        // One column now: « Plus de … » waits at the foot of the list until it is scrolled to.
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("album-list").performScrollToKey("album-more") }.isSuccess }
        image("album-tablet-2-more")
    }

    /** A page drawn alone, dark, with the large-screen context the root would give a wide window. */
    private fun frame(large: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            KetvoiceTheme(useDark = true) {
                val config = androidx.compose.ui.platform.LocalConfiguration.current
                val width = config.screenWidthDp.toFloat(); val height = config.screenHeightDp.toFloat()
                val info = if (large) LargeScreen.info(width, height, false, null) else null
                CompositionLocalProvider(LocalLargeScreen provides info,
                    com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout provides com.ketvoice.app.ui.adaptive.AdaptiveLayout(width, height,
                        navigationWidthDp = info?.navigationWidthDp),
                    androidx.compose.foundation.LocalOverscrollFactory provides null) { content() }
            }
        }
        compose.waitForIdle()
    }

    /** Photos and covers made up by the test: soft gradients with a shape, different for each address. */
    private fun pictures() {
        val resources = compose.activity.resources
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                val key = chain.request.data.toString()
                val bitmap = when {
                    key.startsWith("fixture:portrait") -> portrait()
                    key.startsWith("fixture:artist:") -> cover(key.substringAfterLast(':').toIntOrNull() ?: 0, 7, round = true)
                    else -> cover(key.substringAfterLast(':').toIntOrNull() ?: 0, 0)
                }
                coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(resources, bitmap), chain.request,
                    coil.decode.DataSource.MEMORY)
            })
        }.build())
    }

    private fun portrait(): Bitmap {
        val size = 600
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), intArrayOf(0xFF2B1B4A.toInt(), 0xFF8C3A5E.toInt(), 0xFFE58B5A.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        // A warm light behind the figure, then the figure: head and shoulders in shadow.
        paint.shader = RadialGradient(size * .62f, size * .30f, size * .45f, 0x99FFD3A0.toInt(), 0x00FFD3A0, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFF1A1020.toInt()
        canvas.drawCircle(size * .5f, size * .40f, size * .14f, paint)
        canvas.drawOval(size * .22f, size * .58f, size * .78f, size * 1.15f, paint)
        return bitmap
    }

    private fun cover(index: Int, salt: Int, round: Boolean = false): Bitmap {
        val palettes = listOf(
            intArrayOf(0xFF0F2027.toInt(), 0xFF2C5364.toInt(), 0xFF6DD5ED.toInt()),
            intArrayOf(0xFF41295A.toInt(), 0xFF2F0743.toInt(), 0xFFE96443.toInt()),
            intArrayOf(0xFF134E5E.toInt(), 0xFF71B280.toInt(), 0xFFF5F7A1.toInt()),
            intArrayOf(0xFFFF512F.toInt(), 0xFFDD2476.toInt(), 0xFF3A1C71.toInt()),
            intArrayOf(0xFF1D2B64.toInt(), 0xFFF8CDDA.toInt(), 0xFF8E9EAB.toInt()),
            intArrayOf(0xFF232526.toInt(), 0xFF414345.toInt(), 0xFFC9A227.toInt()),
            intArrayOf(0xFF5A3F37.toInt(), 0xFF2C7744.toInt(), 0xFFDCE35B.toInt()),
            intArrayOf(0xFF614385.toInt(), 0xFF516395.toInt(), 0xFFF0C27B.toInt()))
        val colors = palettes[(index + salt) % palettes.size]
        val size = 400
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, size.toFloat(), colors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        paint.color = 0x55FFFFFF
        if (round) {
            paint.color = 0xCC140C18.toInt()
            canvas.drawCircle(size * .5f, size * .42f, size * .16f, paint)
            canvas.drawOval(size * .2f, size * .64f, size * .8f, size * 1.2f, paint)
        } else when (index % 3) {
            0 -> canvas.drawCircle(size * .5f, size * .5f, size * .26f, paint)
            1 -> { paint.strokeWidth = 18f; repeat(6) { canvas.drawLine(0f, it * 80f, size.toFloat(), it * 80f + 120f, paint) } }
            else -> canvas.drawRect(size * .18f, size * .18f, size * .82f, size * .82f, paint.apply { style = Paint.Style.STROKE; strokeWidth = 14f })
        }
        return bitmap
    }

    private fun image(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/pages/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
