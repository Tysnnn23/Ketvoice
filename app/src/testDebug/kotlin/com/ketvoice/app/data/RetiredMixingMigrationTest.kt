package com.ketvoice.app.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetiredMixingMigrationTest {
    @Test fun oldMixingSettingsAndOnlyTheirAnalysisCacheAreRemovedOnUpgrade() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("automix", true).putBoolean("automixAutoFade", false)
            .putInt("automixFadeSeconds", 30).putInt("automixPerformance", 3)
            .putBoolean("skipSilence", true).commit()
        val analysis = File(context.filesDir, "automix").apply { mkdirs() }
        File(analysis, "old-analysis.json").writeText("{}")
        val music = File(context.filesDir, "qa-music-preserved.mp3").apply { writeText("keep") }
        SettingsStore.load(context)
        SettingsStore.save()
        listOf("automix", "automixAutoFade", "automixFadeSeconds", "automixPerformance").forEach {
            assertFalse(it, prefs.contains(it))
        }
        assertFalse(analysis.exists())
        assertEquals("keep", music.readText())
        assertTrue(prefs.getBoolean("skipSilence", false))
        music.delete()
    }
}
