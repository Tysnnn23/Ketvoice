package com.ketvoice.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LyricsOffsetsHostTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences("lyrics-test-$name", mode)
    }
    private val prefsField = LyricsOffsets::class.java.getDeclaredField("prefs").apply { isAccessible = true }
    private val values get() = LyricsOffsets.values as MutableStateFlow<Map<String, Int>>
    private var previousPrefs: Any? = null
    private var previousValues = emptyMap<String, Int>()

    @Before fun setup() {
        previousPrefs = prefsField.get(LyricsOffsets)
        previousValues = values.value
        context.getSharedPreferences("lyrics-offsets-v1", Context.MODE_PRIVATE).edit().clear().commit()
        prefsField.set(LyricsOffsets, null)
        LyricsOffsets.init(context)
    }

    @After fun cleanup() {
        context.getSharedPreferences("lyrics-offsets-v1", Context.MODE_PRIVATE).edit().clear().commit()
        prefsField.set(LyricsOffsets, previousPrefs)
        values.value = previousValues
    }

    @Test fun perTrackAdjustmentPersistsAndCanBeReset() {
        LyricsOffsets.set("first", 350)
        LyricsOffsets.set("second", -20_000)
        prefsField.set(LyricsOffsets, null)
        values.value = emptyMap()
        LyricsOffsets.init(context)
        assertEquals(350, values.value[LyricsOffsets.key("first")])
        assertEquals(-10_000, values.value[LyricsOffsets.key("second")])
        LyricsOffsets.set("first", 0)
        assertFalse(values.value.containsKey(LyricsOffsets.key("first")))
        assertEquals(-10_000, values.value[LyricsOffsets.key("second")])
    }
}
