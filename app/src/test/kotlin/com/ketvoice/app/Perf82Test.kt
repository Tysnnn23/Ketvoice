package com.ketvoice.app

import android.os.PowerManager
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.HomeShelf
import com.ketvoice.app.data.SmartPrefetch
import com.ketvoice.app.data.Track
import com.ketvoice.app.util.Diagnostics
import com.ketvoice.app.util.EconomyMode
import com.ketvoice.app.util.EconomyMode.Cause
import com.ketvoice.app.util.JankTally
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 8.2 perf82: automatic economy, the opt-in diagnostic, the smart prefetch and the hand-written baseline profile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Perf82Test {
    @Before fun reset() {
        EconomyMode.apply(null)
        EconomyMode.notice.value = null
        AppSettings.highPerformanceMode.value = false
        AppSettings.autoEconomy.value = true
    }

    @After fun clear() = reset()

    @Test fun economyCausesFollowTheThresholds() {
        assertEquals(Cause.Battery, EconomyMode.causeOf(20, 100, plugged = false, thermal = 0))
        assertNull(EconomyMode.causeOf(21, 100, plugged = false, thermal = 0))
        assertNull(EconomyMode.causeOf(5, 100, plugged = true, thermal = 0))
        assertNull(EconomyMode.causeOf(-1, 100, plugged = false, thermal = 0))
        assertEquals(Cause.Battery, EconomyMode.causeOf(50, 255, plugged = false, thermal = 0))
        assertEquals(Cause.Heat, EconomyMode.causeOf(90, 100, plugged = true, thermal = PowerManager.THERMAL_STATUS_SEVERE))
        assertNull(EconomyMode.causeOf(90, 100, plugged = true, thermal = PowerManager.THERMAL_STATUS_MODERATE))
    }

    @Test fun economyHoldsPerformanceModeOnlyWhileTheCauseLasts() {
        EconomyMode.apply(Cause.Battery)
        assertTrue(AppSettings.highPerformanceMode.value)
        assertEquals(Cause.Battery, EconomyMode.holding.value)
        assertEquals("Mode économie activé : batterie faible", EconomyMode.notice.value)
        assertFalse("the hold is never saved", EconomyMode.stored(AppSettings.highPerformanceMode.value))
        EconomyMode.apply(null)
        assertFalse(AppSettings.highPerformanceMode.value)
        assertNull(EconomyMode.holding.value)
        assertEquals("Mode économie terminé", EconomyMode.notice.value)
    }

    @Test fun aManualPerformanceModeIsNeverTurnedOff() {
        AppSettings.highPerformanceMode.value = true
        EconomyMode.apply(Cause.Heat)
        assertNull(EconomyMode.holding.value)
        EconomyMode.apply(null)
        assertTrue(AppSettings.highPerformanceMode.value)
        assertTrue(EconomyMode.stored(true))
    }

    @Test fun turningItOffWhileHeldIsRespectedUntilTheCauseEnds() {
        EconomyMode.apply(Cause.Battery)
        EconomyMode.manual(false); AppSettings.highPerformanceMode.value = false
        EconomyMode.apply(Cause.Battery)
        assertFalse(AppSettings.highPerformanceMode.value)
        EconomyMode.apply(null)
        EconomyMode.apply(Cause.Battery)
        assertTrue("a new episode holds again", AppSettings.highPerformanceMode.value)
    }

    @Test fun theAutomaticSwitchCanBeTurnedOff() {
        EconomyMode.apply(Cause.Battery)
        AppSettings.autoEconomy.value = false
        EconomyMode.autoChanged()
        assertFalse(AppSettings.highPerformanceMode.value)
        EconomyMode.apply(Cause.Heat)
        assertFalse(AppSettings.highPerformanceMode.value)
        assertNull(EconomyMode.holding.value)
    }

    @Test fun diagnosticLinesCarryNoTitleText() {
        val hash = Diagnostics.hash("dz:123456|Ma chanson")!!
        assertEquals(10, hash.length)
        assertEquals(hash, Diagnostics.hash("dz:123456|Ma chanson"))
        assertNull(Diagnostics.hash(" "))
        val line = JSONObject(Diagnostics.line("first_sound", arrayOf("ms" to 420L, "title" to hash), 1L))
        assertEquals(setOf("at", "event", "ms", "title"), line.keys().asSequence().toSet())
        assertFalse(line.toString().contains("chanson"))
    }

    @Test fun theDiagnosticFileStaysBoundedAtLineBoundaries() {
        val file = File.createTempFile("diag", ".txt").also { it.delete() }
        repeat(500) { Diagnostics.append(file, Diagnostics.line("load", arrayOf("kind" to "album", "ms" to it.toLong()), it.toLong()), max = 4096) }
        assertTrue(file.length() <= 4096)
        val lines = file.readLines()
        lines.forEach { JSONObject(it) }
        assertEquals(499L, JSONObject(lines.last()).getLong("ms"))
        file.delete()
    }

    @Test fun jankTallyResetsAfterEachLine() {
        val tally = JankTally()
        assertNull(tally.take())
        tally.add(8_000_000, false); tally.add(40_000_000, true)
        val fields = tally.take()!!.toMap()
        assertEquals(2, fields["frames"]); assertEquals(1, fields["janky"]); assertEquals(40L, fields["worst_ms"])
        assertEquals(0, tally.count)
    }

    private fun track(i: Int, cover: String? = "https://img.example/$i.jpg", albumId: String? = null) =
        Track(id = "t$i", title = "T$i", artist = "A", album = "B", coverUrl = cover, albumId = albumId)

    @Test fun prefetchIsBoundedAndOnlyAsksForWebCovers() {
        val next = listOf(track(1), track(2, cover = "content://local/2"), track(3), track(4), track(5))
        assertEquals(listOf("https://img.example/1.jpg", "https://img.example/3.jpg"), SmartPrefetch.nextCovers(next))
        val shelves = List(5) { s -> HomeShelf("S$s", "", List(10) { track(s * 10 + it) }) }
        val home = SmartPrefetch.homeCovers(shelves, emptyList())
        assertEquals(SmartPrefetch.HOME_COVERS, home.size)
        assertEquals(home.distinct(), home)
    }

    @Test fun prefetchOnlyAsksForCatalogueAlbums() {
        assertEquals("123", SmartPrefetch.album(track(1, albumId = "123"))?.id)
        assertEquals("MPREb_x", SmartPrefetch.album(track(1, albumId = "MPREb_x"))?.id)
        assertNull(SmartPrefetch.album(track(1, albumId = "local:9")))
        assertNull(SmartPrefetch.album(track(1)))
    }

    /** The profile is hand-written: every rule must parse, and every class it names must exist. */
    @Test fun baselineProfileRulesParseAndNameRealClasses() {
        val file = listOf(File("src/main/baseline-prof.txt"), File("app/src/main/baseline-prof.txt")).first { it.isFile }
        val method = Regex("""^[HSP]+L[\w/$*]+;->[\w<>$*]+\([\w/$;\[*]*\)[\w/$;\[*]+$""")
        val klass = Regex("""^L[\w/$*]+;$""")
        val rules = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(rules.size > 40)
        rules.forEach { assertTrue("bad rule: $it", method.matches(it) || klass.matches(it)) }
        rules.map { it.substringAfter("L").substringBefore(";") }.filter { '*' !in it }.distinct().forEach { name ->
            val found = runCatching { Class.forName(name.replace('/', '.'), false, javaClass.classLoader) }.isSuccess
            assertTrue("no class $name", found)
        }
    }
}
