package com.ketvoice.app.player

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source regression guard, not an audio/device playback test. */
class RetiredMixingTest {
    @Test fun retiredEngineAndAnalysisAreNotShipped() {
        listOf("AutoMix.kt", "AutoMixEngine.kt", "AutoMixAnalyzer.kt", "AutoMixDsp.kt").forEach {
            assertFalse(File("src/main/kotlin/com/ketvoice/app/player/$it").exists())
        }
    }

    @Test fun runtimeAndDictionaryHaveNoMixingFeatureOrCommands() {
        listOf("player/Player.kt", "player/PlaybackService.kt", "ui/AppRoot.kt", "ui/settings/SettingsScreen.kt").forEach {
            val source = File("src/main/kotlin/com/ketvoice/app/$it").readText()
            assertFalse(it, source.contains("automix", ignoreCase = true))
        }
        assertFalse(File("src/main/assets/translations.json").readText().contains("automix", ignoreCase = true))
    }
}
