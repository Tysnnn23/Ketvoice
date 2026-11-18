package com.ketvoice.app.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Light of the Liquid 27 material. The specular highlight of every glass surface follows how
 * the phone is held: turning it sideways swings the light around the rim, tipping it towards
 * or away from oneself makes the glint brighter or softer, as a real slab of glass would catch
 * a lamp. It rests at the top-left default whenever tilt is not observed (reduced motion,
 * performance mode, Quest, the setting off, no sensor, the app in the background).
 *
 * There is one state, [tilt], fed by one sensor listener ([GlassTiltSensor]) and read only in
 * draw-phase lambdas (the backdrop effects and highlight of [GlassPanel] and the fused layer),
 * so a sensor event redraws glass but never recomposes a screen.
 */
object GlassLight {
    const val REST_DEGREES = 45f
    /** How far the light swings either way from rest when the phone turns sideways. */
    const val RANGE_DEGREES = 32f
    /** How much brighter (or softer) the glint gets when the phone tips towards (or away from) the light. */
    const val GLINT_RANGE = .22f

    /** Tilt of the phone, both axes in -1..1 (x sideways, y towards/away), packed in one state. */
    val tilt = mutableLongStateOf(pack(0f, 0f))

    fun tiltX(packed: Long = tilt.longValue): Float = Float.fromBits((packed ushr 32).toInt())
    fun tiltY(packed: Long = tilt.longValue): Float = Float.fromBits(packed.toInt())

    fun pack(x: Float, y: Float): Long =
        (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xFFFFFFFFL)

    /** Light angle in degrees for a sideways tilt; read from draw-phase lambdas only. */
    fun degrees(x: Float = tiltX()): Float = REST_DEGREES + safe(x) * RANGE_DEGREES

    /** Unit vector pointing from the panel toward the light, in shader coordinates. */
    fun direction(angleDegrees: Float = degrees()): Pair<Float, Float> {
        val a = Math.toRadians(angleDegrees.toDouble())
        return -cos(a).toFloat() to -sin(a).toFloat()
    }

    /** Multiplier of the rim light and sheen for a tilt towards (+) or away from (−) the light. */
    fun glint(y: Float = tiltY()): Float = 1f + safe(y) * GLINT_RANGE

    fun rest() { if (tilt.longValue != pack(0f, 0f)) tilt.longValue = pack(0f, 0f) }

    /**
     * Whether the highlight should follow the tilt: the switch is on, glass is on, and nothing
     * asks for stillness or economy — reduced motion, the performance mode, Quest (a headset's
     * window has no tilt worth following).
     */
    fun tiltWanted(setting: Boolean, liquidGlass: Boolean, reducedMotion: Boolean, highPerformance: Boolean, quest: Boolean) =
        setting && liquidGlass && !reducedMotion && !highPerformance && !quest

    private fun safe(v: Float) = if (v.isFinite()) v.coerceIn(-1f, 1f) else 0f
}

/**
 * Turns the phone's "up" direction (a unit vector in device coordinates) into a smooth tilt.
 * The tilt is measured from a reference that drifts slowly towards how the phone is held, so
 * the light rests at its default while the listener holds still, whatever the angle, and moves
 * when the phone moves. Sub-threshold jitter is ignored so a phone at rest never redraws glass.
 */
class GlassTiltFilter {
    private var baseX = 0f; private var baseY = 0f; private var baseZ = 0f
    private var started = false
    private var x = 0f; private var y = 0f
    private var shownX = 0f; private var shownY = 0f

    /** Returns the new tilt (x, y) to show, or null when it did not move enough to be worth a frame. */
    fun update(ux: Float, uy: Float, uz: Float, dtSeconds: Float): Pair<Float, Float>? {
        if (!(ux.isFinite() && uy.isFinite() && uz.isFinite())) return null
        val n = sqrt(ux * ux + uy * uy + uz * uz)
        if (n < 1e-3f) return null
        val nx = ux / n; val ny = uy / n; val nz = uz / n
        if (!started) { baseX = nx; baseY = ny; baseZ = nz; started = true }
        val dt = if (dtSeconds.isFinite()) dtSeconds.coerceIn(0f, .25f) else 0f
        // The reference follows the hand within a few seconds.
        val follow = 1f - kotlin.math.exp(-dt / RECENTER_SECONDS)
        baseX += (nx - baseX) * follow; baseY += (ny - baseY) * follow; baseZ += (nz - baseZ) * follow
        // Sideways: the up vector leans towards the raised edge (−x when the right edge goes down).
        val targetX = ((baseX - nx) / FULL_TILT).coerceIn(-1f, 1f)
        // Towards / away: the screen faces up more (z grows) when the top tips away from the eyes.
        val targetY = ((nz - baseZ) / FULL_TILT).coerceIn(-1f, 1f)
        // Smoothing over about a tenth of a second: the light glides, it never jumps.
        val smooth = 1f - kotlin.math.exp(-dt / SMOOTH_SECONDS)
        x += (targetX - x) * smooth; y += (targetY - y) * smooth
        if (abs(x - shownX) < STEP && abs(y - shownY) < STEP) return null
        shownX = x; shownY = y
        return x to y
    }

    fun reset() { started = false; x = 0f; y = 0f; shownX = 0f; shownY = 0f }

    companion object {
        /** A change of the up vector this large (about 20°) is a full tilt. */
        const val FULL_TILT = .34f
        const val RECENTER_SECONDS = 3.5f
        const val SMOOTH_SECONDS = .12f
        /**
         * Smallest change worth a redraw: about two thirds of a degree of light (0.4° of the phone). At a quarter of a
         * degree (until 6.5) the tremor of a hand and the slow recentring kept every glass surface redrawing, blur and
         * lens included, nearly fifty times a second whenever the phone was held (6.6).
         */
        const val STEP = .02f
    }
}

/**
 * The one listener behind [GlassLight.tilt]. Registered only while its lifecycle is resumed
 * (the app on screen), unregistered and the light rested on pause. Prefers the game rotation
 * vector (gyroscope fused with the accelerometer, no magnetometer jumps), then the rotation
 * vector, then gravity and the accelerometer.
 */
class GlassTiltSensor(context: Context) : SensorEventListener, DefaultLifecycleObserver {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    internal val sensor: Sensor? = manager?.let { m ->
        listOf(Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER)
            .firstNotNullOfOrNull { m.getDefaultSensor(it) }
    }
    private val filter = GlassTiltFilter()
    private val matrix = FloatArray(9)
    private var lastNanos = 0L
    /** Whether the listener is registered right now. */
    var listening = false
        private set

    override fun onResume(owner: LifecycleOwner) = start()
    override fun onPause(owner: LifecycleOwner) = stop()

    fun start() {
        if (listening || manager == null || sensor == null) return
        filter.reset(); lastNanos = 0L
        listening = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (listening) manager?.unregisterListener(this)
        listening = false
        GlassLight.rest()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values ?: return
        val dt = if (lastNanos == 0L) 0f else (event.timestamp - lastNanos) / 1e9f
        lastNanos = event.timestamp
        val up = when (event.sensor?.type ?: sensor?.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                if (v.size < 3) return
                runCatching { SensorManager.getRotationMatrixFromVector(matrix, v) }.getOrElse { return }
                // The world's up axis seen from the device: the third row of the rotation matrix.
                Triple(matrix[6], matrix[7], matrix[8])
            }
            // At rest an accelerometer (and the gravity sensor) reads +g along the device's up.
            else -> if (v.size < 3) return else Triple(v[0], v[1], v[2])
        }
        val next = filter.update(up.first, up.second, up.third, dt) ?: return
        GlassLight.tilt.longValue = GlassLight.pack(next.first, next.second)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Observes the tilt while [active] and the app is resumed; detaches and rests the light otherwise. */
@Composable
fun ObserveGlassTilt(active: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(active, context, lifecycle) {
        if (!active) {
            GlassLight.rest()
            return@DisposableEffect onDispose { }
        }
        val tilt = GlassTiltSensor(context.applicationContext)
        // Adding the observer to a resumed lifecycle replays onResume at once.
        lifecycle.addObserver(tilt)
        onDispose {
            lifecycle.removeObserver(tilt)
            tilt.stop()
        }
    }
}

/** Shader-ready light for one panel. Call from draw-phase lambdas only: it reads [GlassLight.tilt]. */
internal fun liquid27Light(touchX: Float, touchY: Float, press: Float): Liquid27Light {
    val packed = GlassLight.tilt.longValue
    val (x, y) = GlassLight.direction(GlassLight.degrees(GlassLight.tiltX(packed)))
    return Liquid27Light(x, y, touchX, touchY, press, GlassLight.glint(GlassLight.tiltY(packed)))
}
