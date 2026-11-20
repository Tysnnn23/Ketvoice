package com.ketvoice.app.player

import android.os.Looper
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 7.5 « Fondu à la pause et à la reprise »: the pause shows at once, the volume glides, then the engine stops. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PauseFadeHostTest {
    /** A stand-in for ExoPlayer: what the bridge reads and sets, nothing else. */
    private class Engine {
        var playWhenReady = true
        var volume = 1f
        var state = Player.STATE_READY
        val volumes = mutableListOf<Float>()
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, args ->
            when (m.name) {
                "getApplicationLooper" -> Looper.getMainLooper()
                "getPlayWhenReady" -> playWhenReady
                "isPlaying" -> playWhenReady && state == Player.STATE_READY
                "setPlayWhenReady" -> { playWhenReady = args[0] as Boolean; null }
                "play" -> { playWhenReady = true; null }
                "pause" -> { playWhenReady = false; null }
                "getPlaybackState" -> state
                "getVolume" -> volume
                "setVolume" -> { volume = args[0] as Float; volumes += volume; null }
                "equals" -> false
                "hashCode" -> 1
                else -> when (m.returnType) { Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L; Float::class.javaPrimitiveType -> 0f; else -> null }
            }
        } as Player
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun bridge(engine: Engine, fades: Boolean = true) = QueueBridgePlayer(engine.player, {}, {}) { fades }

    @Test fun aPauseShowsAtOnceAndTheSoundGlidesDown() {
        val engine = Engine(); val bridge = bridge(engine)
        val seen = mutableListOf<Boolean>()
        bridge.addListener(object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { seen += isPlaying } })
        bridge.pause()
        assertFalse("the pause is shown at once", bridge.playWhenReady)
        assertFalse(bridge.isPlaying)
        assertEquals(listOf(false), seen)
        assertTrue("the sound still plays while it fades", engine.playWhenReady)
        idle(100)
        assertTrue("half way down", engine.volume in 0.05f..0.95f)
        idle(200)
        assertFalse("then the engine pauses", engine.playWhenReady)
        assertEquals("and the volume is back for the next resume", 1f, engine.volume, 0f)
        val ramp = engine.volumes.dropLast(1)
        assertTrue("the volume only goes down", ramp.zipWithNext().all { (a, b) -> b <= a })
        assertTrue("small steps, no click", ramp.zipWithNext().all { (a, b) -> a - b < .2f })
        println("pause-fade: ${ramp.size} steps over ${PauseFade.MS} ms, largest step ${"%.3f".format(ramp.zipWithNext().maxOf { (a, b) -> a - b })}")
    }

    @Test fun aResumeRisesFromSilence() {
        val engine = Engine().apply { playWhenReady = false }; val bridge = bridge(engine)
        bridge.play()
        assertTrue("the engine starts at once", engine.playWhenReady)
        assertEquals(0f, engine.volume, 0f)
        idle(300)
        assertEquals(1f, engine.volume, 0f)
        assertTrue(engine.volumes.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun aResumeDuringTheFadeRisesAgainWithoutStopping() {
        val engine = Engine(); val bridge = bridge(engine)
        bridge.pause(); idle(100)
        val low = engine.volume
        bridge.play()
        assertTrue(bridge.playWhenReady)
        idle(300)
        assertTrue("the engine never paused", engine.playWhenReady)
        assertEquals(1f, engine.volume, 0f)
        assertTrue(low < 1f)
    }

    @Test fun noFadeWhenSilentInBitPerfectOrDuringAMix() {
        val idleEngine = Engine().apply { state = Player.STATE_BUFFERING }
        bridge(idleEngine).pause()
        assertFalse("buffering: nothing heard, paused at once", idleEngine.playWhenReady)
        val mixing = Engine()
        bridge(mixing, fades = false).pause()
        assertFalse("a mix moves its own volumes: paused at once", mixing.playWhenReady)
        assertTrue(mixing.volumes.isEmpty() || mixing.volumes.last() == 1f)
    }

    @Test fun aVolumeAskedDuringTheFadeIsTheOneItComesBackTo() {
        val engine = Engine(); val bridge = bridge(engine)
        bridge.pause(); idle(50)
        bridge.volume = .5f
        assertEquals(.5f, bridge.volume, 0f)
        idle(300)
        assertEquals(.5f, engine.volume, 0f)
    }

    @Test fun theCurveEndsWhereItShould() {
        assertEquals(1f, PauseFade.level(1f, 0f, 0f), 1e-6f)
        assertEquals(0f, PauseFade.level(1f, 0f, 1f), 1e-6f)
        assertEquals(1f, PauseFade.level(0f, 1f, 1f), 1e-6f)
        assertEquals(20, PauseFade.steps())
        assertFalse(PauseFade.applies(audible = true, bitPerfect = true, mixing = false))
        assertFalse(PauseFade.applies(audible = true, bitPerfect = false, mixing = true))
        assertTrue(PauseFade.applies(audible = true, bitPerfect = false, mixing = false))
    }
}
