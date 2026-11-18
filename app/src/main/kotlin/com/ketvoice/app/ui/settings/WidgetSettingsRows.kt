package com.ketvoice.app.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ketvoice.app.data.AppText
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.widget.WidgetPinning

/**
 * « Widgets » (6.4): « Ajouter le widget » for each widget (the launcher asks where to put it) and, on Android 13+,
 * each Quick Settings tile. Phones only.
 */
@Composable
internal fun ColumnScope.WidgetShortcutRows() {
    val ctx = LocalContext.current
    val pinnable = remember { WidgetPinning.supported(ctx) }
    var added by remember { mutableStateOf(emptySet<String>()) }
    WidgetPinning.widgets.forEach { entry ->
        SRow(KetIcons.Appearance, entry.name, sub = entry.description,
            value = if (pinnable) "Ajouter le widget" else "",
            onClick = if (pinnable) ({ WidgetPinning.pin(ctx, entry) }) else null)
    }
    if (!pinnable) SRow(KetIcons.Info, "Ajouter un widget",
        sub = "Touchez longuement l'écran d'accueil, puis Widgets, et choisissez Ketvoice.")
    if (!WidgetPinning.tilesSupported()) return
    WidgetPinning.tiles.forEach { tile ->
        SRow(if (tile.name == "Ketmix") KetIcons.Shuffle else KetIcons.Play, tile.name,
            sub = "Réglages rapides", value = if (tile.name in added) "Ajouté" else "Ajouter aux réglages rapides") {
            WidgetPinning.addTile(ctx, tile, AppText.t(tile.name)) { ok -> if (ok) added = added + tile.name }
        }
    }
}
