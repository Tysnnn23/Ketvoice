package com.ketvoice.app.ui.motion

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.data.AppSettings

internal data class RefreshMode(val id: Int, val width: Int, val height: Int, val hz: Float)

/** Never trade resolution or request a fictitious mode on a 60/90 Hz display. */
internal object RefreshRatePolicy {
    fun select(current: RefreshMode, modes: List<RefreshMode>): RefreshMode? = modes
        .filter { it.width == current.width && it.height == current.height && it.hz in 119f..121f }
        .minByOrNull { kotlin.math.abs(it.hz - 120f) }
    fun mayRequest(enabled: Boolean, resumed: Boolean, powerSaving: Boolean): Boolean = enabled && resumed && !powerSaving
}

/** Restore the previous preference only while this component still owns it. */
internal class RefreshWindowLease(private val window: Window) {
    private val originalId = window.attributes.preferredDisplayModeId
    private val originalRate = window.attributes.preferredRefreshRate
    private var owned: RefreshMode? = null
    fun request(mode: RefreshMode) {
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != mode.id || attrs.preferredRefreshRate != mode.hz) {
            attrs.preferredDisplayModeId = mode.id
            attrs.preferredRefreshRate = mode.hz
            window.attributes = attrs
        }
        owned = mode
    }
    fun release() {
        val mode = owned ?: return
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId == mode.id && attrs.preferredRefreshRate == mode.hz) {
            attrs.preferredDisplayModeId = originalId
            attrs.preferredRefreshRate = originalRate
            window.attributes = attrs
        }
        owned = null
    }
}

internal data class RefreshStatus(val supported: Boolean = false, val actualHz: Float = 0f,
                                  val requested: Boolean = false, val powerSaving: Boolean = false)

internal object DisplayRefresh {
    var status by mutableStateOf(RefreshStatus())
        internal set
}

/** A request to Android, not an FPS counter and not a platform-policy bypass. */
@Composable
internal fun ObserveDisplayRefresh() {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val enabled = AppSettings.highRefreshRate.value
    DisposableEffect(view, lifecycle, enabled) {
        var context = view.context
        while (context is ContextWrapper && context !is Activity && context.baseContext !== context) context = context.baseContext
        val activity = context as? Activity
        val window = activity?.window
        val manager = view.context.getSystemService(DisplayManager::class.java)
        val power = view.context.getSystemService(PowerManager::class.java)
        val lease = window?.let(::RefreshWindowLease)
        fun refresh() {
            val display = view.display
            val mode = display?.mode
            fun android.view.Display.Mode.asMode() = RefreshMode(modeId, physicalWidth, physicalHeight, refreshRate)
            val selected = if (mode != null && !BuildConfig.IS_QUEST)
                RefreshRatePolicy.select(mode.asMode(), display.supportedModes.map { it.asMode() }) else null
            val saving = power?.isPowerSaveMode == true
            val request = selected != null && lease != null && RefreshRatePolicy.mayRequest(enabled,
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), saving)
            if (request) {
                lease!!.request(selected!!)
            } else lease?.release()
            DisplayRefresh.status = RefreshStatus(selected != null, display?.refreshRate ?: 0f, request, saving)
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = refresh()
            override fun onDisplayRemoved(displayId: Int) = refresh()
            override fun onDisplayChanged(displayId: Int) = refresh()
        }
        val observer = LifecycleEventObserver { _, _ -> refresh() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh()
        }
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(view.context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
        onDispose {
            lifecycle.removeObserver(observer)
            manager?.unregisterDisplayListener(listener)
            view.context.unregisterReceiver(receiver)
            lease?.release()
            DisplayRefresh.status = DisplayRefresh.status.copy(requested = false)
        }
    }
}
