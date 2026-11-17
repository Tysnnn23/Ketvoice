package com.ketvoice.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * How a new install is told apart from an update (6.2), with the real preferences under Robolectric: the decision
 * is taken by [SettingsStore.load] before anything is written, kept, and never shown to a listener who updates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirstRunHostTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before fun clean() {
        listOf("ketvoice_settings", "ketvoice-editions", "listening-history").forEach { prefs(it).edit().clear().commit() }
        File(context.filesDir, "downloads").deleteRecursively()
        FirstRun.pending.value = false
    }

    @After fun reset() { FirstRun.pending.value = false }

    @Test fun aNewInstallSeesTheWelcomeUntilItIsFinished() {
        SettingsStore.load(context)
        assertTrue(FirstRun.pending.value)
        // The app saves its settings (onStop) before the welcome is finished: it still comes back.
        SettingsStore.save()
        SettingsStore.load(context)
        assertTrue("left halfway, it comes back", FirstRun.pending.value)
        FirstRun.finish(context)
        assertFalse(FirstRun.pending.value)
        SettingsStore.load(context)
        assertFalse("never again", FirstRun.pending.value)
    }

    @Test fun anUpdateNeverSeesIt() {
        // An earlier version (before 6.2) left its settings and no decision.
        prefs("ketvoice_settings").edit().putInt("themeMode", 2).putBoolean("automixOn", true).commit()
        SettingsStore.load(context)
        assertFalse(FirstRun.pending.value)
        assertEquals(FirstRun.DONE, prefs("ketvoice-editions").getString("onboarding", null))
    }

    @Test fun aListeningHistoryOrDownloadsAloneAlsoMeanAnEarlierInstall() {
        prefs("listening-history").edit().putString("tracks", "[]").commit()
        SettingsStore.load(context)
        assertFalse(FirstRun.pending.value)
        clean()
        File(context.filesDir, "downloads").apply { mkdirs() }.resolve("a.json").writeText("{}")
        SettingsStore.load(context)
        assertFalse(FirstRun.pending.value)
    }

    @Test fun thePlaybackServiceReadingTheSettingsFirstDoesNotSpoilIt() {
        // Android Auto may start the service before the app is ever opened: it loads and saves the settings.
        SettingsStore.load(context)
        SettingsStore.save()
        FirstRun.pending.value = false
        SettingsStore.load(context)
        assertTrue("the decision taken first is kept", FirstRun.pending.value)
    }
}
