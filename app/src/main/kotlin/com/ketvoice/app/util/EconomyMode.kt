package com.ketvoice.app.util

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText

/**
 * 8.2 « Mode économie auto » (owner): a low battery (20 % or less, not charging) or a hot phone (thermal status
 * SEVERE or worse) switches performance mode on for as long as it lasts, with one quiet line, and back off after.
 * The listener's own choice wins: performance mode already on is left alone (and never turned off by this), turning
 * it off while held is respected until the cause ends, and « Mode économie auto » off stops the automatic switch.
 * Watched only while the app is on screen (MainActivity onStart/onStop): performance mode only changes what is drawn.
 */
object EconomyMode {
    enum class Cause { Battery, Heat }

    /** Why performance mode is held on right now; null when the listener's own setting applies. */
    val holding = mutableStateOf<Cause?>(null)
    /** One line for the snackbar (EconomyNotice), cleared once shown. */
    val notice = mutableStateOf<String?>(null)

    private var cause: Cause? = null
    private var declined = false
    private var level = -1
    private var scale = 100
    private var plugged = true
    private var thermal = 0
    private var receiver: BroadcastReceiver? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    internal fun causeOf(level: Int, scale: Int, plugged: Boolean, thermal: Int): Cause? = when {
        thermal >= PowerManager.THERMAL_STATUS_SEVERE -> Cause.Heat
        !plugged && level >= 0 && scale > 0 && level * 100 <= 20 * scale -> Cause.Battery
        else -> null
    }

    /** The value SettingsStore keeps: what the listener chose, not the temporary hold. */
    fun stored(value: Boolean): Boolean = if (holding.value != null) false else value

    /** The listener flipped « Mode performance » by hand: that choice wins until the cause ends. */
    fun manual(on: Boolean) {
        if (holding.value == null) return
        holding.value = null
        declined = !on
    }

    /** « Mode économie auto » was switched: off lets go at once (silently), on applies the current cause. */
    fun autoChanged() = apply(cause)

    internal fun apply(next: Cause?) {
        cause = next
        if (next == null) { declined = false; release(say = true); return }
        if (!AppSettings.autoEconomy.value || declined) { release(say = false); return }
        if (holding.value == null && AppSettings.highPerformanceMode.value) return
        if (holding.value == null) notice.value = line(next)
        holding.value = next
        AppSettings.highPerformanceMode.value = true
    }

    private fun release(say: Boolean) {
        if (holding.value == null) return
        holding.value = null
        AppSettings.highPerformanceMode.value = false
        if (say) notice.value = "Mode économie terminé"
    }

    internal fun line(cause: Cause) =
        if (cause == Cause.Heat) "Mode économie activé : appareil chaud" else "Mode économie activé : batterie faible"

    fun watch(activity: Activity) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = battery(i) }
        receiver = r
        val sticky = runCatching {
            androidx.core.content.ContextCompat.registerReceiver(activity.applicationContext, r,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }.getOrNull()
        sticky?.let(::battery)
        watchHeat(activity.applicationContext)
    }

    private fun watchHeat(app: Context) {
        // The thermal status exists since Android 10 (Ketvoice TV runs from Android 9).
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val pm = app.getSystemService(PowerManager::class.java) ?: return
        val l = PowerManager.OnThermalStatusChangedListener { thermal = it; apply(causeOf(level, scale, plugged, thermal)) }
        thermalListener = l
        runCatching { pm.addThermalStatusListener(app.mainExecutor, l) }
    }

    fun unwatch(activity: Activity) {
        val app = activity.applicationContext
        receiver?.let { r -> runCatching { app.unregisterReceiver(r) } }
        receiver = null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            thermalListener?.let { l -> runCatching { app.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(l) } }
        thermalListener = null
    }

    private fun battery(intent: Intent) {
        level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        apply(causeOf(level, scale, plugged, thermal))
    }
}

/** The quiet line of [EconomyMode], in the app's snackbar. */
@Composable
fun EconomyNotice() {
    val line = EconomyMode.notice.value
    LaunchedEffect(line) {
        if (line == null) return@LaunchedEffect
        val bar = com.ketvoice.app.ui.common.TrackActionFeedback.snackbar
        bar.currentSnackbarData?.dismiss()
        try { bar.showSnackbar(AppText.t(line)) } finally { if (EconomyMode.notice.value == line) EconomyMode.notice.value = null }
    }
}
