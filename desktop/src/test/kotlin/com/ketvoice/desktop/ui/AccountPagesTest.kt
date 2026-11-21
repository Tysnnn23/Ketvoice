package com.ketvoice.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.AccountState
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.DayStat
import com.ketvoice.app.data.ListeningWeek
import com.ketvoice.app.data.LiveStats
import com.ketvoice.app.data.MonthStat
import com.ketvoice.app.data.ListeningMonths
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Playlist delete with its confirmation, the statistics page, and renders of the account, stats and dialog screens. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class AccountPagesTest {
    @BeforeTest fun setUp() { Fixtures.installCovers(); AppSettings.reduceAnimations.value = true }
    @AfterTest fun tearDown() = AccountSession.showForTest(AccountState())

    @Test fun deletingAPlaylistAsksFirst() = runComposeUiTest {
        val library = Fixtures.library()
        setContent { KetvoiceShell(RecordingPlayer(), library, FixtureCatalog, dark = true, navigator = remember { Navigator(Route.Playlist("pl-road")) }) }
        onNodeWithTag("delete-playlist").performClick()
        onNodeWithTag("confirm-dialog").assertIsDisplayed()
        onNodeWithTag("confirm-cancel").performClick()
        waitForIdle()
        assertTrue(library.playlists.value.any { it.id == "pl-road" }, "cancel keeps it")
        onNodeWithTag("delete-playlist").performClick()
        onNodeWithTag("confirm-ok").performClick()
        waitForIdle()
        assertTrue(library.playlists.value.none { it.id == "pl-road" })
        onNodeWithTag("page:home").assertExists()
    }

    @Test fun statisticsShowTheHistory() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalStatsInput provides ::fixtureStats) {
                KetvoiceShell(RecordingPlayer(), Fixtures.library(), FixtureCatalog, dark = false, navigator = remember { Navigator(Route.Stats) })
            }
        }
        waitUntil(timeoutMillis = 3_000) { runCatching { onNodeWithTag("stats-hero").assertExists() }.isSuccess }
        onNodeWithTag("stats-track:0").assertExists()
        onNodeWithTag("period:All").performClick()
        onNodeWithTag("stats-artist:0").assertExists()
    }

    private val out = File("build/desktop-ui-preview").apply { mkdirs() }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        AppSettings.reduceAnimations.value = false
        val scene = ImageComposeScene(1440, 900, Density(1f), content = content)
        try {
            var t = 0L
            repeat(40) { scene.render(t); t += 50_000_000L }
            File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }

    private fun both(name: String, route: Route, overlay: @Composable () -> Unit = {}) {
        for (dark in listOf(true, false)) shoot("$name-${if (dark) "dark" else "light"}") {
            CompositionLocalProvider(LocalStatsInput provides ::fixtureStats) {
                Box {
                    KetvoiceShell(RecordingPlayer().playing(), Fixtures.library(), FixtureCatalog, dark = dark, navigator = remember { Navigator(route) })
                    // The shell's colours, as a dialog opened from a page gets them.
                    CompositionLocalProvider(com.ketvoice.desktop.ui.theme.LocalKet provides com.ketvoice.desktop.ui.theme.KetColors.of(dark)) { overlay() }
                }
            }
        }
    }

    @Test fun renders() {
        both("stats", Route.Stats)
        both("settings-signed-out", Route.Settings)
        AccountSession.showForTest(AccountState("00000000-0000-0000-0000-00000000000a", "lea@example.com"))
        both("settings-signed-in", Route.Settings)
        AccountSession.showForTest(AccountState())
        both("playlist-delete", Route.Playlist("pl-road")) {
            ConfirmDialog(AppText.t("Supprimer la playlist"), AppText.t("« {0} » sera supprimée de votre bibliothèque.").replace("{0}", "Route du soir"),
                "Supprimer", onConfirm = {}, onDismiss = {})
        }
        assertTrue(File(out, "stats-dark.png").length() > 10_000)
    }

    companion object {
        /** Two weeks of listening over the fixture titles. */
        internal fun fixtureStats(): LiveStats.Input {
            val today = LocalDate.now()
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val tracks = Fixtures.tracks.take(12)
            val days = (0..13).associate { back ->
                val day = today.minusDays(back.toLong())
                val plays = tracks.withIndex().filter { (i, _) -> (i + back) % 3 != 0 }.associate { (i, t) -> t.id to (12 - i) / 2 + 1 }
                ListeningWeek.key(day) to DayStat(ms = (40 + back * 7 % 50) * 60_000L, plays = plays,
                    hours = List(24) { h -> if (h in 8..23) ((h * 37 + back * 11) % 9) * 60_000L else 0L })
            }.filterKeys { !LocalDate.parse(it).isBefore(monday.minusWeeks(1)) }
            val months = mapOf(ListeningMonths.key(today) to MonthStat(ms = days.values.sumOf { it.ms },
                plays = tracks.associate { it.id to 20 }, tracks = tracks.associateBy { it.id }))
            return LiveStats.Input(today, days, months, emptyMap(), days.values.sumOf { it.ms } * 6,
                tracks.withIndex().associate { (i, t) -> t.id to 60 - i * 4 }, tracks)
        }
    }
}
