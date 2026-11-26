package com.ketvoice.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.ketvoice.app.automix.MixKind
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.data.AppSettings
import com.ketvoice.desktop.ketmix.KetmixFeedback
import com.ketvoice.desktop.ketmix.KetmixPcSettings
import com.ketvoice.app.data.Track
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** « Ketmix apprend »'s 👍/👎 in the « Ketmix » tab and the island's toast; the live mix view of Windows 2.0 is gone. */
@OptIn(ExperimentalTestApi::class)
class KetmixRatingTest {
    private val from = Track("live-a", "Nuit claire", "Nora Vale", "", durationMs = 200_000L)
    private val to = Track("live-b", "Sous la jetée", "Nora Vale", "", durationMs = 200_000L)
    private val plan = MixPlan(MixKind.Beatmatch, outMs = 160_000, inMs = 500, overlapMs = 16_000, swapMs = 168_000, reason = "32 beats")

    @BeforeTest fun setUp() {
        Fixtures.installCovers(); AppSettings.reduceAnimations.value = true
        KetmixRating.register()
        KetmixFeedback.resetForTest(); KetmixPcSettings.learns.value = false
    }
    @AfterTest fun tearDown() { KetmixFeedback.resetForTest(); KetmixPcSettings.learns.value = false }

    private fun paused() = RecordingPlayer().playing().apply { toggle() }

    @Test fun theKetmixTabOffersTheLastTransitionsRatingOnlyWhenLearning() = runComposeUiTest {
        val shell = ShellState("ketmix")
        setContent { KetvoiceShell(paused(), Fixtures.library(), FixtureCatalog, dark = true, shell = shell) }
        waitForIdle()
        onNodeWithTag("panel:ketmix").assertExists()
        onNodeWithTag("live-mix").assertDoesNotExist()
        KetmixFeedback.planned(plan, from, to)
        KetmixFeedback.playing(true); waitForIdle()
        KetmixFeedback.playing(false); waitForIdle()
        onNodeWithTag("ketmix-last").assertDoesNotExist()
        KetmixPcSettings.learns.value = true
        waitForIdle()
        onNodeWithTag("live-mix").assertDoesNotExist()
        onNodeWithTag("ketmix-last").assertExists()
        onNodeWithTag("ketmix-last-down").performClick()
        assertEquals(false, KetmixFeedback.transition.value?.rating)
        onNodeWithTag("panel:ketmix").assertExists()
    }

    @Test fun theFullPlayerHasNoLiveMixView() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalLyricsFeed provides remember { FixedLyricsFeed() }) {
                KetvoiceShell(paused(), Fixtures.library(), FixtureCatalog, dark = true, initialFullPlayer = true)
            }
        }
        KetmixFeedback.planned(plan, from, to)
        KetmixFeedback.playing(true)
        waitForIdle()
        onNodeWithTag("full-player").assertExists()
        onNodeWithTag("live-mix").assertDoesNotExist()
        assertTrue(FullPlayerSlots.all.none { it.id == "ketmix-live" })
    }

    @Test fun afterEachTransitionTheIslandAsksForAnOpinionOnlyWhenLearning() = runComposeUiTest {
        setContent { KetvoiceShell(paused(), Fixtures.library(), FixtureCatalog, dark = true) }
        waitForIdle()
        fun transition() {
            KetmixFeedback.planned(plan, from, to)
            KetmixFeedback.playing(true); waitForIdle()
            KetmixFeedback.playing(false); waitForIdle()
        }
        transition()
        onNodeWithTag("ketmix-rate-toast").assertDoesNotExist()
        KetmixPcSettings.learns.value = true
        transition()
        onNodeWithTag("ketmix-rate-toast").assertExists()
        onNodeWithTag("ketmix-rate-down").performClick()
        assertEquals(false, KetmixFeedback.transition.value?.rating)
        assertEquals(setOf<MixKind>(), KetmixFeedback.avoid(), "one vote is not enough to avoid a kind")
    }

    @Test fun theLabelIsWrittenAsATitle() {
        assertEquals("Calé sur le tempo", KetmixRating.title("calé sur le tempo"))
        assertNull(KetmixRating.ended(null, 1L))
    }

    /** Polish: symbol and capsule buttons are reached with Tab and pressed with Enter (they are drawn with pointer gestures). */
    @Test fun buttonsTakeTheKeyboard() = runComposeUiTest {
        var symbol = 0; var capsule = 0
        setContent {
            com.ketvoice.desktop.ui.theme.KetDesktopTheme(true) {
                androidx.compose.foundation.layout.Row {
                    KetIconButton(com.ketvoice.app.ui.common.KetIcons.Close, "Fermer", { symbol++ }, Modifier.testTag("symbol"))
                    CapsuleButton("Lecture", null, { capsule++ }, filled = true, accent = androidx.compose.ui.graphics.Color.Red, modifier = Modifier.testTag("capsule"))
                }
            }
        }
        onNodeWithTag("symbol").requestFocus()
        onNodeWithTag("symbol").assertIsFocused()
        onNodeWithTag("symbol").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, symbol)
        onNodeWithTag("capsule").requestFocus()
        onNodeWithTag("capsule").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, capsule)
    }

    @Test fun theOpenTabKeepsRoomForItsName() {
        assertEquals(2.6f, TabStrip.openWeight("Paroles"))
        assertTrue(TabStrip.openWeight("Activité des amis") > 3.4f)
        assertEquals(4f, TabStrip.openWeight("x".repeat(60)))
    }
}
