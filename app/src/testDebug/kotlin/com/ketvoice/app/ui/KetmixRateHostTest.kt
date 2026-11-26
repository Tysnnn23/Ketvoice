package com.ketvoice.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.ketvoice.app.automix.KetmixLearning
import com.ketvoice.app.automix.MixKind
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.KetmixAsk
import com.ketvoice.app.ui.player.KetmixLastTransition
import com.ketvoice.app.ui.player.KetmixRateLine
import com.ketvoice.app.ui.player.rememberKetmixAsk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** « Ketmix apprend »'s 👍/👎 after a transition, on the player line and in Settings (host, software Skia). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KetmixRateHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val mixing = mutableStateOf(false)
    private val echo = MixPlan(MixKind.Echo, overlapMs = 4_000, reason = "fixture")

    @Before fun setUp() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = true
        AppSettings.automix.value = true
        AppSettings.ketmixLearns.value = false
        AppSettings.ketmixTaste.value = ""
        KetmixLearning.persist = {}
        KetmixLearning.resetForTest()
    }

    @After fun tearDown() {
        AppSettings.automix.value = false; AppSettings.ketmixLearns.value = false; AppSettings.ketmixTaste.value = ""
        AppSettings.reduceAnimations.value = false
        KetmixLearning.resetForTest()
    }

    private fun showPlayerLine() = compose.setContent { KetvoiceTheme(useDark = true) {
        Box(Modifier.fillMaxSize().background(Color(0xFF241A2E)).padding(16.dp)) {
            Box(Modifier.fillMaxWidth().height(34.dp)) {
                rememberKetmixAsk(mixing.value).value?.let { KetmixRateLine(it, Modifier.fillMaxWidth()) }
            }
        }
    } }

    private fun transition() {
        // The mix must be seen playing, then ending.
        compose.waitForIdle()
        compose.runOnUiThread { KetmixLearning.played(echo, "Titre a — Artiste", "Titre b — Artiste"); mixing.value = true }
        compose.waitForIdle()
        compose.runOnUiThread { mixing.value = false }
        compose.waitForIdle()
    }

    @Test fun offNothingIsAsked() {
        showPlayerLine()
        transition()
        compose.onNodeWithTag("ketmix-rate").assertDoesNotExist()
    }

    @Test fun onTheLineAsksAfterATransitionAndThanks() {
        AppSettings.ketmixLearns.value = true
        showPlayerLine()
        transition()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("ketmix-rate").assertIsDisplayed()
        compose.onNodeWithText("Cette transition vous a plu ?").assertIsDisplayed()
        compose.onNodeWithContentDescription("Elle ne m'a pas plu").assertIsDisplayed()
        compose.onNodeWithTag("ketmix-rate-down").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals(false, KetmixLearning.last.value?.rating)
        compose.onNodeWithText("Merci, Ketmix s'en souviendra").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(KetmixLearning.THANKS_MS + 200)
        compose.onNodeWithTag("ketmix-rate").assertDoesNotExist()
    }

    @Test fun unansweredTheQuestionLeavesAfterNineSeconds() {
        AppSettings.ketmixLearns.value = true
        showPlayerLine()
        transition()
        compose.mainClock.advanceTimeBy(KetmixLearning.ASK_MS - 1_000)
        compose.onNodeWithTag("ketmix-rate").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag("ketmix-rate").assertDoesNotExist()
        assertNull(KetmixLearning.last.value?.rating)
    }

    @Test fun theLastTransitionIsRateableFromSettings() {
        AppSettings.ketmixLearns.value = true
        compose.setContent { KetvoiceTheme(useDark = false) { Column { KetmixLastTransition(Modifier.fillMaxWidth().padding(16.dp)) } } }
        compose.onNodeWithText("Aucune transition pour l'instant").assertIsDisplayed()
        compose.runOnIdle { KetmixLearning.played(echo, "Titre a — Artiste", "Titre b — Artiste") }
        compose.onNodeWithTag("ketmix-last-up").performClick()
        compose.waitForIdle()
        assertEquals(true, KetmixLearning.last.value?.rating)
        assertEquals(1, com.ketvoice.app.automix.KetmixTaste.decode(AppSettings.ketmixTaste.value)[MixKind.Echo]?.votes)
    }

    @Test fun eachTransitionIsAskedOnce() {
        val t = KetmixLearning.Transition(4, "a", "b", MixKind.Echo, "sortie en écho")
        assertEquals(4L, KetmixAsk.ended(4, t, 3))
        assertNull("asked already", KetmixAsk.ended(4, t, 4))
        assertNull("rated already", KetmixAsk.ended(4, t.copy(rating = true), 0))
        assertNull("no mix was heard", KetmixAsk.ended(null, t, 0))
    }
}
