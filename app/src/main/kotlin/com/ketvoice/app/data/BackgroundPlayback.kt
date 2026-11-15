package com.ketvoice.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit

/**
 * Whether Android's battery saving may stop Ketvoice behind other apps. Many phones (Samsung,
 * Xiaomi, Huawei, OnePlus…) put a music app that is not exempted to sleep after a while, which is
 * heard as cuts or a stop. The listener is asked once, and can change it from Settings → Playback.
 */
object BackgroundPlayback {
    private const val PREFS = "background-playback"

    fun unrestricted(context: Context): Boolean =
        runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)

    /** Asks Android to let Ketvoice play on (the system's own dialog); falls back to the list of apps. */
    @SuppressLint("BatteryLife")
    fun ask(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(direct) }.recoverCatching { context.startActivity(list) }
    }

    /** The one-time question is due: playing, restricted, never asked on this device. */
    fun shouldOffer(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("offered", false) && !unrestricted(context)

    fun offered(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("offered", true) }
}
