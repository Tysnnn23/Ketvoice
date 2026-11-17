package com.ketvoice.app.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrefsWarmupHostTest {
    @Test fun everyListedFileLoadsAndNoneIsCreated() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        val before = dir.list().orEmpty().toSet()
        assertEquals(PrefsWarmup.NAMES.size, PrefsWarmup.load(context, PrefsWarmup.NAMES))
        assertEquals(before, dir.list().orEmpty().toSet())
    }

    @Test fun aFileThatWasWrittenIsReadBackThroughTheWarmedInstance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE).edit().putBoolean("warm", true).commit()
        PrefsWarmup.load(context, listOf("ketvoice_settings"))
        assertTrue(context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE).getBoolean("warm", false))
    }

    @Test fun theListHasNoDuplicates() = assertEquals(PrefsWarmup.NAMES.size, PrefsWarmup.NAMES.toSet().size)
}
