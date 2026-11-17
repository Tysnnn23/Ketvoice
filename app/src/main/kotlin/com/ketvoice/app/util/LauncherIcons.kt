package com.ketvoice.app.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast

object LauncherIcons {

    private val aliases = listOf(
        "LauncherDefault",
        "LauncherGlass",
        "LauncherDark",
        "LauncherClassic",
        // The Vice City limited edition (6.1), offered until 10 December 2026 (ViceCityEdition).
        "LauncherViceCity"
    )

    /** The activity-alias of each `appIconVariant`, in order (one is enabled at a time). */
    internal val aliasNames: List<String> get() = aliases

    /**
     * The class of an alias: the manifest names it after the code's namespace (`com.ketvoice.app`), which a
     * debug build's application id (`com.ketvoice.app.qa`) does not change.
     */
    internal fun className(alias: String): String =
        com.ketvoice.app.MainActivity::class.java.name.substringBeforeLast('.') + "." + alias

    fun apply(context: Context, variant: Int, notify: Boolean = false) {
        // After the edition its icon is never enabled again: the default one is.
        val allowed = if (com.ketvoice.app.data.ViceCityEdition.iconAllowed(variant)) variant
            else com.ketvoice.app.data.ViceCityEdition.DEFAULT_ICON
        val index = allowed.coerceIn(0, aliases.lastIndex)
        val pm = context.packageManager
        val pkg = context.packageName
        aliases.forEachIndexed { i, alias ->
            val component = ComponentName(pkg, className(alias))
            val desired = if (i == index) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            runCatching {
                if (pm.getComponentEnabledSetting(component) != desired) {
                    pm.setComponentEnabledSetting(
                        component,
                        desired,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        }
        if (notify) {
            Toast.makeText(
                context,
                com.ketvoice.app.data.AppText.t("Icône mise à jour — le lanceur peut mettre quelques secondes"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
