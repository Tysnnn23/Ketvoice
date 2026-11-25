package com.ketvoice.desktop.social

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.AccountState
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.together.TogetherTransport
import com.ketvoice.desktop.platform.DesktopPlatform
import com.ketvoice.desktop.ui.FixtureCatalog
import com.ketvoice.desktop.ui.Fixtures
import com.ketvoice.desktop.ui.IslandMarks
import com.ketvoice.desktop.ui.KetvoiceShell
import com.ketvoice.desktop.ui.Navigator
import com.ketvoice.desktop.ui.PageRegistry
import com.ketvoice.desktop.ui.PanelTabs
import com.ketvoice.desktop.ui.RecordingPlayer
import com.ketvoice.desktop.ui.Route
import com.ketvoice.desktop.ui.ShellHooks
import com.ketvoice.desktop.ui.ShellState
import com.ketvoice.desktop.ui.TrackMenuSlots
import org.jetbrains.skia.EncodedImageFormat
import org.json.JSONObject
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The social pieces in the real shell: « Ensemble », « Activité des amis », the profile page, ketvoice:// links. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SocialShellTest {
    private val server = FakeSocial()
    private val uid = "aaaaaaaa-0000-4000-8000-000000000001"

    @BeforeTest fun setUp() {
        Fixtures.installCovers()
        AppSettings.reduceAnimations.value = true
        Social.backend = server; Social.clear()
        DesktopTogether.reset()
        DesktopTogether.post = { it.run() }
        DesktopTogether.transports = { _, _, _, _ -> object : TogetherTransport {
            override fun start() {}
            override fun send(payload: JSONObject) = false
            override fun close() {}
        } }
        SocialFeature.register()
    }

    @AfterTest fun tearDown() {
        DesktopTogether.reset(); Social.backend = RestSocial; Social.clear()
        listOf(SocialPages.TOGETHER_TAB, SocialPages.FRIENDS_TAB).forEach(PanelTabs::unregister)
        listOf(SocialPages.PROFILE, SocialPages.SHARED_PLAYLIST).forEach(PageRegistry::unregister)
        IslandMarks.unregister("together"); ShellHooks.unregister("social")
        TrackMenuSlots.share = null; TrackMenuSlots.shareAlbum = null; TrackMenuSlots.sharePlaylist = null; TrackMenuSlots.extra.clear()
        AccountSession.showForTest(AccountState())
        DesktopPlatform.pendingLinks.value = emptyList()
    }

    @Composable
    private fun Shell(nav: Navigator, shell: ShellState, dark: Boolean = true) =
        KetvoiceShell(RecordingPlayer().playing(), Fixtures.library(), FixtureCatalog, dark = dark, navigator = nav, shell = shell)

    @Test fun ensembleOpensARoomAndTheIslandSaysSo() = runComposeUiTest {
        val shell = ShellState(SocialPages.TOGETHER_TAB)
        setContent { Shell(remember { Navigator(Route.Home) }, shell) }
        waitForIdle()
        onNodeWithTag("panel:together").assertExists()
        onNodeWithTag("together-create").performClick()
        waitForIdle()
        onNodeWithTag("together-room").assertExists()
        onNodeWithTag("together-rules").assertExists()
        onNodeWithTag("together-badge").assertExists()
        assertTrue(DesktopTogether.ui.value.code.length == 6)
        onNodeWithTag("together-leave").performClick()
        waitForIdle()
        onNodeWithTag("together-ended").assertExists()
    }

    @Test fun signedOutTheFriendsTabAndTheProfileAskToSignIn() = runComposeUiTest {
        server.uid = null
        val nav = Navigator(Route.Home)
        setContent { Shell(nav, remember { ShellState(SocialPages.FRIENDS_TAB) }) }
        waitForIdle()
        onNodeWithTag("panel:friends").assertExists()
        onNodeWithTag("friends-add").assertDoesNotExist()
        nav.push(Route.Extra(SocialPages.PROFILE))
        waitForIdle()
        onNodeWithTag("page:profile").assertExists()
        onNodeWithTag("profile-header").assertDoesNotExist()
    }

    @Test fun myProfileHasItsEditModeAndItsModulesReach() = runComposeUiTest {
        AccountSession.showForTest(AccountState(uid))
        val nav = Navigator(Route.Extra(SocialPages.PROFILE))
        setContent { Shell(nav, remember { ShellState(SocialPages.FRIENDS_TAB) }) }
        waitUntil(timeoutMillis = 5_000) { Social.me.value != null }
        waitForIdle()
        onNodeWithTag("profile-header").assertExists()
        onNodeWithTag("profile-module:top").assertExists()
        onNodeWithTag("profile-reach:now:2").performClick()
        waitUntil(timeoutMillis = 5_000) { server.modules["now"]?.getString("reach") == "public" }
        onNodeWithTag("profile-edit").performClick()
        waitForIdle()
        onNodeWithTag("profile-editor").assertExists()
        // « Activité des amis » for a signed-in listener: the switch, adding a friend.
        onNodeWithTag("toggle:friends-share").assertExists()
        onNodeWithTag("friends-add").assertExists()
    }

    @Test fun someoneElsesProfileOffersFriendship() = runComposeUiTest {
        AccountSession.showForTest(AccountState(uid))
        server.answers["ketvoice_profile_view"] = """{"handle":"bob","relation":"stranger","friend":"none","private":false,"display_name":"Bob",
            "bio":"Basse et batterie","is_public":true,"modules":{"top":{"reach":"public","data":{"items":[{"name":"Néon"}]}}}}"""
        server.answers["ketvoice_friend_request"] = """{"status":"requested","ref":"c4ef6b1b-e2f5-4705-85bf-9804aa63e070"}"""
        val nav = Navigator(Route.Home)
        setContent { Shell(nav, remember { ShellState() }) }
        // A ketvoice:// link from the browser opens the page.
        DesktopPlatform.open(listOf("ketvoice://u/Bob"))
        waitUntil(timeoutMillis = 5_000) { nav.current == Route.Extra(SocialPages.PROFILE, "bob") }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("profile-friend:Ask").assertExists() }.isSuccess }
        server.answers["ketvoice_profile_view"] = server.answers["ketvoice_profile_view"]!!.replace("\"friend\":\"none\"", "\"friend\":\"outgoing\"")
        onNodeWithTag("profile-friend:Ask").performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("profile-friend:Cancel").assertExists() }.isSuccess }
        onNodeWithTag("profile-top:0").assertExists()
        assertTrue(DesktopPlatform.pendingLinks.value.isEmpty())
    }

    /** Renders for review, to build/desktop-ui-preview/social-*.png (no network). */
    @Test fun renders() {
        val out = File("build/desktop-ui-preview").apply { mkdirs() }
        AccountSession.showForTest(AccountState(uid))
        server.profile.put("bio", "Nuits blanches, synthés et basses rondes.").put("accent", "#5E5CE6").put("is_public", true)
        server.modules["top"] = JSONObject().put("reach", "friends").put("data", JSONObject())
        server.answers["ketvoice_friends_now"] = """[{"handle":"luna.k","display_name":"Luna","playing":true,"room":"ABC234-ABCDEFGH",
            "track":{"id":"yt:abcdefghijk","title":"Ocean Drive","artist":"Néon"}},{"handle":"marc","display_name":"Marc","playing":false,
            "track":{"id":"yt:bcdefghijkl","title":"Marée basse","artist":"Néon"}}]"""
        server.answers["ketvoice_friend_lists"] = """{"friends":[{"ref":"c4ef6b1b-e2f5-4705-85bf-9804aa63e070","handle":"luna.k","display_name":"Luna"}],
            "incoming":[{"ref":"350697da-d20d-4a71-82cc-2c0b16d9dc40","handle":"sam","display_name":"Sam"}],"outgoing":[]}"""
        fun shoot(name: String, nav: Navigator, shell: ShellState, dark: Boolean = true) {
            val scene = ImageComposeScene(1440, 900, Density(1f)) { Shell(nav, shell, dark) }
            try {
                var t = 0L
                repeat(60) { scene.render(t); t += 50_000_000L; Thread.sleep(5) }
                File(out, "social-$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            } finally { scene.close() }
        }
        shoot("profile-dark", Navigator(Route.Extra(SocialPages.PROFILE)), ShellState(SocialPages.FRIENDS_TAB))
        shoot("profile-light", Navigator(Route.Extra(SocialPages.PROFILE)), ShellState(SocialPages.FRIENDS_TAB), dark = false)
        shoot("together-dark", Navigator(Route.Home), ShellState(SocialPages.TOGETHER_TAB))
        DesktopTogether.create("PC")
        shoot("room-dark", Navigator(Route.Home), ShellState(SocialPages.TOGETHER_TAB))
        assertTrue(File(out, "social-profile-dark.png").length() > 10_000)
        assertEquals(6, DesktopTogether.ui.value.code.length)
    }
}
