package com.ketvoice.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalBackupMergeHostTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val name = "backup-test-${UUID.randomUUID()}"
    private val directory = File(base.filesDir, name).apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("${this@PersonalBackupMergeHostTest.name}-$name", mode)
    }
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private var playlistFile: Any? = null
    private var playlistRows = emptyList<LocalPlaylist>()
    private var playlistError = false
    private var historyPrefs: Any? = null
    private var recent = emptyList<Track>()
    private var known = emptyList<Track>()
    private var counts = emptyMap<String, Int>()
    private var listened = 0L
    private var unflushed = 0L
    private var settingsPrefs: Any? = null
    private var lyricsPrefs: Any? = null
    private var lyricValues = emptyMap<String, Int>()
    private var settingsStates = emptyList<Pair<MutableState<Any?>, Any?>>()
    private var designStates = emptyMap<String, Boolean>()
    private var reverb = Triple(0f, 0f, 0f)

    @Before fun setup() {
        playlistFile = field(PlaylistStore, "file").get(PlaylistStore)
        playlistRows = PlaylistStore.lists.value
        playlistError = PlaylistStore.loadError.value
        historyPrefs = field(ListeningHistory, "prefs").get(ListeningHistory)
        settingsPrefs = field(SettingsStore, "prefs").get(SettingsStore)
        lyricsPrefs = field(LyricsOffsets, "prefs").get(LyricsOffsets)
        lyricValues = LyricsOffsets.values.value
        @Suppress("UNCHECKED_CAST")
        settingsStates = AppSettings::class.java.declaredFields.mapNotNull { f ->
            f.isAccessible = true
            (f.get(AppSettings) as? MutableState<Any?>)?.let { it to it.value }
        }
        designStates = DesignChoices.all.associate { it.code to DesignChoices.enabled(it.code) }
        reverb = Triple(com.ketvoice.app.player.ReverbControl.amount, com.ketvoice.app.player.ReverbControl.size,
            com.ketvoice.app.player.ReverbControl.damping)
        recent = ListeningHistory.tracks.value; known = ListeningHistory.knownTracks.value
        counts = ListeningHistory.playCounts.value; listened = ListeningHistory.listenedMs.value
        unflushed = field(ListeningHistory, "unflushedMs").getLong(ListeningHistory)
        PlaylistStore.resetForTest()
        PlaylistStore.init(context)
        // Robolectric's Android rename cannot replace an existing file on Windows.
        field(PlaylistStore, "file").set(PlaylistStore, HostAtomicFile(File(directory, "playlists-v1.json")))
        field(ListeningHistory, "prefs").set(ListeningHistory, null)
        ListeningHistory.init(context)
    }

    @After fun cleanup() {
        field(PlaylistStore, "file").set(PlaylistStore, playlistFile)
        (PlaylistStore.lists as MutableStateFlow<List<LocalPlaylist>>).value = playlistRows
        (PlaylistStore.loadError as MutableStateFlow<Boolean>).value = playlistError
        field(ListeningHistory, "prefs").set(ListeningHistory, historyPrefs)
        (ListeningHistory.tracks as MutableStateFlow<List<Track>>).value = recent
        (ListeningHistory.knownTracks as MutableStateFlow<List<Track>>).value = known
        (ListeningHistory.playCounts as MutableStateFlow<Map<String, Int>>).value = counts
        (ListeningHistory.listenedMs as MutableStateFlow<Long>).value = listened
        field(ListeningHistory, "unflushedMs").setLong(ListeningHistory, unflushed)
        field(SettingsStore, "prefs").set(SettingsStore, settingsPrefs)
        field(LyricsOffsets, "prefs").set(LyricsOffsets, lyricsPrefs)
        (LyricsOffsets.values as MutableStateFlow<Map<String, Int>>).value = lyricValues
        settingsStates.forEach { (state, value) -> state.value = value }
        designStates.forEach { (key, value) -> DesignChoices.set(key, value) }
        com.ketvoice.app.player.ReverbControl.amount = reverb.first
        com.ketvoice.app.player.ReverbControl.size = reverb.second
        com.ketvoice.app.player.ReverbControl.damping = reverb.third
    }

    @Test fun existingPlaylistIsNotOverwrittenAndNewPlaylistIsAddedOnce() = runBlocking {
        val id = PlaylistStore.create("Existing")
        val original = PlaylistStore.lists.value.single()
        val incoming = listOf(original.copy(name = "Changed elsewhere"), LocalPlaylist(id = "new", name = "New"))
        PlaylistStore.mergeBackup(incoming)
        PlaylistStore.mergeBackup(incoming)
        assertEquals("Existing", PlaylistStore.lists.value.single { it.id == id }.name)
        assertEquals(2, PlaylistStore.lists.value.size)
        PlaylistStore.resetForTest()
        PlaylistStore.init(context)
        assertEquals(setOf("Existing", "New"), PlaylistStore.lists.value.map { it.name }.toSet())
    }

    private class HostAtomicFile(file: File) : android.util.AtomicFile(file) {
        private val pending = File(file.path + ".host-pending")
        override fun startWrite(): java.io.FileOutputStream = java.io.FileOutputStream(pending)
        override fun finishWrite(stream: java.io.FileOutputStream?) {
            stream?.fd?.sync(); stream?.close()
            java.nio.file.Files.move(pending.toPath(), baseFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        override fun failWrite(stream: java.io.FileOutputStream?) { stream?.close(); pending.delete() }
    }

    @Test fun unreadableHistoryIsNotOverwritten() {
        val prefs = context.getSharedPreferences("listening-history", Context.MODE_PRIVATE)
        prefs.edit().putString("counts", "{broken").commit()
        assertThrows(Exception::class.java) {
            ListeningHistory.mergeBackup(listOf(Track("new", "Song", "Artist", "Album")), emptyList(), emptyMap(), 0)
        }
        assertEquals("{broken", prefs.getString("counts", null))
    }

    @Test fun confirmedMergePreservesExistingDataAndIsIdempotent() = runBlocking {
        field(SettingsStore, "prefs").set(SettingsStore, null)
        field(LyricsOffsets, "prefs").set(LyricsOffsets, null)
        SettingsStore.load(context)
        LyricsOffsets.init(context)
        AppSettings.favoriteIds.value = setOf("existing")
        SettingsStore.save()
        val oldId = PlaylistStore.create("Existing")
        val old = PlaylistStore.lists.value.single()
        val song = Track("song", "Song", "Artist", "Album")
        ListeningHistory.record(song)
        LyricsOffsets.set("song", 100)
        val incoming = PersonalBackupData(
            playlists = listOf(old.copy(name = "Do not overwrite"), LocalPlaylist(id = "new", name = "New")),
            favorites = setOf("incoming"), settings = mapOf("themeMode" to 1),
            recent = listOf(Track("other", "Other", "Artist", "Album")), known = emptyList(),
            counts = mapOf("song" to 999, "other" to 2), listenedMs = 10_000,
            lyricOffsets = mapOf(LyricsOffsets.key("song") to 900, LyricsOffsets.key("other") to -200))
        PersonalBackup.merge(context, incoming)
        PersonalBackup.merge(context, incoming)
        assertEquals("Existing", PlaylistStore.lists.value.single { it.id == oldId }.name)
        assertEquals(2, PlaylistStore.lists.value.size)
        assertEquals(setOf("existing", "incoming"), AppSettings.favoriteIds.value)
        assertEquals(1, AppSettings.themeMode.value)
        assertEquals(1, ListeningHistory.playCounts.value["song"])
        assertEquals(2, ListeningHistory.playCounts.value["other"])
        assertEquals(100, LyricsOffsets.values.value[LyricsOffsets.key("song")])
        assertEquals(-200, LyricsOffsets.values.value[LyricsOffsets.key("other")])
    }
}
