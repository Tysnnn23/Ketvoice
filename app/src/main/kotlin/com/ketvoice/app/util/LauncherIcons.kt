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
        // The GTA VI icon: the 6.1 Vice City edition's, the default since 7.6 (owner, 2026-11-20: « laisse l'icône
        // d'app GTA 6 par défaut »). The alias keeps its name so launchers keep their shortcuts.
        "LauncherViceCity"
    )

    /** The GTA VI icon, enabled in the manifest for a new install and the default of `appIconVariant`. */
    const val DEFAULT = 4
    /** The last variant. */
    const val LAST = 4
    /** The default before 7.6 (Liquid Glass). */
    internal const val LEGACY_DEFAULT = 1
    /** Written by every save since 7.6: a stored icon is then the listener's own. */
    const val MIGRATED_KEY = "appIconDefault76"

    /**
     * The icon to use from what is stored ([stored], null when nothing is): before 7.6 every save wrote the old
     * default, so a stored [LEGACY_DEFAULT] not yet [migrated] moves to the GTA VI icon once; any other icon the
     * listener picked stays, and so does every choice made since.
     */
    fun loaded(stored: Int?, migrated: Boolean): Int = when {
        stored == null || stored !in 0..LAST -> DEFAULT
        !migrated && stored == LEGACY_DEFAULT -> DEFAULT
        else -> stored
    }

    /** The activity-alias of each `appIconVariant`, in order (one is enabled at a time). */
    internal val aliasNames: List<String> get() = aliases

    /**
     * The class of an alias: the manifest names it after the code's namespace (`com.ketvoice.app`), which a
     * debug build's application id (`com.ketvoice.app.qa`) does not change.
     */
    internal fun className(alias: String): String =
        com.ketvoice.app.MainActivity::class.java.name.substringBeforeLast('.') + "." + alias

    fun apply(context: Context, variant: Int, notify: Boolean = false) {
        val index = variant.coerceIn(0, aliases.lastIndex)
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
