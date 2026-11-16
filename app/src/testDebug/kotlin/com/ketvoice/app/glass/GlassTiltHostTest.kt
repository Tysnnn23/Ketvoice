package com.ketvoice.app.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import kotlin.math.cos
import kotlin.math.sin

/** The tilt listener: one, registered only while the app is resumed, feeding one state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlassTiltHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val manager get() = context.getSystemService(SensorManager::class.java)

    @Before fun sensors() {
        shadowOf(manager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_GAME_ROTATION_VECTOR))
        shadowOf(manager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        GlassLight.rest()
    }

    @After fun rest() = GlassLight.rest()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** A rotation of [degrees] about the device's y axis (its right edge going down) as a rotation vector. */
    private fun rotationEvent(degrees: Float, nanos: Long) = ShadowSensorManager.createSensorEvent(4, Sensor.TYPE_GAME_ROTATION_VECTOR).apply {
        val half = Math.toRadians(degrees / 2.0)
        values[0] = 0f; values[1] = sin(half).toFloat(); values[2] = 0f; values[3] = cos(half).toFloat()
        timestamp = nanos
    }

    @Test fun listensOnlyWhileResumedAndPrefersTheGameRotationVector() {
        val tilt = GlassTiltSensor(context)
        assertEquals(Sensor.TYPE_GAME_ROTATION_VECTOR, tilt.sensor?.type)
        val owner = Owner()
        owner.registry.addObserver(tilt)
        owner.registry.currentState = Lifecycle.State.STARTED
        assertFalse("Not before the app is resumed", shadowOf(manager).hasListener(tilt))
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(shadowOf(manager).hasListener(tilt)); assertTrue(tilt.listening)

        // Flat, then the right edge goes down by 20°: the light swings, smoothly, from one state.
        var t = 1_000_000_000L
        repeat(5) { tilt.onSensorChanged(rotationEvent(0f, t)); t += 20_000_000L }
        assertEquals(GlassLight.pack(0f, 0f), GlassLight.tilt.longValue)
        val seen = mutableListOf<Float>()
        repeat(15) {
            tilt.onSensorChanged(rotationEvent(20f, t)); t += 20_000_000L
            seen += GlassLight.tiltX()
        }
        assertTrue("The light moved (${seen.last()})", kotlin.math.abs(seen.last()) > .5f)
        assertTrue("Gradually, not in one jump", kotlin.math.abs(seen.first()) < kotlin.math.abs(seen.last()))
        assertNotEquals(GlassLight.REST_DEGREES, GlassLight.degrees(), .5f)

        // In the background: unregistered, and the light back at rest.
        owner.registry.currentState = Lifecycle.State.STARTED
        assertFalse(shadowOf(manager).hasListener(tilt)); assertFalse(tilt.listening)
        assertEquals(GlassLight.REST_DEGREES, GlassLight.degrees(), 0f)
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue("Back on screen, listening again", shadowOf(manager).hasListener(tilt))
        owner.registry.currentState = Lifecycle.State.DESTROYED
        assertFalse(shadowOf(manager).hasListener(tilt))
    }

    @Test fun theComposableRegistersOneListenerOnlyWhenActiveAndResumed() {
        var active by mutableStateOf(true)
        compose.setContent { ObserveGlassTilt(active) }
        compose.waitForIdle()
        assertEquals(1, shadowOf(manager).listeners.size)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        assertEquals("Paused: no listener", 0, shadowOf(manager).listeners.size)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(1, shadowOf(manager).listeners.size)
        compose.runOnIdle { active = false }
        compose.waitForIdle()
        assertEquals("Turned off (reduced motion, performance mode, Quest, setting)", 0, shadowOf(manager).listeners.size)
        assertEquals(GlassLight.pack(0f, 0f), GlassLight.tilt.longValue)
    }
}
