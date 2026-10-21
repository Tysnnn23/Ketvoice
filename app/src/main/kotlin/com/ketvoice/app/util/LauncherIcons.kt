package com.ketvoice.app.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast

/**
 * Bascule réelle de l'icône du lanceur via les activity-alias du manifeste.
 * Les états sont conservés par PackageManager : une seule variante active.
 */
object LauncherIcons {

    private val aliases = listOf(
        "LauncherDefault",
        "LauncherGlass",
        "LauncherDark",
        "LauncherClassic"
    )

    fun apply(context: Context, variant: Int, notify: Boolean = false) {
        val index = variant.coerceIn(0, aliases.lastIndex)
        val pm = context.packageManager
        val pkg = context.packageName
        aliases.forEachIndexed { i, alias ->
            val component = ComponentName(pkg, "$pkg.$alias")
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
                "Icône mise à jour — le lanceur peut mettre quelques secondes",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
