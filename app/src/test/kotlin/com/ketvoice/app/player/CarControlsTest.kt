@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import android.app.Application
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.session.CommandButton
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.R
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The car's Now Playing buttons (8.4): their look follows the app, and each goes through the phone's queue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarControlsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val state get() = Player.state as MutableStateFlow<PlayerUiState>
    private fun field(name: String) = Player::class.java.getDeclaredField(name).apply { isAccessible = true }
    private lateinit var original: PlayerUiState
    private var oldController: Any? = null
    private lateinit var liked: Set<String>
    private var ketmix = false
    private val queue = listOf("a", "b", "c").map { Track("car:$it", "Title $it", "Artist", "Album") }

    @Before fun setup() {
        original = state.value
        oldController = field("controller").get(Player)
        field("controller").set(Player, null)
        liked = AppSettings.favoriteIds.value
        ketmix = AppSettings.automix.value
        state.value = PlayerUiState(queue = queue, queueEntryIds = listOf("k0", "k1", "k2"), index = 1)
    }

    @After fun cleanup() {
        state.value = original
        field("controller").set(Player, oldController)
        AppSettings.favoriteIds.value = liked
        AppSettings.automix.value = ketmix
    }

    private fun buttons() = CarControls.buttons(CarControls.look(state.value, AppSettings.favoriteIds.value, AppSettings.automix.value)) { it }

    @Test fun theButtonsShowTheAppsState() {
        AppSettings.favoriteIds.value = setOf("car:b")
        AppSettings.automix.value = true
        var shown = buttons()
        assertEquals(CarControls.ACTIONS, shown.map { it.sessionCommand!!.customAction })
        assertEquals(CommandButton.ICON_HEART_FILLED, shown[0].icon)
        assertEquals("Retirer des favoris", shown[0].displayName.toString())
        assertEquals(CommandButton.ICON_SHUFFLE_OFF, shown[1].icon)
        assertEquals(CommandButton.ICON_REPEAT_OFF, shown[2].icon)
        assertEquals(CommandButton.ICON_RADIO, shown[3].icon)
        assertEquals(R.drawable.ic_widget_ketmix, shown[4].iconResId)
        assertTrue(shown.all { it.isEnabled && it.slots.contains(CommandButton.SLOT_OVERFLOW) })

        state.value = state.value.copy(shuffle = true, repeatOne = true)
        AppSettings.automix.value = false
        AppSettings.favoriteIds.value = emptySet()
        shown = buttons()
        assertEquals(CommandButton.ICON_HEART_UNFILLED, shown[0].icon)
        assertEquals(CommandButton.ICON_SHUFFLE_ON, shown[1].icon)
        assertEquals(CommandButton.ICON_REPEAT_ONE, shown[2].icon)
        assertEquals(R.drawable.ic_car_ketmix_off, shown[4].iconResId)
        state.value = state.value.copy(repeatOne = false, repeatAll = true)
        assertEquals(CommandButton.ICON_REPEAT_ALL, buttons()[2].icon)

        // Nothing playing: like and radio wait for a title.
        state.value = PlayerUiState()
        assertFalse(buttons()[0].isEnabled)
        assertFalse(buttons()[3].isEnabled)
        assertTrue(buttons()[1].isEnabled)
    }

    @Test fun eachButtonGoesThroughThePhonesQueue() {
        AppSettings.favoriteIds.value = emptySet()
        assertNull(CarControls.perform(app, CarControls.LIKE) {})
        assertTrue("car:b" in AppSettings.favoriteIds.value)
        assertNull(CarControls.perform(app, CarControls.LIKE) {})
        assertFalse("car:b" in AppSettings.favoriteIds.value)

        assertNull(CarControls.perform(app, CarControls.SHUFFLE) {})
        assertTrue(state.value.shuffle)
        assertEquals("car:b", state.value.current?.id)
        assertNull(CarControls.perform(app, CarControls.SHUFFLE) {})
        assertFalse(state.value.shuffle)

        CarControls.perform(app, CarControls.REPEAT) {}
        assertTrue(state.value.repeatAll)
        CarControls.perform(app, CarControls.REPEAT) {}
        assertTrue(state.value.repeatOne)
        CarControls.perform(app, CarControls.REPEAT) {}
        assertFalse(state.value.repeatOne || state.value.repeatAll)

        // (Ketmix's button is the quick tile's own switch, KetmixSwitch, which loads and saves the settings itself.)
        state.value = PlayerUiState()
        assertEquals(com.ketvoice.app.data.AppText.t("Aucun titre en lecture"), CarControls.perform(app, CarControls.RADIO) {})
        assertEquals(com.ketvoice.app.data.AppText.t("Aucun titre en lecture"), CarControls.perform(app, CarControls.LIKE) {})
    }

    @Test fun aRepeatModeFromOutsideIsTheAppsRepeat() {
        Player.setRepeatMode(MediaPlayer.REPEAT_MODE_ALL)
        assertTrue(state.value.repeatAll && !state.value.repeatOne)
        Player.setRepeatMode(MediaPlayer.REPEAT_MODE_ONE)
        assertTrue(state.value.repeatOne && !state.value.repeatAll)
        Player.setRepeatMode(MediaPlayer.REPEAT_MODE_OFF)
        assertFalse(state.value.repeatOne || state.value.repeatAll)
        assertEquals(MediaPlayer.REPEAT_MODE_OFF, QueueBridgePlayer.repeatModeOf(state.value))
    }
}
