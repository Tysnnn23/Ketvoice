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
class LosslessDefaultHostTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences("lossless-test-$name", mode)
    }
    private val sourcePrefs get() = context.getSharedPreferences("ketvoice_custom_sources", Context.MODE_PRIVATE)
    private val priorityPrefs get() = context.getSharedPreferences("ketvoice_source_priority", Context.MODE_PRIVATE)
    private val customField = CustomSources::class.java.getDeclaredField("prefs").apply { isAccessible = true }
    private val priorityField = SourcePriority::class.java.getDeclaredField("prefs").apply { isAccessible = true }
    private val sources get() = CustomSources.sources as MutableStateFlow<List<CustomSource>>
    private var oldCustom: Any? = null
    private var oldPriority: Any? = null
    private lateinit var oldSources: List<CustomSource>
    private lateinit var oldPlayback: List<String>
    private lateinit var oldDownload: List<String>
    private lateinit var oldPlaybackDisabled: Set<String>
    private lateinit var oldDownloadDisabled: Set<String>

    @Before fun setup() {
        oldCustom = customField.get(CustomSources); oldPriority = priorityField.get(SourcePriority)
        oldSources = sources.value; oldPlayback = SourcePriority.playbackOrder.value; oldDownload = SourcePriority.downloadOrder.value
        oldPlaybackDisabled = SourcePriority.playbackDisabled.value; oldDownloadDisabled = SourcePriority.downloadDisabled.value
        sourcePrefs.edit().clear().commit(); priorityPrefs.edit().clear().commit()
        customField.set(CustomSources, null); priorityField.set(SourcePriority, null)
    }

    @After fun cleanup() {
        sourcePrefs.edit().clear().commit(); priorityPrefs.edit().clear().commit()
        customField.set(CustomSources, oldCustom); priorityField.set(SourcePriority, oldPriority)
        sources.value = oldSources; SourcePriority.playbackOrder.value = oldPlayback; SourcePriority.downloadOrder.value = oldDownload
        SourcePriority.playbackDisabled.value = oldPlaybackDisabled; SourcePriority.downloadDisabled.value = oldDownloadDisabled
    }

    @Test fun sourceIsAddedOnceAndCanBeRemovedWithoutReturningAfterRestart() {
        CustomSources.init(context)
        val source = CustomSources.sources.value.single()
        assertEquals(CustomSources.LOSSLESS_MANIFEST, source.manifestUrl)
        assertEquals("cs:${source.id}", SourcePriority.entries().first().id)
        CustomSources.remove(source.id)
        customField.set(CustomSources, null)
        CustomSources.init(context)
        assertTrue(CustomSources.sources.value.isEmpty())
    }

    @Test fun existingDisabledSourceAndDownloadOrderArePreserved() {
        sourcePrefs.edit().putString("items", """[{"id":"existing","manifestUrl":"${CustomSources.LOSSLESS_MANIFEST}","name":"Mine","baseUrl":"https://ketvoice-lossless.onrender.com","enabled":false,"quality":"low","manifestJson":"{}"}]""").commit()
        priorityPrefs.edit().putString("download_order", """["youtube","api:um"]""").commit()
        CustomSources.init(context)
        val source = CustomSources.sources.value.single()
        assertEquals("existing", source.id)
        assertEquals("low", source.quality)
        assertFalse(source.enabled)
        assertFalse(SourcePriority.entries().first().enabled)
        assertEquals(listOf("youtube", "api:um"), SourcePriority.downloadOrder.value)
    }

    @Test fun malformedStoredSourcesAreNotOverwritten() {
        sourcePrefs.edit().putString("items", "{broken").commit()
        CustomSources.init(context)
        assertEquals("{broken", sourcePrefs.getString("items", null))
        assertFalse(sourcePrefs.contains("lossless_added_v1"))
    }
}
