package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.AccountProfile
import com.ketvoice.app.account.PublicListening
import com.ketvoice.app.account.PublicPlaylist
import com.ketvoice.app.account.PublicProfile
import com.ketvoice.app.account.PublicProfiles
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.data.PlaylistRules
import com.ketvoice.app.data.Track
import com.ketvoice.app.sync.SharedInvite
import com.ketvoice.app.sync.SharedMember
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.account.ProfileContent
import com.ketvoice.app.ui.account.ProfileLoad
import com.ketvoice.app.ui.account.PublicProfileSwitches
import com.ketvoice.app.ui.library.MembersSheet
import com.ketvoice.app.ui.library.PlaylistMenuSheet
import com.ketvoice.app.ui.library.playlistsSection
import com.ketvoice.app.ui.library.PlaylistShelf
import com.ketvoice.app.ui.library.SharedBadge
import com.ketvoice.app.ui.library.ShareSheet
import com.ketvoice.app.ui.library.ShareState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Shared playlists and the public profile, drawn in software on the host (not on a phone): badge, sheets, profile page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedPlaylistsHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setup() {
        com.ketvoice.app.ui.library.AvatarDecode.dispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
    }

    @After fun reset() { com.ketvoice.app.ui.library.AvatarDecode.dispatcher = kotlinx.coroutines.Dispatchers.IO;
        AppSettings.reduceAnimations.value = false
        PublicProfiles.showForTest(emptySet())
    }

    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        view.draw(canvas)
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Coil decodes photos on its own threads: give it a moment, then let the main thread draw the result. */
    private fun image(name: String) {
        repeat(3) { Thread.sleep(200); compose.waitForIdle() }
        // Coil fades a photo in against the system clock, which Robolectric holds still: move it past the fade.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2))
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.runOnIdle { save(compose.activity.window.decorView, name) }
    }

    private fun page(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { KetvoiceTheme(useDark = dark) {
            Box(Modifier.fillMaxSize().background(LocalTheme.current.background)) { content() }
        } }
    }

    /** A sheet at the bottom of a dimmed page, as the app shows it. */
    private fun sheet(dark: Boolean, content: @Composable () -> Unit) = page(dark) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.widthIn(max = 560.dp).fillMaxWidth().statusBarsPadding().navigationBarsPadding()) { content() }
        }
    }

    private fun track(id: String, title: String, artist: String) = Track(id, title, artist, "Album", durationMs = 200_000)

    private fun photo(name: String, argb: Int): File {
        val file = File.createTempFile("avatar-$name", ".png")
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { it.eraseColor(argb) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.deleteOnExit()
        return file
    }

    @Test fun theBadgeSitsBesideTheTitleCountOfASharedPlaylist() {
        val shared = LocalPlaylist(PlaylistRules.sharedLocalId("abc"), "Road trip",
            listOf(PlaylistEntry(title = "A", artist = "B", track = track("1", "A", "B"))), createdAt = 1)
        val own = LocalPlaylist("own", "Mes favoris", emptyList(), createdAt = 2)
        page(true) { Column(Modifier.padding(vertical = 24.dp)) {
            PlaylistShelf(listOf(shared, own))
            Spacer(Modifier.height(24.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(260.dp)) {
                playlistsSection(listOf(shared, own), grid = false, paneDp = 360f, owner = "Vous",
                    enabled = true, onPin = null, onNew = {}, onImport = {})
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.padding(16.dp)) { SharedBadge() }
        } }
        // The row of Library › Playlists merges its badge into its own semantics: count the unmerged tree.
        compose.onAllNodesWithTag("shared-badge", useUnmergedTree = true).assertCountEquals(3)
        image("shared-badge")
    }

    @Test fun theShareSheetShowsTheCodeAndTheLinkAsText() {
        val invite = SharedInvite("ABCDEFGH23", "2026-11-25T10:00:00+00:00")
        sheet(true) { ShareSheet("Road trip", ShareState.Ready(invite), {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("share-code").assertIsDisplayed()
        compose.onNodeWithTag("share-link").assertTextContains("ketvoice://join/ABCDEFGH23")
        compose.onNodeWithTag("share-copy-code").assertIsDisplayed()
        compose.onNodeWithTag("share-copy-link").assertIsDisplayed()
        compose.onNodeWithTag("share-system").assertIsDisplayed()
        image("shared-share-sheet")
    }

    @Test fun theShareSheetInTheLightThemeAndWhileItLoadsOrFails() {
        sheet(false) { ShareSheet("Road trip", ShareState.Ready(SharedInvite("ZXCVBNM234", "x")), {}, {}, {}, {}, {}) }
        image("shared-share-sheet-light")
    }

    @Test fun theShareSheetAsksAgainWhenTheInvitationFailed() {
        sheet(true) { ShareSheet("Road trip", ShareState.Failed("Hors ligne : réessayez une fois connecté."), {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Réessayer").assertIsDisplayed()
        image("shared-share-sheet-failed")
    }

    private val members = listOf(
        SharedMember("u1", true, "Alice Martin", "alice", "u1/avatar-1.jpg", true),
        SharedMember("u2", false, "Bob", "", "u2/avatar-2.jpg", false),
        SharedMember("u3", false, "Camille", "camille", "", true))

    @Test fun theMembersSheetShowsTheirPhotosAndLetsTheOwnerRemove() {
        val photos = mapOf("u1" to photo("1", 0xFFB5651D.toInt()), "u2" to photo("2", 0xFF2C7DA0.toInt()))
        var removed: String? = null
        sheet(true) { MembersSheet("Road trip", members, false, "u1", true, photos, false, {}, { removed = it.userId }, {}, {}) }
        compose.onNodeWithTag("members-sheet").assertIsDisplayed()
        compose.onAllNodesWithTag("member-avatar", useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithText("Propriétaire").assertIsDisplayed()
        compose.onAllNodesWithText("Retirer").assertCountEquals(2)
        compose.onAllNodesWithText("Retirer")[0].performClick()
        assertTrue(removed == "u2")
        image("shared-members-owner")
    }

    @Test fun anEditorSeesNoRemoveButtonButCanLeave() {
        var left = false
        sheet(false) { MembersSheet("Road trip", members, false, "u2", false, emptyMap(), true, {}, {}, { left = true }, {}) }
        compose.onAllNodesWithText("Retirer").assertCountEquals(0)
        compose.onNodeWithText("Quitter la playlist").performClick()
        assertTrue(left)
        image("shared-members-editor")
    }

    @Test fun aPersonWithAPublicProfileOpensIt() {
        var opened: String? = null
        sheet(true) { MembersSheet("Road trip", members, false, "u2", false, emptyMap(), false, { opened = it.handle }, {}, {}, {}) }
        compose.onNodeWithTag("member-u1").performClick()
        compose.onNodeWithTag("member-u2").performClick()
        assertTrue(opened == "alice")
    }

    @Test fun thePlaylistMenuOffersSharingMembersAndThePublicProfile() {
        val shared = LocalPlaylist(PlaylistRules.sharedLocalId("abc"), "Road trip", emptyList())
        com.ketvoice.app.sync.SharedPlaylists.showForTest(com.ketvoice.app.sync.SharedState("me",
            mapOf("abc" to com.ketvoice.app.sync.SharedList("abc", "Road trip", "me", emptyList()))))
        page(true) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            PlaylistMenuSheet(shared, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Membres").assertIsDisplayed()
        compose.onNodeWithText("Partager la playlist").assertIsDisplayed()
        compose.onNodeWithText("Supprimer la playlist").assertIsDisplayed()
        image("shared-playlist-menu")
        com.ketvoice.app.sync.SharedPlaylists.showForTest(com.ketvoice.app.sync.SharedState())
    }

    @Test fun anEditorsMenuOffersMembersAndLeavingButNotSharingOrRenaming() {
        val shared = LocalPlaylist(PlaylistRules.sharedLocalId("abc"), "Road trip", emptyList())
        page(true) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            PlaylistMenuSheet(shared, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Membres").assertIsDisplayed()
        compose.onNodeWithText("Quitter la playlist").assertIsDisplayed()
        compose.onNodeWithText("Partager la playlist").assertDoesNotExist()
        compose.onNodeWithText("Renommer").assertDoesNotExist()
        image("shared-playlist-menu-editor")
    }

    @Test fun aPersonalPlaylistMenuOffersToShareItAndToMakeItPublic() {
        page(true) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            PlaylistMenuSheet(LocalPlaylist("own", "Mes favoris", emptyList()), {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Partager la playlist").assertIsDisplayed()
        compose.onNodeWithText("Rendre cette playlist publique").assertIsDisplayed()
        image("shared-playlist-menu-personal")
    }

    private val profile = PublicProfile("alice", "Alice Martin", null, listOf(
        PublicPlaylist("p1", "Été 2026", null, listOf(track("1", "Sunset", "Band"), track("2", "Waves", "Band"), track("3", "Palms", "Duo"))),
        PublicPlaylist("p2", "Nuit", null, listOf(track("4", "Neon", "Solo")))),
        PublicListening("Neon Lights", "Solo", null, true))

    @Test fun aProfilePageShowsThePhotoTheNameTheCurrentTitleAndThePublicPlaylists() {
        var played: String? = null
        page(true) { ProfileContent("alice", ProfileLoad.Loaded(profile), photo("alice", 0xFF7B3FA0.toInt()),
            { played = it.id }, { _, _ -> }, {}, {}, {}) }
        compose.onNodeWithTag("profile-name").assertTextContains("Alice Martin")
        compose.onNodeWithTag("profile-handle").assertTextContains("@alice")
        compose.onNodeWithTag("profile-listening").assertIsDisplayed()
        compose.onNodeWithTag("public-playlist-p1").assertIsDisplayed()
        compose.onNodeWithText("Été 2026").performClick()
        assertTrue(played == "p1")
        image("profile-page")
    }

    @Test fun aProfilePageInTheLightThemeWithoutListeningShowsOnlyPlaylists() {
        page(false) { ProfileContent("alice", ProfileLoad.Loaded(profile.copy(listening = null)), null, {}, { _, _ -> }, {}, {}, {}) }
        compose.onNodeWithTag("profile-listening").assertDoesNotExist()
        compose.onAllNodesWithTag("public-playlist-toggle").assertCountEquals(2)
        image("profile-page-light")
    }

    @Test fun anUnknownOrPrivateProfileSaysSoWithoutSayingWhich() {
        page(true) { ProfileContent("nobody", ProfileLoad.NotFound, null, {}, { _, _ -> }, {}, {}, {}) }
        compose.onNodeWithText("Profil introuvable ou privé.").assertIsDisplayed()
        image("profile-page-not-found")
    }

    @Test fun theAccountSheetOffersTheTwoSwitchesOffByDefault() {
        page(true) { Column(Modifier.padding(horizontal = 22.dp).padding(top = 40.dp)) {
            PublicProfileSwitches(AccountProfile("Alice", "alice"), null, onPublic = {}, onListening = {})
        } }
        compose.onNodeWithText("Profil public").assertIsDisplayed()
        compose.onNodeWithText("Montrer ce que j'écoute").assertIsDisplayed()
        compose.onNodeWithTag("see-my-profile").assertDoesNotExist()
        image("profile-switches-off")
    }

    @Test fun onceThePublicSwitchIsOnTheListeningSwitchWorksAndMyProfileCanBeSeen() {
        var changed: Boolean? = null
        page(false) { Column(Modifier.padding(horizontal = 22.dp).padding(top = 40.dp)) {
            PublicProfileSwitches(AccountProfile("Alice", "alice", isPublic = true, showListening = true), null, onPublic = {}, onListening = { changed = it })
        } }
        compose.onNodeWithTag("see-my-profile").assertIsDisplayed()
        compose.onNodeWithTag("switch-show-listening").performClick()
        image("profile-switches-on")
    }
}
