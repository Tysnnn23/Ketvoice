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
import com.ketvoice.desktop.ketmix.LiveMix
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The live mix view in the « Ketmix » tab and the full player, and « Ketmix apprend »'s 👍/👎 (view and island toast). */
@OptIn(ExperimentalTestApi::class)
class KetmixLiveTest {
    @BeforeTest fun setUp() {
        Fixtures.installCovers(); AppSettings.reduceAnimations.value = true
        KetmixLive.register()
        KetmixFeedback.resetForTest(); LiveMix.resetForTest(); KetmixPcSettings.learns.value = false
    }
    @AfterTest fun tearDown() { KetmixFeedback.resetForTest(); LiveMix.resetForTest(); KetmixPcSettings.learns.value = false }

    private fun paused() = RecordingPlayer().playing().apply { toggle() }

    @Test fun theKetmixTabShowsTheTransitionPlayingAndItsRatingsOnlyWhenLearning() = runComposeUiTest {
        val shell = ShellState("ketmix")
        setContent { KetvoiceShell(paused(), Fixtures.library(), FixtureCatalog, dark = true, shell = shell) }
        waitForIdle()
        onNodeWithTag("panel:ketmix").assertExists()
        onNodeWithTag("live-mix").assertDoesNotExist()
        KetmixFeedback.planned(LiveMixFixtures.plan, LiveMixFixtures.from, LiveMixFixtures.to)
        LiveMix.showForTest(LiveMixFixtures.frame())
        waitForIdle()
        onNodeWithTag("live-mix").assertExists()
        onNodeWithTag("live-mix-out").assertExists()
        onNodeWithTag("live-mix-in").assertExists()
        onNodeWithTag("panel:ketmix").assertExists()
        onNodeWithTag("live-mix-up").assertDoesNotExist()
        KetmixPcSettings.learns.value = true
        waitForIdle()
        onNodeWithTag("live-mix-up").performClick()
        assertEquals(true, KetmixFeedback.transition.value?.rating)
        // Once the transition is over the tab offers the last one's 👍/👎.
        LiveMix.showForTest(null)
        waitForIdle()
        onNodeWithTag("live-mix").assertDoesNotExist()
        onNodeWithTag("ketmix-last").assertExists()
        onNodeWithTag("ketmix-last-down").performClick()
        assertEquals(false, KetmixFeedback.transition.value?.rating)
    }

    @Test fun theFullPlayerShowsTheLiveMixOnlyWhileATransitionPlays() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalLyricsFeed provides remember { FixedLyricsFeed() }) {
                KetvoiceShell(paused(), Fixtures.library(), FixtureCatalog, dark = true, initialFullPlayer = true)
            }
        }
        waitForIdle()
        onNodeWithTag("full-player").assertExists()
        onNodeWithTag("live-mix").assertDoesNotExist()
        LiveMix.showForTest(LiveMixFixtures.frame())
        waitForIdle()
        onNodeWithTag("live-mix").assertExists()
        LiveMix.showForTest(null)
        waitForIdle()
        onNodeWithTag("live-mix").assertDoesNotExist()
    }

    @Test fun afterEachTransitionTheIslandAsksForAnOpinionOnlyWhenLearning() = runComposeUiTest {
        setContent { KetvoiceShell(paused(), Fixtures.library(), FixtureCatalog, dark = true) }
        waitForIdle()
        fun transition() {
            KetmixFeedback.planned(LiveMixFixtures.plan, LiveMixFixtures.from, LiveMixFixtures.to)
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

    @Test fun theWordsFollowTheTransition() {
        assertEquals("Transition terminée", LiveMixWords.doing(LiveMixFixtures.frame(1f)))
        assertTrue(LiveMixWords.doing(LiveMixFixtures.frame(-.25f)).startsWith("Transition dans "))
        val blend = MixPlan(MixKind.Beatmatch, outMs = 100_000, overlapMs = 16_000, swapMs = 108_000)
        assertEquals("Les deux titres jouent en rythme", LiveMixWords.sentence(blend, .3f))
        assertEquals("Les basses passent au titre suivant", LiveMixWords.sentence(blend, .6f))
        assertEquals("Le titre s'éteint dans son écho", LiveMixWords.sentence(MixPlan(MixKind.Echo, overlapMs = 4_000), .5f))
        assertEquals("Les filtres se croisent", LiveMixWords.sentence(MixPlan(MixKind.Filter, overlapMs = 8_000), .5f))
        assertEquals("Calé sur le tempo", LiveMixWords.title("calé sur le tempo"))
        // The live frame carries what the engine plays: a falling curve for the outgoing deck, a rising one for the next.
        val end = LiveMixFixtures.frame(1f).outgoing.gain
        assertEquals(LiveMix.CURVE_POINTS, end.size)
        assertTrue(end.first() > end.last() + .05f && end.last() < .01f, end.joinToString())
        val start = LiveMixFixtures.frame(0f).incoming.gain
        assertTrue(start.first() < .01f && start.last() > start.first() + .05f, start.joinToString())
        assertNull(KetmixLive.ended(null, 1L))
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
