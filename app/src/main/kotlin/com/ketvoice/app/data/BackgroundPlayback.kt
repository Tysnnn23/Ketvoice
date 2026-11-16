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

    /**
     * The manufacturer's own screen for apps allowed to run in the background (5.10.0). Xiaomi, Huawei,
     * Oppo, OnePlus, Realme, Vivo and Samsung add their own battery managers on top of Android's, and
     * they stop a music app the Android setting alone does not protect. Null on other phones.
     */
    fun manufacturerScreen(context: Context): Intent? = vendorIntents(android.os.Build.MANUFACTURER)
        .map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .firstOrNull { runCatching { it.resolveActivity(context.packageManager) != null }.getOrDefault(false) }

    fun openManufacturerScreen(context: Context): Boolean {
        val intent = manufacturerScreen(context) ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    internal fun vendorIntents(manufacturer: String): List<Intent> {
        fun component(pkg: String, cls: String) = Intent().setComponent(android.content.ComponentName(pkg, cls))
        return when (manufacturer.lowercase(java.util.Locale.ROOT)) {
            "xiaomi", "redmi", "poco" -> listOf(
                component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            "huawei", "honor" -> listOf(
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
            "oppo", "realme", "oneplus" -> listOf(
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                component("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
            "vivo", "iqoo" -> listOf(
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"))
            "samsung" -> listOf(
                component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                component("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"))
            else -> emptyList()
        }
    }
}
