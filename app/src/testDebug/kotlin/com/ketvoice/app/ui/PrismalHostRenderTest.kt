package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.GLASS_AGSL
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.common.CompactSearchField
import com.ketvoice.app.ui.settings.SettingsScreen
import com.ketvoice.app.ui.chrome.FloatingChrome
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.data.ArtistPage
import com.ketvoice.app.data.MusicArtist
import com.ketvoice.app.data.CatalogPage
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.ui.library.PlaylistScreen
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.sub.ArtistHeader
import com.ketvoice.app.ui.sub.AlbumPage
import com.ketvoice.app.ui.player.CanvasFocusPlayer
import com.ketvoice.app.ui.player.LyricsPane
import com.ketvoice.app.ui.player.KaraokeLine
import com.ketvoice.app.ui.player.PlayerArtworkFrame
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Host Skia/Android model, NOT a device, owner session or hardware GPU test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrismalHostRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @org.junit.Before fun pinFrenchForReadableAssertions() {
        // The app now follows the system language; host tests assert French copy.
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
    }

    @After fun reset() {
        coil.Coil.reset()
        PlaylistStore.resetForTest()
        AppUi.closeOverlays()
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.highPerformanceMode.value = false
        AppSettings.animatedArtwork.value = true
        AppSettings.artistPhotos.value = true
        AppSettings.artistSignatures.value = false
        @Suppress("UNCHECKED_CAST")
        (Player.state as MutableStateFlow<PlayerUiState>).value = PlayerUiState()
        @Suppress("UNCHECKED_CAST")
        (LyricsRepo.state as MutableStateFlow<LyricsRepo.State>).value = LyricsRepo.State()
        @Suppress("UNCHECKED_CAST")
        (Player.positionMs as MutableStateFlow<Long>).value = 0L
    }

    private fun image(name: String, verify: (Bitmap) -> Unit = {}) {
        val directory = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}").apply { mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // Host Skia can evaluate the shader in software. This flag only bypasses
            // the Android Java canvas's hardware-only API guard in this test.
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            verify(bitmap)
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun settingsHomeAndAppearanceNavigateAndRenderOnHost() {
        AppSettings.reduceAnimations.value = true
        compose.setContent { KetvoiceTheme(useDark = false) { SettingsScreen() } }
        compose.onNodeWithTag("settings-home").assertIsDisplayed()
        val backBounds = compose.onNodeWithContentDescription("Retour").fetchSemanticsNode().boundsInRoot
        val accountBounds = compose.onNodeWithText("Compte YouTube Music").fetchSemanticsNode().boundsInRoot
        assertTrue("Measured floating header cannot overlap the first settings cell", accountBounds.top >= backBounds.bottom)
        image("settings-home")
        compose.onNodeWithTag("settings-category-Appearance").performClick()
        compose.onNodeWithText("Thème").performScrollTo().assertIsDisplayed()
        image("settings-appearance")
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithTag("settings-home").assertIsDisplayed()
    }

    @Test fun narrowSearchRemainsOneLineAtLargeFontScaleOnHost() {
        var query by mutableStateOf("")
        compose.setContent { KetvoiceTheme(useDark = false) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                Column(Modifier.width(280.dp).padding(8.dp)) {
                    CompactSearchField(query, { query = it }, {}, Modifier.testTag("search"))
                }
            }
        } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Rechercher un titre ou un artiste…", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        compose.onNodeWithTag("search").assertHeightIsEqualTo(52.dp)
        image("compact-search-large-font")
        compose.onNode(hasSetTextAction()).performTextInput("Long artist name and long track title")
        compose.onNodeWithTag("search").assertHeightIsEqualTo(52.dp)
        compose.onNodeWithContentDescription("Effacer").performClick()
        compose.runOnIdle { assertEquals("", query) }
    }

    @Test fun collapsedChromeFitsA320DpViewportWithHomeMiniAndSearch() {
        AppSettings.reduceAnimations.value = true
        AppUi.selectTab(AppUi.HOME)
        @Suppress("UNCHECKED_CAST")
        (Player.state as MutableStateFlow<PlayerUiState>).value = PlayerUiState(
            queue = listOf(Track("qa:chrome", "A very long title that must not widen the compact player", "QA artist", "QA album")), index = 0)
        compose.setContent { KetvoiceTheme(useDark = false) {
            Box(Modifier.width(320.dp)) { FloatingChrome(true, {}) }
        } }
        val home = compose.onNodeWithContentDescription("Accueil").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithContentDescription("Lecture").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithContentDescription("Rechercher").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Home stays left of the mini-player", home.right <= play.left)
        assertTrue("The mini-player stays left of search", play.right <= search.left)
        assertTrue("Navigation must use the actual 320dp pane", search.right <= 320f)
        assertTrue("The mini-player transport remains at least a 48dp touch target", play.width >= 48f && play.height >= 48f)
        assertFalse("The transport must not overlap search", play.overlaps(search))
        compose.onNodeWithContentDescription("Bibliothèque").assertDoesNotExist()
        compose.onNodeWithContentDescription("Explorer").assertDoesNotExist()
        image("condensed-navigation-320dp")
    }

    @Test fun collapsedChromeDocksTheMiniPlayerBetweenHomeAndSearch() {
        AppSettings.reduceAnimations.value = true
        AppUi.selectTab(AppUi.HOME)
        @Suppress("UNCHECKED_CAST")
        (Player.state as MutableStateFlow<PlayerUiState>).value = PlayerUiState(
            queue = listOf(Track("qa:dock", "QA dock title", "QA artist", "QA album")), index = 0)
        compose.setContent { KetvoiceTheme(useDark = false) {
            Box(Modifier.fillMaxSize()) { FloatingChrome(true, {}) }
        } }
        val home = compose.onNodeWithContentDescription("Accueil").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithContentDescription("Lecture").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithContentDescription("Rechercher").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Accueil remains left of the mini-player", home.right <= play.left)
        assertTrue("The mini-player remains left of search", play.right <= search.left)
        assertTrue("The transport keeps a 48dp touch target", play.width >= 48f && play.height >= 48f)
        assertFalse(home.overlaps(play))
        assertFalse(play.overlaps(search))
        compose.onNodeWithContentDescription("Bibliothèque").assertDoesNotExist()
        image("collapsed-chrome-home-mini-search")
    }

    @Test fun artistHeaderKeepsLongNamesAndBothActionsReadableOnHost() {
        val track = Track("qa:artist", "QA title", "Un artiste au nom particulièrement long", "Album")
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(LocalTheme.current.background)) {
                ArtistHeader(ArtistPage(MusicArtist("qa:artist", track.artist, null), "", listOf(track),
                    emptyList(), emptyList(), "1 234 567 auditeurs mensuels", 2.4f))
            }
        } }
        compose.onNodeWithText(track.artist).assertIsDisplayed()
        compose.onNodeWithText("Écouter").assertIsDisplayed()
        compose.onNodeWithText("Aléatoire").assertIsDisplayed()
        // The radio link sits under the two buttons, never over Listen (5.8.0 drew it on top).
        val play = compose.onNodeWithTag("artist-play", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val radio = compose.onNodeWithTag("artist-radio", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("radio ${radio.top} below play ${play.bottom}", radio.top >= play.bottom)
        image("artist-header-dark-fallback")
    }

    @Test fun artistPageTakesItsColourFromThePortrait() {
        AppSettings.reduceAnimations.value = true
        AppSettings.artistPhotos.value = false
        AppSettings.artistSignatures.value = false
        // A warm portrait: red above, a darker red below.
        val bitmap = android.graphics.Bitmap.createBitmap(120, 120, android.graphics.Bitmap.Config.ARGB_8888)
        for (x in 0 until 120) for (y in 0 until 120) bitmap.setPixel(x, y, android.graphics.Color.rgb(200 - y / 2, 40 + x / 6, 60))
        val drawable = android.graphics.drawable.BitmapDrawable(compose.activity.resources, bitmap)
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain -> coil.request.SuccessResult(drawable, chain.request, coil.decode.DataSource.MEMORY) })
        }.build())
        val track = Track("qa:artist-tint", "QA title", "Nova", "Album")
        var reported: androidx.compose.ui.graphics.Color? = null
        compose.setContent { KetvoiceTheme(useDark = false) {
            val base = LocalTheme.current
            var tone by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.Color?>(null) }
            val tinted = base.copy(background = com.ketvoice.app.ui.sub.artistPageTint(base.background, tone, base.isDark))
            androidx.compose.runtime.CompositionLocalProvider(LocalTheme provides tinted) {
                Box(Modifier.fillMaxSize().background(tinted.background)) {
                    ArtistHeader(ArtistPage(MusicArtist("qa:artist-tint", "Nova", "fixture:warm-portrait"), "", listOf(track),
                        emptyList(), emptyList(), "", 1f), onTone = { tone = it; reported = it })
                }
            }
        } }
        compose.waitUntil(10_000) { reported != null }
        compose.waitForIdle()
        val portrait = compose.onNodeWithTag("artist-portrait").fetchSemanticsNode().boundsInRoot
        val width = compose.activity.window.decorView.width.toFloat()
        assertEquals("The portrait starts at the left edge", 0f, portrait.left, 1f)
        assertTrue("The portrait reaches the right edge", portrait.width >= width - 1f)
        compose.onNodeWithText("Nova").assertIsDisplayed()
        compose.onNodeWithText("Écouter").assertIsDisplayed()
        image("artist-page-tint-light") { shot ->
            // Below the portrait the page is no longer the plain light background: it carries the portrait's red.
            val page = shot.getPixel(20, (portrait.bottom + 40f).toInt().coerceAtMost(shot.height - 1))
            assertTrue("The page is tinted warm (r=${android.graphics.Color.red(page)}, b=${android.graphics.Color.blue(page)})",
                android.graphics.Color.red(page) > android.graphics.Color.blue(page) + 12)
            // And the photo's last rows have already dissolved into that same colour: no line.
            val seam = shot.getPixel(20, (portrait.bottom - 2f).toInt())
            assertTrue("No visible seam where the portrait ends",
                kotlin.math.abs(android.graphics.Color.red(seam) - android.graphics.Color.red(page)) <= 6)
        }
    }

    @Test fun artistPortraitFixtureKeepsIdentityAndActionsOnOneSurface() {
        AppSettings.reduceAnimations.value = true
        AppSettings.artistPhotos.value = false
        AppSettings.artistSignatures.value = false
        val fixture = File("../ketvoice-banner.png")
        assertTrue(fixture.isFile)
        val drawable = android.graphics.drawable.BitmapDrawable(compose.activity.resources, android.graphics.BitmapFactory.decodeFile(fixture.absolutePath))
        val requests = java.util.concurrent.atomic.AtomicInteger()
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                requests.incrementAndGet()
                coil.request.SuccessResult(drawable, chain.request, coil.decode.DataSource.MEMORY)
            })
        }.build())
        val track = Track("qa:artist-image", "QA title", "Artiste", "Album")
        compose.setContent { KetvoiceTheme(useDark = false) {
            ArtistHeader(ArtistPage(MusicArtist("qa:artist-image", "Artiste", "fixture:image"), "", listOf(track),
                emptyList(), emptyList(), "", 1f))
        } }
        compose.waitUntil(10_000) { requests.get() > 0 }
        compose.onNodeWithText("Artiste").assertIsDisplayed()
        compose.onNodeWithText("Écouter").assertIsDisplayed()
        image("artist-portrait-light-host-fixture")
    }

    @Test @Config(qualifiers = "w320dp-h900dp-mdpi")
    fun explorerCategoryNamesRemainReadableAndClickableAtLargeFontScale() {
        var chosen = ""
        compose.setContent { KetvoiceTheme(useDark = true) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.4f)) {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    com.ketvoice.app.ui.explorer.categoryColumns(false, 1.4f), Modifier.width(320.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val names = listOf("Hip-hop et nouvelles tendances", "Découvertes internationales")
                    items(names.size) { index ->
                        val name = names[index]
                        com.ketvoice.app.ui.explorer.GenreCategoryCard(
                            com.ketvoice.app.data.CatalogGenre("fixture:$index", name, null), 120.dp) { chosen = name }
                    }
                }
            }
        } }
        compose.onNodeWithText("Hip-hop et nouvelles tendances").assertIsDisplayed().performClick()
        assertEquals("Hip-hop et nouvelles tendances", chosen)
        compose.onNodeWithText("Découvertes internationales").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Découvertes internationales", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val split = layout.layoutInput.text.text.indexOf("internationales")
        assertTrue("The last word must stay on one line at large font size",
            (0 until layout.lineCount).any { row -> layout.getLineStart(row) <= split && layout.getLineEnd(row) >= split + "internationales".length })
        image("explorer-categories-320dp-large-font")
    }

    private fun playlistFixture() {
        AppSettings.reduceAnimations.value = true
        AppSettings.reduceDynamicBlur.value = true
        PlaylistStore.resetForTest()
        kotlinx.coroutines.runBlocking { PlaylistStore.init(compose.activity) }
        val playlist = LocalPlaylist(id = "qa:fixed-playlist", name = "Playlist fixe", entries = (0..49).map {
            PlaylistEntry(id = "entry:$it", title = "Titre $it", artist = "Artiste QA")
        })
        @Suppress("UNCHECKED_CAST")
        (PlaylistStore.lists as MutableStateFlow<List<LocalPlaylist>>).value = listOf(playlist)
        AppUi.playlistId.value = playlist.id
    }

    @Test fun playlistHeaderScrollsAwayWithoutLeavingABlankHeaderArea() {
        playlistFixture()
        compose.setContent { KetvoiceTheme(useDark = true) { PlaylistScreen() } }
        compose.onNodeWithTag("playlist-title").assertTextEquals("Playlist fixe")
        val cover = compose.onNodeWithTag("playlist-cover").fetchSemanticsNode().boundsInRoot
        var before = IntArray(0)
        image("playlist-scroll-before") { bitmap ->
            before = IntArray(80 * 80); bitmap.getPixels(before, 0, 80, cover.left.toInt() + 10, cover.top.toInt() + 10, 80, 80)
        }
        compose.onNodeWithTag("playlist-tracks").performScrollToIndex(26) // 7.5: hero, then the search/sort row, then the titles
        compose.onNodeWithText("Titre 25").assertIsDisplayed()
        compose.onNodeWithText("Playlist fixe").assertExists()
        // The hero is a normal list item now: it scrolls out of the viewport.
        assertEquals("The header must not stay pinned on screen",
            0, compose.onAllNodesWithTag("playlist-cover").fetchSemanticsNodes().size)
        // And it does not reserve empty space at the top: the first visible row
        // starts right under the top bar, not hundreds of pixels lower.
        val listTop = compose.onNodeWithTag("playlist-tracks").fetchSemanticsNode().boundsInRoot.top
        val rowTop = compose.onNodeWithText("Titre 25").fetchSemanticsNode().boundsInRoot.top
        assertTrue("No blank header area may remain, rowTop=$rowTop listTop=$listTop", rowTop < listTop + 200f)
        image("playlist-scroll-after")
        compose.onNodeWithTag("playlist-tracks").performScrollToIndex(0)
        compose.onNodeWithTag("playlist-cover").assertIsDisplayed()
        image("playlist-scroll-restored") { bitmap ->
            val after = IntArray(80 * 80); bitmap.getPixels(after, 0, 80, cover.left.toInt() + 10, cover.top.toInt() + 10, 80, 80)
            assertArrayEquals("Returning to the top restores the same artwork", before, after)
        }
    }

    @Test @Config(qualifiers = "w900dp-h480dp-mdpi")
    fun shortLandscapePlaylistScrollsItsHeaderAndKeepsTheListUsable() {
        playlistFixture()
        compose.setContent { KetvoiceTheme(useDark = true) { PlaylistScreen() } }
        compose.onNodeWithTag("playlist-play").assertIsDisplayed()
        compose.onNodeWithTag("playlist-cover").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("playlist-tracks").fetchSemanticsNode().boundsInRoot.height > 100f)
        compose.onNodeWithTag("playlist-tracks").performScrollToIndex(25)
        compose.onNodeWithText("Titre 25").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("playlist-cover").fetchSemanticsNodes().size)
        image("playlist-scroll-short-landscape")
    }

    @Test fun playlistDragMovesTheFirstTrackWithoutAHeaderIndexOffset() {
        playlistFixture()
        compose.setContent { KetvoiceTheme(useDark = true) { PlaylistScreen() } }
        // « Modifier l'ordre » lives in the playlist's « … » sheet (drawn by AppRoot), as Apple Music's Edit.
        compose.onNodeWithContentDescription("Actions de la playlist").performClick()
        compose.runOnIdle { AppUi.playlistMenu.value!!.onReorder!!(); AppUi.playlistMenu.value = null }
        compose.onNodeWithTag("playlist-reorder-done").assertIsDisplayed()
        val first = compose.onNodeWithContentDescription("Déplacer Titre 0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithContentDescription("Déplacer Titre 1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val step = second.center.y - first.center.y
        compose.onNodeWithContentDescription("Déplacer Titre 0", useUnmergedTree = true).performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 12f));
            moveBy(androidx.compose.ui.geometry.Offset(0f, step * 1.25f), 300); up()
        }
        compose.waitUntil(5000) { PlaylistStore.lists.value.first().entries.first().id == "entry:1" }
        assertEquals("entry:0", PlaylistStore.lists.value.first().entries[1].id)
    }

    @Test fun playlistRenameAndDeleteSheetsPreserveDataWhenCancelled() {
        playlistFixture()
        compose.setContent { KetvoiceTheme(useDark = false) { PlaylistScreen() } }
        val original = PlaylistStore.lists.value.single()
        compose.onNodeWithContentDescription("Actions de la playlist").performClick()
        compose.runOnIdle { AppUi.playlistMenu.value!!.onRename(); AppUi.playlistMenu.value = null }
        compose.onNodeWithContentDescription("Nom de la playlist").assertIsDisplayed().performTextReplacement("New name")
        compose.onNodeWithText("Annuler").performClick()
        assertEquals(original, PlaylistStore.lists.value.single())
        compose.onNodeWithContentDescription("Actions de la playlist").performClick()
        compose.runOnIdle { AppUi.playlistMenu.value!!.onDelete(); AppUi.playlistMenu.value = null }
        compose.onNodeWithText("Supprimer cette playlist ?").assertIsDisplayed()
        compose.onNodeWithText("Annuler").performClick()
        assertEquals("Cancelling must never delete playlist data", original, PlaylistStore.lists.value.single())
    }

    @Test fun hostRenderingCannotValidateRenderEffectBlur() {
        // Regression note: Robolectric's native Skia does NOT composite RenderEffect
        // blur, so host pixels cannot validate a bloom. Device validation is required.
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Box(Modifier.offset(x = 20.dp, y = 40.dp).size(80.dp).background(Color.White)
                    .blur(12.dp, BlurredEdgeTreatment.Unbounded))
            }
        } }
        compose.waitForIdle()
        image("blur-probe") { bitmap ->
            val inside = android.graphics.Color.red(bitmap.getPixel(28, 48))
            val bleed = android.graphics.Color.red(bitmap.getPixel(28, 32))
            println("blur-probe inside=$inside bleed=$bleed")
            assertTrue("Sharp content is drawn on host", inside > 240)
            assertEquals("Documented limitation: host blur is not a device result", 0, bleed)
        }
    }

    @Test fun heldWordsRemainSingleShapedTextWithoutGlowOrScaledCopies() {
        AppSettings.reduceAnimations.value = false
        AppSettings.highPerformanceMode.value = false
        val line = MusicApi.LyricLine(0, "Hold", listOf(MusicApi.LyricWord(0, "Hold", 3_000)))
        @Suppress("UNCHECKED_CAST")
        val position = Player.positionMs as MutableStateFlow<Long>
        position.value = 500L
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Box(Modifier.padding(top = 220.dp, start = 24.dp, end = 24.dp).fillMaxWidth()) {
                    KaraokeLine(line, Modifier.fillMaxWidth(), 1f)
                }
            }
        } }
        compose.onNodeWithText("Hold").assertIsDisplayed()
        compose.onAllNodesWithText("Hold").assertCountEquals(1)
        compose.runOnIdle { position.value = 1_500L }
        compose.waitForIdle()
        assertEquals("Held words must not compose blurred copies", 0, compose.onAllNodesWithTag("word-glow").fetchSemanticsNodes().size)
        compose.onAllNodesWithText("Hold").assertCountEquals(1)
        image("lyrics-clean-held-word") { bitmap ->
            assertTrue("Lit glyphs are present", android.graphics.Color.red(bitmap.getPixel(40, 232)) > 0)
        }
        compose.runOnIdle { position.value = 3_200L }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("word-glow").fetchSemanticsNodes().size)
        compose.runOnIdle { position.value = 3_500L }
        compose.waitForIdle()
        assertEquals("No delayed duplicate may appear after the word", 0, compose.onAllNodesWithTag("word-glow").fetchSemanticsNodes().size)
    }

    @Test fun collectionHeroMatchesThePlaylistPresentation() {
        AppSettings.reduceAnimations.value = true
        var played = false
        var shared = false
        var refreshed = false
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(LocalTheme.current.background)) {
                Box(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    com.ketvoice.app.ui.sub.CollectionHero(
                        title = "Récemment écoutés",
                        subtitle = "12 titres · 48 min · Écoutés récemment",
                        covers = listOf(null, null, null, null),
                        seed = "history",
                        onPlay = { played = true },
                        onShuffle = {},
                        onShare = { shared = true },
                        onRefresh = { refreshed = true }
                    )
                }
            }
        } }
        compose.onNodeWithText("Récemment écoutés").assertIsDisplayed()
        compose.onNodeWithText("Écouter").assertIsDisplayed()
        compose.onNodeWithText("Aléatoire").assertIsDisplayed()
        compose.onNodeWithText("Partager").performClick()
        compose.onNodeWithText("Actualiser").performClick()
        compose.onNodeWithText("Récemment écoutés").performClick()
        compose.runOnIdle { assertTrue(shared); assertTrue(refreshed) }
        image("collection-hero")
    }

    @Test fun splashBrandSettlesOnTheMarkAndWordmark() {
        compose.setContent { KetvoiceTheme(useDark = false) { SplashBrand(Modifier.fillMaxSize()) } }
        compose.mainClock.advanceTimeBy(1_800)
        compose.onNodeWithTag("splash-brand").assertExists()
        compose.onNodeWithText("Ketvoice").assertExists()
        image("splash-brand")
    }

    private fun warmCover() {
        // A warm cover: red above, a darker red below.
        val bitmap = android.graphics.Bitmap.createBitmap(120, 120, android.graphics.Bitmap.Config.ARGB_8888)
        for (x in 0 until 120) for (y in 0 until 120) bitmap.setPixel(x, y, android.graphics.Color.rgb(200 - y / 2, 40 + x / 6, 60))
        val drawable = android.graphics.drawable.BitmapDrawable(compose.activity.resources, bitmap)
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain -> coil.request.SuccessResult(drawable, chain.request, coil.decode.DataSource.MEMORY) })
        }.build())
    }

    @Test fun albumPageTakesItsColourFromTheCoverAndTheCoverDissolvesIntoIt() {
        AppSettings.reduceAnimations.value = true
        AppSettings.albumColors.value = true
        warmCover()
        val track = Track("qa:album-tint", "QA title", "QA artist", "QA album", durationMs = 180_000)
        compose.setContent { KetvoiceTheme(useDark = false) {
            AlbumPage(CatalogRequest("album", "qa:album-tint", "QA album"),
                CatalogPage("QA album", "QA artist", "fixture:warm-cover", listOf(track), "2026"), listOf(track), false, {})
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("album-artwork-loaded").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val hero = compose.onNodeWithTag("album-hero-visual").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Lire").assertIsDisplayed()
        image("album-page-tint-light") { shot ->
            val page = shot.getPixel(20, (hero.bottom + 30f).toInt().coerceAtMost(shot.height - 1))
            assertTrue("The page is tinted warm (r=${android.graphics.Color.red(page)}, b=${android.graphics.Color.blue(page)})",
                android.graphics.Color.red(page) > android.graphics.Color.blue(page) + 12)
            val seam = shot.getPixel(20, (hero.bottom - 2f).toInt())
            assertTrue("No visible seam where the cover ends",
                kotlin.math.abs(android.graphics.Color.red(seam) - android.graphics.Color.red(page)) <= 6)
        }
    }

    @Test fun theOwnerFramesTheAlbumArtworkInAFullScreenEditorThenPreviewsThePage() {
        AppSettings.reduceAnimations.value = true
        warmCover()
        val allowed = com.ketvoice.app.data.PublisherSession::class.java.getDeclaredField("_allowed").apply { isAccessible = true }
            .get(com.ketvoice.app.data.PublisherSession) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
        val track = Track("qa:album-frame", "QA title", "QA artist", "QA album", durationMs = 180_000)
        try {
            // A published framing: a wide band of the cover, so the hero is lower than the default one.
            com.ketvoice.app.data.MediaOverrides.replaceForTest(listOf(com.ketvoice.app.data.MediaOverride("album", "qa album|qa artist",
                frame = com.ketvoice.app.data.Frame(w = 1f, h = .5f))))
            allowed.value = true
            compose.setContent { KetvoiceTheme(useDark = true) {
                AlbumPage(CatalogRequest("album", "qa:album-frame", "QA album"),
                    CatalogPage("QA album", "QA artist", "fixture:warm-cover", listOf(track), "2026"), listOf(track), false, {})
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("album-artwork-loaded").fetchSemanticsNodes().isNotEmpty() }
            val hero = compose.onNodeWithTag("album-hero-visual").fetchSemanticsNode().boundsInRoot
            assertEquals("The centred cover stays square", hero.width, hero.height, 2f)
            // The three actions of the owner sit in one capsule.
            val pad = compose.onNodeWithTag("album-actions").fetchSemanticsNode().boundsInRoot
            for (tag in listOf("album-visuals", "album-framing", "album-share")) {
                val bounds = compose.onNodeWithTag(tag).assertHasClickAction().fetchSemanticsNode().boundsInRoot
                assertTrue("$tag is inside the capsule", bounds.left >= pad.left - 1f && bounds.right <= pad.right + 1f)
            }
            compose.onNodeWithTag("album-framing").performClick()
            compose.onNodeWithTag("framing-editor").assertIsDisplayed()
            compose.onNodeWithTag("framing-square").performClick()
            compose.onNodeWithTag("framing-done").performClick()
            // Back on the real page, with the draft shown and not yet published.
            compose.onAllNodesWithTag("framing-editor").assertCountEquals(0)
            compose.onNodeWithTag("album-framing-bar").assertIsDisplayed()
            val square = compose.onNodeWithTag("album-hero-visual").fetchSemanticsNode().boundsInRoot
            assertEquals("A square rectangle makes a square hero", square.width, square.height, 2f)
            image("album-framing-preview-dark")
            compose.onNodeWithTag("album-framing-default").assertHasClickAction()
            compose.onNodeWithTag("album-framing-edit").performClick()
            compose.onNodeWithTag("framing-editor").assertIsDisplayed()
            compose.onNodeWithTag("framing-cancel").performClick()
            compose.onNodeWithTag("album-framing-cancel").performClick()
            compose.onAllNodesWithTag("album-framing-bar").assertCountEquals(0)
        } finally {
            allowed.value = false
            com.ketvoice.app.data.MediaOverrides.replaceForTest(emptyList())
        }
    }

    @Test fun aFramingThatReachesPastWhatTheHeroShowsLeavesNoEmptyBand() {
        AppSettings.reduceAnimations.value = true
        warmCover()
        val track = Track("qa:album-reach", "QA title", "QA artist", "QA album", durationMs = 180_000)
        try {
            // A band near the top of a square cover, a mirror and a slight turn: all of it lies outside
            // the band a plain "cover the hero" shows, and the corners are pulled in by the turn.
            com.ketvoice.app.data.MediaOverrides.replaceForTest(listOf(com.ketvoice.app.data.MediaOverride("album", "qa album|qa artist",
                frame = com.ketvoice.app.data.Frame(cx = .05f, cy = -.28f, w = .7f, h = .28f, rotation = 6f, flip = true))))
            compose.setContent { KetvoiceTheme(useDark = true) {
                AlbumPage(CatalogRequest("album", "qa:album-reach", "QA album"),
                    CatalogPage("QA album", "QA artist", "fixture:warm-cover", listOf(track), "2026"), listOf(track), false, {})
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("album-artwork-loaded").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val hero = compose.onNodeWithTag("album-hero-visual").fetchSemanticsNode().boundsInRoot
            image("album-framing-reach") { shot ->
                // Inside the hero, away from the top scrim and the bottom dissolve, every edge is picture, not page.
                val y = (hero.top + hero.height * .45f).toInt()
                for (x in listOf(4, shot.width / 4, shot.width / 2, shot.width * 3 / 4, shot.width - 5)) {
                    val p = shot.getPixel(x, y)
                    assertTrue("picture at ($x,$y), not an empty band (r=${android.graphics.Color.red(p)}, b=${android.graphics.Color.blue(p)})",
                        android.graphics.Color.red(p) > android.graphics.Color.blue(p) + 12)
                }
                for (fraction in listOf(.18f, .6f)) {
                    val row = (hero.top + hero.height * fraction).toInt()
                    for (x in listOf(2, shot.width - 3)) {
                        val p = shot.getPixel(x, row)
                        assertTrue("edge at ($x,$row)", android.graphics.Color.red(p) > android.graphics.Color.blue(p) + 12)
                    }
                }
            }
        } finally { com.ketvoice.app.data.MediaOverrides.replaceForTest(emptyList()) }
    }

    @Test @Config(qualifiers = "w600dp-h800dp-mdpi")
    fun aWidePhoneWindowShowsTheWholeCoverInsteadOfABandOfIt() {
        AppSettings.reduceAnimations.value = true
        warmCover()
        val track = Track("qa:album-whole", "QA title", "QA artist", "QA album", durationMs = 180_000)
        compose.setContent { KetvoiceTheme(useDark = true) {
            AlbumPage(CatalogRequest("album", "qa:album-whole", "QA album"),
                CatalogPage("QA album", "QA artist", "fixture:warm-cover", listOf(track), "2026"), listOf(track), false, {})
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("album-artwork-loaded").fetchSemanticsNodes().isNotEmpty() }
        val hero = compose.onNodeWithTag("album-hero-visual").fetchSemanticsNode().boundsInRoot
        val cover = compose.onNodeWithTag("album-artwork-loaded").fetchSemanticsNode().boundsInRoot
        assertTrue("and is capped by the window height", hero.height <= 800f * .46f + 1f)
        assertEquals("The cover itself stays a whole square", cover.width, cover.height, 1f)
        assertEquals("centred in the header", 300f, (cover.left + cover.right) / 2f, 1.5f)
        compose.onNodeWithText("Lire").assertIsDisplayed()
        image("album-whole-cover-wide-dark")
    }

    @Test fun albumArtworkFillsTheWidthWithoutBeingOversized() {
        AppSettings.reduceAnimations.value = true
        AppSettings.albumHeroCanvas.value = true
        val track = Track("qa:album-hero", "QA title", "QA artist", "QA album", durationMs = 180_000)
        compose.setContent { KetvoiceTheme(useDark = true) {
            AlbumPage(CatalogRequest("album", "qa:album-hero", "QA album"),
                CatalogPage("QA album", "QA artist", null, listOf(track), "2026-11-07"),
                listOf(track), false, {})
        } }
        val hero = compose.onNodeWithTag("album-hero-visual").fetchSemanticsNode().boundsInRoot
        val width = compose.activity.window.decorView.width.toFloat()
        // 7.3: the cover floats centred, a square, instead of spanning the width.
        assertEquals("The cover is centred", width / 2f, (hero.left + hero.right) / 2f, 1.5f)
        assertEquals("The cover is a square", hero.width, hero.height, 1.5f)
        assertTrue("The cover is large", hero.width >= width * .55f)
        assertTrue("The artwork is never oversized", hero.height <= 421f)
        image("album-hero-full-bleed")
    }

    @Test fun normalCanvasAndCoverShareTheRestoredLegacyDissolve() {
        var video by mutableStateOf(true)
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                PlayerArtworkFrame(video, Color.Black,
                    Modifier.padding(top = 60.dp).size(200.dp, 400.dp).testTag("normal-canvas-frame")) {
                    Box(Modifier.fillMaxSize().background(Color.Cyan))
                }
            }
        } }
        val frame = compose.onNodeWithTag("normal-canvas-frame")
        val bounds = frame.fetchSemanticsNode().boundsInRoot
        val x = bounds.center.x.toInt()
        val y = (bounds.bottom - 20).toInt()
        image("normal-canvas-legacy-dissolve-host-fixture") {
            assertTrue("Legacy Canvas dissolves into the scene", android.graphics.Color.green(it.getPixel(x, y)) < 90)
            assertTrue("Upper image remains visible", android.graphics.Color.green(it.getPixel(x, bounds.top.toInt() + 100)) > 180)
        }
        compose.runOnIdle { video = false }
        image("normal-cover-dissolve-host-fixture") {
            assertTrue("Covers retain the same dissolve", android.graphics.Color.green(it.getPixel(x, y)) < 90)
        }
    }

    @Test fun albumHeroShowsYearTrackCountAndLocalizedTotalDurationOnHost() {
        AppSettings.reduceAnimations.value = true
        val track = Track("qa:album", "QA title", "QA artist", "QA album", durationMs = 4_620_000)
        compose.setContent { KetvoiceTheme(useDark = false) {
            AlbumPage(CatalogRequest("album", "qa:album", "Un album au titre très long"),
                CatalogPage("Un album au titre très long", "QA artist", null, listOf(track), "2026-10-31"),
                listOf(track), false, {})
        } }
        compose.onNodeWithText("2026-10-31  ·  1 titre  ·  1 h 17 min").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Lire").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Aléatoire").assertIsDisplayed()
        image("album-header-light")
    }

    @Test fun compactCanvasChromeHasOnlyMetadataSeekAndExitAndSeekingDoesNotExit() {
        // Static fallback isolates chrome layout/gestures; no video decoder/device validation.
        AppSettings.reduceAnimations.value = true
        AppSettings.animatedArtwork.value = false
        val track = Track("qa:canvas", "Un titre de Canvas", "QA artist", "QA album", durationMs = 180_000)
        var exited = false
        var seek = -1L
        compose.setContent { KetvoiceTheme(useDark = true) {
            CanvasFocusPlayer(track, "unused-host-fixture.mp4", false, track.durationMs,
                onExit = { exited = true }, onFailure = {}, onSeek = { seek = it })
        } }
        compose.onNodeWithTag("canvas-focus").assertIsDisplayed()
        compose.onNodeWithText(track.title).assertIsDisplayed()
        compose.onNodeWithText(track.artist).assertIsDisplayed()
        compose.onNodeWithContentDescription("Lecture").assertDoesNotExist()
        compose.onNodeWithContentDescription("File d'attente").assertDoesNotExist()
        image("canvas-focus-static-chrome")
        compose.onNodeWithTag("canvas-focus-seek").performTouchInput { click(androidx.compose.ui.geometry.Offset(center.x, 12f)) }
        compose.runOnIdle { assertFalse(exited); assertTrue(seek in 80_000..100_000) }
        compose.onNodeWithContentDescription("Quitter le plein écran").performClick()
        compose.runOnIdle { assertTrue(exited) }
    }

    @Test fun lyricsAppearAndRemainReadableWithReducedMotionAndSeparateBacksOnHost() {
        AppSettings.reduceAnimations.value = true
        val track = Track("qa:lyrics", "QA lyrics", "QA artist", "QA album")
        @Suppress("UNCHECKED_CAST")
        (LyricsRepo.state as MutableStateFlow<LyricsRepo.State>).value = LyricsRepo.State(
            lines = listOf(MusicApi.LyricLine(0, "Stay (oh yeah) with me"),
                MusicApi.LyricLine(3_000, "Another line that stays the same size"),
                MusicApi.LyricLine(6_000, "A softer entrance")), isTimed = true)
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF282330)).padding(24.dp)) {
                LyricsPane(track, true, {}, Modifier.fillMaxSize())
            }
        } }
        compose.onNodeWithText("Stay with me").assertIsDisplayed()
        compose.onAllNodesWithText("oh yeah").assertCountEquals(1)
        image("lyrics-reduced-motion-host")
        compose.runOnIdle { AppSettings.reduceAnimations.value = false }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Stay with me").assertIsDisplayed()
        image("lyrics-motion-host")
    }

    @Test fun prismalShaderCompilesAndRefractsRealSourcePixelsOnHostSkia() {
        val source = Bitmap.createBitmap(360, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        val paint = Paint()
        for (x in 0 until source.width step 8) for (y in 0 until source.height step 8) {
            paint.color = if ((x / 8 + y / 8) % 2 == 0) android.graphics.Color.rgb(50, 110, 180)
                else android.graphics.Color.rgb(225, 130, 100)
            canvas.drawRect(x.toFloat(), y.toFloat(), x + 8f, y + 8f, paint)
        }
        fun render(amount: Float, pressure: Float = 0f): Bitmap {
        val shader = RuntimeShader(GLASS_AGSL)
        shader.setInputShader("content", BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setFloatUniform("size", 320f, 60f)
        shader.setFloatUniform("offset", -20f, -20f)
        shader.setFloatUniform("contentSize", 360f, 100f)
        shader.setFloatUniform("cornerRadii", 30f, 30f, 30f, 30f)
        shader.setFloatUniform("refractionHeight", 12f)
        shader.setFloatUniform("refractionAmount", amount)
        shader.setFloatUniform("dispersion", .035f)
        shader.setFloatUniform("depthEffect", 0f)
        shader.setFloatUniform("tint", 1f, 1f, 1f, .04f)
        shader.setFloatUniform("saturation", 1.2f)
        shader.setFloatUniform("brightness", 1f)
        shader.setFloatUniform("edgeDark", .055f)
        shader.setFloatUniform("rimLight", .5f)
        shader.setFloatUniform("sheen", .03f)
        shader.setFloatUniform("materialDark", 0f)
        shader.setFloatUniform("lightDir", -.7071f, -.7071f)
        shader.setFloatUniform("pixelScale", 1f)
        shader.setFloatUniform("opticalPressure", pressure)
            val bitmap = Bitmap.createBitmap(360, 100, Bitmap.Config.ARGB_8888)
            val target = Canvas(bitmap)
            ReflectionHelpers.setField(target, "mAllowHwFeaturesInSwMode", true)
            target.drawRect(0f, 0f, 360f, 100f, Paint().apply { this.shader = shader })
            return bitmap
        }
        val glass = render(24f)
        val flat = render(0f)
        val pressed = render(24f, 1f)
        assertEquals(0, android.graphics.Color.alpha(glass.getPixel(20, 20)))
        assertEquals(255, android.graphics.Color.alpha(glass.getPixel(180, 50)))
        assertEquals("Edge displacement must not warp the control's centre", flat.getPixel(180, 50), glass.getPixel(180, 50))
        var shifted = 0
        // A single checkerboard scanline can land on the same periodic tile after
        // refraction. Compare an edge band instead of assuming one pixel phase.
        for (y in 21..26) for (x in 70..280) if (glass.getPixel(x, y) != flat.getPixel(x, y)) shifted++
        val directory = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}").apply { mkdirs() }
        File(directory, "prismal-optical-grid.png").outputStream().use { glass.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "prismal-flat-grid.png").outputStream().use { flat.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("Edge refraction must move the sampled source, not just tint it (shifted=$shifted)", shifted > 500)
        var pressureShifted = 0
        for (y in 21..26) for (x in 70..280) if (glass.getPixel(x, y) != pressed.getPixel(x, y)) pressureShifted++
        assertTrue("Pressure must deform sampled edge pixels", pressureShifted > 100)
        assertEquals("Pressure keeps centre pixels calm", glass.getPixel(180, 50), pressed.getPixel(180, 50))
        File(directory, "optical-pressure-grid.png").outputStream().use { pressed.compress(Bitmap.CompressFormat.PNG, 100, it) }
        source.recycle(); glass.recycle(); flat.recycle(); pressed.recycle()
    }
}
