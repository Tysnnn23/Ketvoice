package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.account.PublicListening
import com.ketvoice.app.account.PublicPlaylist
import com.ketvoice.app.account.PublicProfile
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.sync.FeedItem
import com.ketvoice.app.sync.FeedKind
import com.ketvoice.app.sync.FeedPlaylist
import com.ketvoice.app.sync.FeedProblem
import com.ketvoice.app.sync.FeedState
import com.ketvoice.app.sync.FollowLists
import com.ketvoice.app.sync.FollowPerson
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.account.ProfileContent
import com.ketvoice.app.ui.account.ProfileLoad
import com.ketvoice.app.ui.friends.ConfirmFollowAction
import com.ketvoice.app.ui.friends.FeedActions
import com.ketvoice.app.ui.friends.FeedWords
import com.ketvoice.app.ui.friends.FollowListsContent
import com.ketvoice.app.ui.friends.FollowRequest
import com.ketvoice.app.ui.friends.FollowTab
import com.ketvoice.app.ui.friends.FriendsFeedContent
import com.ketvoice.app.ui.friends.FriendsHomeContent
import com.ketvoice.app.ui.friends.FriendsSettingsContent
import com.ketvoice.app.ui.friends.ProfileFollow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** The friends feed, drawn in software on the host (not on a phone): Home section, feed page, profile with Follow, lists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FriendsFeedHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = java.time.Instant.parse("2026-11-18T12:00:00Z").toEpochMilli()

    @Before fun setup() {
        com.ketvoice.app.ui.library.AvatarDecode.dispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
    }

    @After fun reset() { com.ketvoice.app.ui.library.AvatarDecode.dispatcher = kotlinx.coroutines.Dispatchers.IO; AppSettings.reduceAnimations.value = false }

    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        view.draw(canvas)
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun image(name: String) {
        repeat(3) { Thread.sleep(150); compose.waitForIdle() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.runOnIdle { save(compose.activity.window.decorView, name) }
    }

    private fun page(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { KetvoiceTheme(useDark = dark) {
            Box(Modifier.fillMaxSize().background(LocalTheme.current.background)) { content() }
        } }
    }

    private fun photo(name: String, argb: Int): File {
        val file = File.createTempFile("avatar-$name", ".png")
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { it.eraseColor(argb) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.deleteOnExit()
        return file
    }

    private val aliceRef = "dddddddd-0000-4000-8000-00000000000d"
    private val photos by lazy { mapOf(aliceRef to photo("alice", 0xFF5E5CE6.toInt())) }

    private fun item(n: Int, kind: FeedKind, minutesAgo: Long, name: String = "Alice", handle: String = "alice", ref: String? = aliceRef,
                     reactions: Map<String, Int> = emptyMap(), mine: String? = null, titles: Int = 1): FeedItem {
        val at = now - minutesAgo * 60_000
        val track = if (kind == FeedKind.Listening) Track("t$n", "Midnight City", "M83", "Hurry Up, We're Dreaming", 240_000) else null
        val playlist = if (kind == FeedKind.Listening) null else FeedPlaylist("pl-$n", if (n == 2) "Road trip d'été" else "Soirée chill", null)
        return FeedItem("00000000-0000-4000-8000-00000000000$n", kind, java.time.Instant.ofEpochMilli(at).toString(), at, handle, name,
            ref, playlist, track, titles, reactions, mine)
    }

    private val items by lazy { listOf(
        item(1, FeedKind.Listening, 3, reactions = mapOf("heart" to 2, "fire" to 1), mine = "heart"),
        item(2, FeedKind.Published, 75, name = "Bob", handle = "bob", ref = null, reactions = mapOf("clap" to 4)),
        item(3, FeedKind.Added, 60 * 26, titles = 3),
        item(4, FeedKind.Listening, 60 * 50, name = "Carol Martin", handle = "carol", ref = null)) }

    @Test fun theWordsOfALine() {
        assertEquals("écoute", FeedWords.action(items[0], now))
        assertEquals("a écouté", FeedWords.action(items[3], now))
        assertEquals("a publié une playlist", FeedWords.action(items[1], now))
        assertEquals("a ajouté 3 titres", FeedWords.action(items[2], now))
        assertEquals("Midnight City · M83", FeedWords.subject(items[0]))
        assertEquals("il y a 3 min", FeedWords.ago(items[0].atMs, now)); assertEquals("il y a 1 h", FeedWords.ago(items[1].atMs, now))
        assertEquals("il y a 1 j", FeedWords.ago(items[2].atMs, now)); assertEquals("à l'instant", FeedWords.ago(now, now))
    }

    @Test fun theHomeSectionShowsTheThreeLatestLines() {
        page(true) { Column(Modifier.statusBarsPadding().padding(top = 12.dp)) { FriendsHomeContent(items.take(3), now, photos = photos, onAll = {}, onOpen = {}) } }
        compose.onNodeWithText("Activité de vos amis").assertIsDisplayed()
        compose.onNodeWithTag("home-friends-all").assertIsDisplayed()
        compose.onAllNodesWithTag("feed-reactions").assertCountEquals(0)
        image("friends-home-section")
    }

    @Test fun theHomeSectionInTheLightTheme() {
        page(false) { Column(Modifier.statusBarsPadding().padding(top = 12.dp)) { FriendsHomeContent(items.take(3), now, photos = photos, onAll = {}, onOpen = {}) } }
        image("friends-home-section-light")
    }

    @Test fun theFeedPageWithReactions() {
        var reacted: String? = null
        page(true) { FriendsFeedContent(FeedState(items = items, loaded = true), true, FeedActions(onReact = { _, key -> reacted = key }), now, photos = photos) }
        compose.onNodeWithTag("feed-item-" + items[0].id).assertIsDisplayed()
        compose.onAllNodesWithTag("reaction-fire")[0].performClick()
        assertEquals("fire", reacted)
        compose.onAllNodesWithTag("reaction-heart")[0].assertIsSelected()
        image("friends-feed-page")
    }

    @Test fun theFeedPageInTheLightThemeOffline() {
        page(false) { FriendsFeedContent(FeedState(items = items, loaded = true, problem = FeedProblem.Offline), true, FeedActions(), now, photos = photos) }
        compose.onNodeWithTag("friends-offline").assertIsDisplayed()
        image("friends-feed-page-light-offline")
    }

    @Test fun theFeedPageEmptyFailedAndSignedOut() {
        page(true) { FriendsFeedContent(FeedState(loaded = true), true, FeedActions(), now) }
        compose.onNodeWithText("Aucune activité pour l'instant").assertIsDisplayed()
        image("friends-feed-empty")
    }

    @Test fun theFeedPageWhenItCannotLoad() {
        page(true) { FriendsFeedContent(FeedState(problem = FeedProblem.Unavailable), true, FeedActions(), now) }
        compose.onNodeWithText("Fil indisponible").assertIsDisplayed()
        compose.onNodeWithText("Réessayer").assertIsDisplayed()
        image("friends-feed-failed")
    }

    @Test fun theFeedPageSignedOut() {
        page(false) { FriendsFeedContent(FeedState(), false, FeedActions(), now) }
        compose.onNodeWithText("Se connecter").assertIsDisplayed()
        image("friends-feed-signed-out")
    }

    private val profile = PublicProfile("alice", "Alice", aliceRef,
        listOf(PublicPlaylist("p1", "Road trip d'été", null, listOf(Track("1", "One", "A", "X"), Track("2", "Two", "B", "Y")))),
        PublicListening("Midnight City", "M83", null, true))

    @Test fun aProfileWithFollow() {
        var toggled = false
        page(true) { ProfileContent("alice", ProfileLoad.Loaded(profile), photos[aliceRef], {}, { _, _ -> }, {}, {}, {},
            follow = ProfileFollow(false, false, null, { toggled = true }, {})) }
        compose.onNodeWithTag("profile-follow").assertIsDisplayed().performClick()
        assertEquals(true, toggled)
        compose.onNodeWithTag("profile-block").assertExists()
        image("friends-profile-follow")
    }

    @Test fun aProfileAlreadyFollowedWithANote() {
        page(false) { ProfileContent("alice", ProfileLoad.Loaded(profile.copy(following = true)), photos[aliceRef], {}, { _, _ -> }, {}, {}, {},
            follow = ProfileFollow(true, false, "Vous suivez déjà 500 comptes.", {}, {})) }
        compose.onNodeWithTag("profile-unfollow").assertIsDisplayed()
        compose.onNodeWithText("Abonné").assertIsDisplayed()
        image("friends-profile-following-light")
    }

    @Test fun aProfileOfOnesOwnHasNoFollow() {
        page(true) { ProfileContent("me", ProfileLoad.Loaded(profile.copy(isMe = true)), null, {}, { _, _ -> }, {}, {}, {}) }
        compose.onAllNodesWithTag("profile-follow").assertCountEquals(0)
        compose.onAllNodesWithTag("profile-block").assertCountEquals(0)
    }

    private val lists = FollowLists(
        following = listOf(FollowPerson("00000000-0000-4000-8000-0000000000f1", "alice", "Alice", aliceRef, true, false)),
        followers = listOf(FollowPerson("00000000-0000-4000-8000-0000000000f2", "bob", "Bob", null, true, true),
            FollowPerson("00000000-0000-4000-8000-0000000000f3", "carol", "Carol Martin", null, false, false)),
        blocked = emptyList())

    @Test fun theFollowersListWithRemoveAndBlock() {
        var asked: FollowRequest? = null
        page(true) { FollowListsContent(lists, false, FollowTab.Followers, null, photos = photos, onAction = { asked = it }) }
        compose.onNodeWithTag("follow-tab-Followers").assertIsSelected()
        compose.onNodeWithText("Abonnés (2)").assertIsDisplayed()
        compose.onNodeWithText("@carol · Profil privé").assertIsDisplayed()
        compose.onAllNodesWithTag("follower-block")[1].performClick()
        assertEquals(FollowRequest(lists.followers[1], "block"), asked)
        image("friends-followers-list")
    }

    @Test fun theFollowingListAndTheEmptyBlockedList() {
        page(false) { FollowListsContent(lists, false, FollowTab.Following, null, photos = photos) }
        compose.onNodeWithTag("following-unfollow").assertIsDisplayed()
        image("friends-following-list-light")
    }

    @Test fun blockingAFollowerIsConfirmed() {
        page(true) { Box(Modifier.fillMaxSize()) {
            FollowListsContent(lists, false, FollowTab.Followers, null, photos = photos)
            ConfirmFollowAction(FollowRequest(lists.followers[1], "block"), {}, {})
        } }
        // The alert is a window of its own (not drawn into the page's capture): its words are checked instead.
        compose.onNodeWithText("Bloquer @carol ?").assertIsDisplayed()
        compose.onNodeWithText("Cette personne ne pourra plus vous suivre, voir votre profil ni réagir à votre activité. Elle n'en est pas avertie.")
            .assertIsDisplayed()
    }

    @Test fun theFriendsPartOfTheAccountSheet() {
        page(true) { Column(Modifier.statusBarsPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
            FriendsSettingsContent(isPublic = true, share = true, error = null, onFeed = {}, onLists = {})
            Spacer(Modifier.height(24.dp))
            FriendsSettingsContent(isPublic = false, share = true, error = null, onFeed = {}, onLists = {})
        } }
        compose.onAllNodesWithTag("switch-share-activity").assertCountEquals(2)
        image("friends-account-settings")
    }
}
