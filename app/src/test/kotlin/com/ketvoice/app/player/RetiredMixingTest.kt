package com.ketvoice.app.player

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Source regression guard, not an audio/device playback test. The AutoMix of 3.0.6 was
 * removed in 3.10.0 and stays removed. The Automix of 5.0 is a new feature the owner
 * asked for on 2026-11-14: another engine, in its own package, off until switched on.
 */
class RetiredMixingTest {
    @Test fun retiredEngineAndAnalysisAreNotShipped() {
        listOf("AutoMix.kt", "AutoMixEngine.kt", "AutoMixAnalyzer.kt", "AutoMixDsp.kt").forEach {
            assertFalse(File("src/main/kotlin/com/ketvoice/app/player/$it").exists())
        }
    }

    @Test fun theNewAutomixIsOptInAndTheRetiredSettingsStayRetired() {
        val settings = File("src/main/kotlin/com/ketvoice/app/data/AppSettings.kt").readText()
        assertTrue("Off until the listener switches it on", settings.contains("val automix = mutableStateOf(false)"))
        val store = File("src/main/kotlin/com/ketvoice/app/data/SettingsStore.kt").readText()
        // The old switch and its options are still removed on upgrade, and never read.
        listOf("automix", "automixAutoFade", "automixFadeSeconds", "automixPerformance").forEach {
            assertTrue(it, store.contains(".remove(\"$it\")"))
            assertFalse(it, Regex("get(Boolean|Int)\\(\"$it\"").containsMatchIn(store))
        }
        assertTrue(store.contains("getBoolean(\"automixOn\""))
        assertTrue(File("src/main/kotlin/com/ketvoice/app/automix/Automix.kt").exists())
        // The old transition commands are not back: decks are driven in-process, not through session commands.
        val service = File("src/main/kotlin/com/ketvoice/app/player/PlaybackService.kt").readText()
        assertFalse(service.contains("CMD_AUTOMIX")); assertFalse(service.contains("ketvoice.automix"))
    }
}
