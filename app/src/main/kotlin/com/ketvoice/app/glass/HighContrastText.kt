package com.ketvoice.app.glass

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext

/**
 * Android's « Texte à contraste élevé » (7.1): while it is on, every glass surface uses the tinted,
 * more opaque glass ([GlassClarity.tinted]). Android 16 reports it through [AccessibilityManager];
 * earlier versions keep it in a secure setting that apps may read.
 */
object HighContrastText {
    val on = mutableStateOf(false)
    private const val KEY = "high_text_contrast_enabled"

    fun read(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 36) {
            context.getSystemService(AccessibilityManager::class.java)?.isHighContrastTextEnabled == true
        } else Settings.Secure.getInt(context.contentResolver, KEY, 0) == 1
    }.getOrDefault(false)

    /** Follows the setting while the app runs (the secure setting changes with it on every version). */
    @Composable
    fun Observe() {
        val context = LocalContext.current.applicationContext
        DisposableEffect(context) {
            on.value = read(context)
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { on.value = read(context) }
            }
            val registered = runCatching {
                context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(KEY), false, observer)
            }.isSuccess
            onDispose { if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) } }
        }
    }
}
