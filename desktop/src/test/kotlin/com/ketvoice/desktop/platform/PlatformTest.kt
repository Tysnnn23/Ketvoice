package com.ketvoice.desktop.platform

import com.ketvoice.app.data.Track
import com.ketvoice.app.player.PlayerUiState
import com.sun.jna.Pointer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformTest {
    private fun tempDir(): File = Files.createTempDirectory("kv-platform").toFile().also { it.deleteOnExit() }

    private class Recorder : PlatformBridge {
        val calls = ArrayList<String>()
        override fun toggle() { calls += "toggle" }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun seekTo(ms: Long) { calls += "seek $ms" }
    }

    @Test fun mediaKeysMapToCommands() {
        assertEquals(MediaCommand.Toggle, MediaCommands.fromVirtualKey(0xB3))
        assertEquals(MediaCommand.Next, MediaCommands.fromVirtualKey(0xB0))
        assertEquals(MediaCommand.Previous, MediaCommands.fromVirtualKey(0xB1))
        assertEquals(MediaCommand.Stop, MediaCommands.fromVirtualKey(0xB2))
        assertNull(MediaCommands.fromVirtualKey(0x41))
        // Hotkey ids follow the registration order.
        val ids = MediaCommands.hotkeys.indices.map { MediaCommands.fromHotkeyId(MediaCommands.HOTKEY_BASE + it) }
        assertEquals(listOf(MediaCommand.Toggle, MediaCommand.Next, MediaCommand.Previous, MediaCommand.Stop), ids)
        assertNull(MediaCommands.fromHotkeyId(MediaCommands.HOTKEY_BASE + 4))
        assertNull(MediaCommands.fromHotkeyId(1))
    }

    @Test fun nativeCodesMapAndDispatch() {
        val r = Recorder()
        for (code in 1..6) MediaCommands.dispatch(MediaCommands.fromNative(code, 42_000)!!, r)
        assertEquals(listOf("play", "pause", "next", "previous", "seek 42000", "pause"), r.calls)
        assertNull(MediaCommands.fromNative(0, 0))
        assertEquals(MediaCommand.Seek(0), MediaCommands.fromNative(5, -5))
        MediaCommands.dispatch(MediaCommand.Toggle, r)
        assertEquals("toggle", r.calls.last())
    }

    private fun state(playing: Boolean = true, pos: Long = 0, cover: String? = "https://x/c.jpg") = PlayerUiState(
        queue = listOf(Track("1", "Titre", "Artiste", "Album", durationMs = 200_000, coverUrl = cover)),
        index = 0, isPlaying = playing, positionMs = pos, durationMs = 0,
    )

    @Test fun nowPlayingFromPlayerState() {
        assertNull(NowPlaying.of(PlayerUiState()))
        val now = NowPlaying.of(state(pos = 1500))!!
        assertEquals(NowPlaying("Titre", "Artiste", "Album", "https://x/c.jpg", true, 1500, 200_000), now)
        val file = File(tempDir(), "cover.jpg")
        assertEquals(file.path, NowPlaying.of(state(cover = file.toURI().toString()))!!.art)
        // Position ticks under a second are not worth a native call; a seek or a pause is.
        assertFalse(now.copy(positionMs = 2200).differsFrom(now))
        assertTrue(now.copy(positionMs = 2500).differsFrom(now))
        assertTrue(now.copy(playing = false).differsFrom(now))
        assertTrue(now.differsFrom(null))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun bindPushesChangesAndClears() = runTest {
        val seen = ArrayList<NowPlaying?>()
        val controls = object : MediaControls {
            override val name = "fake"
            override fun start(hwnd: Pointer?, onCommand: (MediaCommand) -> Unit) = true
            override fun update(nowPlaying: NowPlaying?) { seen += nowPlaying }
            override fun stop() {}
        }
        val flow = MutableStateFlow(PlayerUiState())
        MediaControls.bind(backgroundScope, controls, flow)
        runCurrent()
        assertTrue(seen.isEmpty(), "nothing playing at start: no clear either")
        flow.value = state(pos = 0); runCurrent()
        flow.value = state(pos = 250); runCurrent()
        flow.value = state(pos = 1250); runCurrent()
        flow.value = state(playing = false, pos = 1300); runCurrent()
        flow.value = PlayerUiState(); runCurrent()
        assertEquals(listOf(0L, 1250L, 1300L), seen.filterNotNull().map { it.positionMs })
        assertNull(seen.last())
    }

    @Test fun windowStateRoundTripsAndMerges() {
        val file = File(tempDir(), "window.json")
        assertEquals(SavedWindow(), WindowStore.load(file))
        val w = SavedWindow(100f, 80f, 1300f, 820f, maximized = true)
        WindowStore.save(w, file)
        assertEquals(w, WindowStore.load(file))
        // Floating bounds are taken; maximising or minimising keeps the floating size for later.
        val floating = WindowStore.merge(SavedWindow(), floating = true, maximized = false, x = 10f, y = 20f, width = 900f, height = 600f)
        assertEquals(SavedWindow(10f, 20f, 900f, 600f, false), floating)
        val maxed = WindowStore.merge(floating, floating = false, maximized = true, x = 0f, y = 0f, width = 1920f, height = 1040f)
        assertEquals(floating.copy(maximized = true), maxed)
        WindowStore.save(SavedWindow(null, null, 800f, 600f), file)
        assertEquals(SavedWindow(null, null, 800f, 600f), WindowStore.load(file))
    }

    @Test fun windowFitsTheScreensThereAre() {
        val screens = listOf(ScreenArea(0f, 0f, 1920f, 1040f))
        val onScreen = SavedWindow(200f, 100f, 1200f, 800f)
        assertEquals(onScreen, WindowStore.fit(onScreen, screens))
        // Saved on a second monitor that is gone: centred instead.
        val gone = WindowStore.fit(SavedWindow(2200f, 100f, 1200f, 800f), screens)
        assertNull(gone.x); assertNull(gone.y)
        // Too large for the screen, or too small to use.
        assertEquals(1920f, WindowStore.fit(SavedWindow(0f, 0f, 4000f, 3000f), screens).width)
        assertEquals(1040f, WindowStore.fit(SavedWindow(0f, 0f, 4000f, 3000f), screens).height)
        assertEquals(SavedWindow.MIN_WIDTH, WindowStore.fit(SavedWindow(0f, 0f, 100f, 100f), screens).width)
        // Left monitor at negative coordinates still counts.
        val two = screens + ScreenArea(-1280f, 0f, 1280f, 1000f)
        assertEquals(-1000f, WindowStore.fit(SavedWindow(-1000f, 50f, 900f, 600f), two).x)
    }

    @Test fun instanceMessagesNeedTheToken() {
        val line = InstanceMessage.encode("abc", listOf("C:\\Musique\\a.flac", "--autostart"))
        assertEquals(listOf("C:\\Musique\\a.flac", "--autostart"), InstanceMessage.decode(line, "abc"))
        assertNull(InstanceMessage.decode(line, "other"))
        assertNull(InstanceMessage.decode(line, ""))
        assertNull(InstanceMessage.decode("not json", "abc"))
        assertEquals(emptyList(), InstanceMessage.decode("""{"token":"abc"}""", "abc"))
        assertEquals(listOf("C:\\Musique\\a.flac"), InstanceMessage.openable(listOf("C:\\Musique\\a.flac", "--autostart", " ")))
    }

    @Test fun secondLaunchHandsItsArgumentsToTheFirst() {
        val dir = tempDir()
        val first = SingleInstance.acquire(dir, emptyList())
        assertTrue(first is SingleInstance.Result.Primary)
        val got = java.util.concurrent.LinkedBlockingQueue<List<String>>()
        var allowed = 0
        // Arguments that arrive before the window listens are kept for it.
        assertEquals(SingleInstance.Result.Forwarded, SingleInstance.acquire(dir, listOf("early.mp3")) { allowed++ })
        first.instance.onActivate { got.put(it) }
        assertEquals(listOf("early.mp3"), got.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(SingleInstance.Result.Forwarded, SingleInstance.acquire(dir, listOf("a b.flac", "--x")))
        assertEquals(listOf("a b.flac", "--x"), got.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, allowed)
        first.instance.close()
        val again = SingleInstance.acquire(dir, emptyList())
        assertTrue(again is SingleInstance.Result.Primary)
        again.instance.close()
    }

    @Test fun autostartWritesTheRunValue() {
        val store = object : Autostart.RunKey {
            var value: String? = null
            override fun get() = value
            override fun set(command: String) { value = command }
            override fun delete() { value = null }
        }
        var exe: String? = "C:\\Users\\a\\AppData\\Local\\Ketvoice\\Ketvoice.exe"
        val a = Autostart(store) { exe }
        assertTrue(a.available)
        assertFalse(a.enabled)
        assertTrue(a.setEnabled(true))
        assertEquals("\"C:\\Users\\a\\AppData\\Local\\Ketvoice\\Ketvoice.exe\" --autostart", store.value)
        assertTrue(a.enabled)
        exe = "D:\\Ketvoice\\Ketvoice.exe"
        a.refresh()
        assertEquals("\"D:\\Ketvoice\\Ketvoice.exe\" --autostart", store.value)
        assertTrue(a.setEnabled(false))
        assertNull(store.value)
        a.refresh()
        assertNull(store.value, "refresh never turns it on")
        exe = null
        assertFalse(a.available)
        assertFalse(a.setEnabled(true))
        assertFalse(Autostart(Autostart.RunKey.None) { "x.exe" }.available)
    }

    @Test fun logRollsAndCrashReportsAreKept() {
        val dir = tempDir()
        File(dir, "ketvoice.log").writeText("0")
        File(dir, "ketvoice.1.log").writeText("1")
        AppLog.roll(dir)
        assertEquals("0", File(dir, "ketvoice.1.log").readText())
        assertEquals("1", File(dir, "ketvoice.2.log").readText())
        assertFalse(File(dir, "ketvoice.log").exists())

        val t = IllegalStateException("boom")
        val start = LocalDateTime.of(2026, 11, 21, 10, 0, 0)
        repeat(12) { CrashHandler.write(dir, "AWT-EventQueue-0", t, start.plusSeconds(it.toLong())) }
        val reports = dir.listFiles { f -> f.name.startsWith("crash-") }!!.map { it.name }.sorted()
        assertEquals(10, reports.size)
        assertEquals("crash-20261121-100002.txt", reports.first())
        val text = File(dir, reports.last()).readText()
        assertTrue("thread: AWT-EventQueue-0" in text && "IllegalStateException: boom" in text && "Ketvoice 1.0.0" in text)
    }

    @Test fun aboutShowsEveryLicence() {
        val text = About.text()
        for (c in About.components) assertNotNull(About.licence(c), c.file)
        assertTrue("GNU LESSER GENERAL PUBLIC LICENSE" in text)
        assertTrue("Source code offer" in text)
        assertTrue("Copyright (c) Microsoft Corporation" in text)
        assertTrue("Apache License" in text)
        assertTrue("UNICODE LICENSE V3" in text)
        assertTrue(text.startsWith("Ketvoice"))
    }

    @Test fun noMediaControlsOffWindows() {
        if (Os.isWindows) return
        assertEquals(NoMediaControls, MediaControls.start(null) {})
        val lines = ArrayList<String>()
        assertTrue(PlatformSmoke.check { lines += it })
        assertEquals(listOf("smtc skipped: not Windows"), lines)
    }
}
