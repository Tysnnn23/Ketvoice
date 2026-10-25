package com.ketvoice.app.ui.motion

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.ketvoice.app.data.AppSettings

object Motion {
    const val FADE = 220
    const val PRESS = 140
    const val FOLLOW = 380
    val reduced: Boolean get() = AppSettings.reduceAnimations.value || AppSettings.systemReduceMotion.value
    fun duration(normal: Int = FADE): Int = if (reduced) 0 else normal
}

@Composable
fun ObserveSystemMotion() {
    val resolver = LocalContext.current.applicationContext.contentResolver
    DisposableEffect(resolver) {
        fun refresh() {
            AppSettings.systemReduceMotion.value = runCatching {
                Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            }.getOrDefault(false)
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        refresh()
        runCatching { resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer) }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
}
