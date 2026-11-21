package com.ketvoice.desktop.player

import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.desktop.audio.AudioEngine
import com.ketvoice.desktop.audio.AudioEvent
import com.ketvoice.desktop.audio.AudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A minimal engine with the Phase 1 contract: records what the player asks, plays the preloaded deck at the end. */
internal class FakeEngine : AudioEngine {
    override val events = MutableSharedFlow<AudioEvent>(extraBufferCapacity = 16)
    val loads = mutableListOf<Pair<AudioSource, Long>>()
    val preloads = mutableListOf<AudioSource?>()
    val seeks = mutableListOf<Long>()
    var current: AudioSource? = null
    var next: AudioSource? = null
    var playing = false
    var position = 0L
    override var volume = 1f
    override fun load(source: AudioSource, startMs: Long, play: Boolean) { loads += source to startMs; current = source; position = startMs; playing = play }
    override fun preloadNext(source: AudioSource?) { preloads += source; next = source }
    override fun play() { playing = true }
    override fun pause() { playing = false }
    override fun seekTo(ms: Long) { seeks += ms; position = ms }
    override fun positionMs() = position
    override fun durationMs() = 180_000L
    override fun isPlaying() = playing
    override fun setEqualizer(gainsDb: FloatArray?) = Unit
    override fun release() = Unit

    /** The current deck reaches its end: the held one follows without a gap. */
    fun end() {
        val held = next
        current = held; next = null; position = 0L
        playing = current != null
        // Like FfmpegAudioEngine: a gapless takeover is Advanced, a real end (nothing held) is Ended.
        events.tryEmit(if (held != null) AudioEvent.Advanced(held) else AudioEvent.Ended)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerFacadeTest {
    private val tracks = (1..5).map { Track("t$it", "Titre $it", "Artiste $it", "Album $it", durationMs = 180_000) }
    private fun src(id: String) = AudioSource.Url("https://audio.test/$id")
    private lateinit var engine: FakeEngine
    private val unplayable = HashSet<String>()

    @Before fun setUp() { Player.resetForTest(); engine = FakeEngine(); unplayable.clear() }
    @After fun tearDown() = Player.resetForTest()

    private fun TestScope.attach() {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        Player.attach(engine, resolve = { t -> if (t.id in unplayable) null else src(t.id) }, scope = scope)
        Player.setAutoplay(false)
    }

    private val s get() = Player.state.value

    @Test fun playQueueLoadsAndPreloadsTheNextSource() = runTest {
        attach()
        Player.playQueue(tracks, 1)
        assertEquals(listOf(src("t2") to 0L), engine.loads)
        assertEquals(src("t3"), engine.next)
        assertTrue(s.isPlaying)
        assertEquals(1, s.index)
    }

    @Test fun endedMovesOnGaplesslyWithoutReloading() = runTest {
        attach()
        Player.playQueue(tracks, 0)
        engine.end()
        assertEquals(1, s.index)
        assertEquals(1, engine.loads.size)
        assertEquals(src("t2"), engine.current)
        assertEquals(src("t3"), engine.next)
        assertTrue(s.isPlaying)
    }

    @Test fun nextAndPreviousFollowTheHistory() = runTest {
        attach()
        Player.playQueue(tracks, 0)
        Player.next()
        Player.next()
        assertEquals(2, s.index)
        assertEquals(src("t3"), engine.loads.last().first)
        Player.moveQueue(0, 4) // t1 goes to the end; « précédent » still goes back to t2, the title left
        Player.previous()
        assertEquals("t2", s.current?.id)
        // After the first seconds « précédent » restarts the title.
        engine.position = 10_000
        Player.previous()
        assertEquals("t2", s.current?.id)
        assertEquals(0L, engine.seeks.last())
    }

    @Test fun queueChangesUpdateThePreloadedSource() = runTest {
        attach()
        Player.playQueue(tracks, 0)
        Player.playNext(tracks[4])
        assertEquals(src("t5"), engine.next)
        Player.removeAt(1)
        assertEquals(src("t2"), engine.next)
        Player.enqueue(Track("x", "X", "Y", "Z"))
        assertEquals("x", s.queue.last().id)
    }

    @Test fun repeatOneAndRepeatAll() = runTest {
        attach()
        Player.playQueue(tracks, 4)
        assertNull(engine.next) // last title, no repeat: nothing follows
        Player.setRepeat(one = false, all = true)
        assertEquals(src("t1"), engine.next)
        engine.end()
        assertEquals(0, s.index)
        Player.setRepeat(one = true, all = false)
        assertEquals(src("t1"), engine.next)
        engine.end()
        assertEquals(0, s.index)
        assertTrue(s.isPlaying)
        // Repeat-one still lets « suivant » move on.
        Player.next()
        assertEquals(1, s.index)
    }

    @Test fun endOfQueueStops() = runTest {
        attach()
        Player.playQueue(tracks, 4)
        engine.end()
        assertFalse(s.isPlaying)
        assertEquals(4, s.index)
    }

    @Test fun shuffleKeepsTheCurrentTitleAndRestoresTheOrder() = runTest {
        attach()
        Player.playQueue(tracks, 2)
        Player.setShuffle(true)
        assertEquals(0, s.index)
        assertEquals("t3", s.current?.id)
        assertEquals(tracks.map { it.id }.toSet(), s.queue.map { it.id }.toSet())
        assertEquals(src(s.queue[1].id), engine.next)
        Player.setShuffle(false)
        assertEquals(tracks.map { it.id }, s.queue.map { it.id })
        assertEquals(2, s.index)
        assertEquals(1, engine.loads.size) // the title kept playing
    }

    @Test fun aFailedTitleIsReportedAndSkipped() = runTest {
        attach()
        val errors = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { Player.error.toList(errors) }
        unplayable += "t2"
        Player.playQueue(tracks, 1)
        assertEquals(listOf(Player.UNAVAILABLE), errors)
        assertEquals("t3", s.current?.id)
        assertEquals(src("t3"), engine.loads.single().first)
        // An engine error on the playing title skips too.
        engine.events.tryEmit(AudioEvent.Error("decoder"))
        assertEquals("t4", s.current?.id)
        // Nothing playable at all: one message, playback stops.
        unplayable += tracks.map { it.id }
        errors.clear()
        Player.playQueue(tracks, 0)
        assertEquals(Player.NOTHING_PLAYABLE, errors.last())
        assertFalse(s.isPlaying)
    }

    @Test fun positionIsPolledWhilePlaying() = runTest {
        attach()
        Player.playQueue(tracks, 0)
        engine.position = 42_000
        advanceTimeBy(300)
        assertEquals(42_000L, s.positionMs)
        assertEquals(180_000L, s.durationMs)
        Player.pause()
        engine.position = 50_000
        advanceTimeBy(1_000)
        assertEquals(42_000L, s.positionMs)
        assertFalse(engine.playing)
        Player.toggle()
        assertTrue(engine.playing)
        Player.setVolume(0.4f)
        assertEquals(0.4f, engine.volume)
        assertEquals(0.4f, Player.volume.value)
    }

    @Test fun autoplayAddsTitlesOfTheSameStyle() = runTest {
        attach()
        val radio = (1..6).map { Track("r$it", "Radio $it", "Autre $it", "A$it", durationMs = 1) }
        Player.autoplayCandidates = { radio }
        Player.setAutoplay(true)
        Player.playQueue(tracks.take(2), 0)
        assertEquals(listOf("t1", "t2", "r1", "r2", "r3", "r4", "r5", "r6"), s.queue.map { it.id })
        // The listener's own additions go before the automatic titles.
        Player.enqueue(tracks[4])
        assertEquals("t5", s.queue[2].id)
    }
}
