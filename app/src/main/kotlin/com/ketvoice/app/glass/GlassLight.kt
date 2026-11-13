package com.ketvoice.app.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Light direction of the Liquid 27 material. The highlight follows how the device
 * is held, like the reference material; it rests at the top-left default whenever
 * tilt is not observed (reduced motion, Quest, other glass styles, no sensor).
 */
object GlassLight {
    const val REST_DEGREES = 45f
    private const val RANGE_DEGREES = 32f
    private const val STEP_DEGREES = .75f

    /** Current light angle in degrees; read from draw-phase lambdas only. */
    val degrees = mutableFloatStateOf(REST_DEGREES)

    /** Unit vector pointing from the panel toward the light, in shader coordinates. */
    fun direction(angleDegrees: Float = degrees.floatValue): Pair<Float, Float> {
        val a = Math.toRadians(angleDegrees.toDouble())
        return -cos(a).toFloat() to -sin(a).toFloat()
    }

    /** Maps the gravity x component (m/s², device frame) to a bounded light angle. */
    fun angleForGravity(gx: Float): Float {
        if (!gx.isFinite()) return REST_DEGREES
        val normalized = (gx / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
        return REST_DEGREES + normalized * RANGE_DEGREES
    }

    /** Low-pass step that ignores sub-threshold jitter, so idle phones do not redraw glass. */
    fun next(current: Float, target: Float, smoothing: Float = .18f): Float {
        val value = current + (target - current) * smoothing
        return if (abs(value - current) < STEP_DEGREES) current else value
    }

    fun rest() { degrees.floatValue = REST_DEGREES }
}

/** Observes the gravity sensor while [active]; detaches and rests the light otherwise. */
@Composable
fun ObserveGlassTilt(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(active, context) {
        if (!active) {
            GlassLight.rest()
            return@DisposableEffect onDispose { }
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) {
            GlassLight.rest()
            return@DisposableEffect onDispose { }
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val current = GlassLight.degrees.floatValue
                val next = GlassLight.next(current, GlassLight.angleForGravity(-event.values[0]))
                if (next != current) GlassLight.degrees.floatValue = next
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            manager.unregisterListener(listener)
            GlassLight.rest()
        }
    }
}

/** Shader-ready light for one panel. */
internal fun liquid27Light(touchX: Float, touchY: Float, press: Float): Liquid27Light {
    val (x, y) = GlassLight.direction()
    return Liquid27Light(x, y, touchX, touchY, press)
}
