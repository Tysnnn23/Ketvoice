package com.ketvoice.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.LyricsOrder
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text

/**
 * « Ordre des sources de paroles » (8.4): the lyrics providers, dragged by their handle into the order the automatic
 * lookup tries them. Each move is kept at once; with a screen reader, every row offers « Monter » and « Descendre ».
 */
@Composable
internal fun LyricsOrderSheet(onClose: () -> Unit) {
    var order by remember { mutableStateOf(LyricsOrder.parse(AppSettings.lyricsOrder.value)) }
    fun keep(next: List<String>) {
        order = next
        AppSettings.lyricsOrder.value = LyricsOrder.encode(next)
        SettingsStore.save()
    }
    fun close() {
        // The title playing is looked up again in the new order.
        com.ketvoice.app.data.LyricsRepo.clear()
        com.ketvoice.app.player.Player.state.value.current?.let { com.ketvoice.app.data.LyricsRepo.load(it, force = true) }
        onClose()
    }
    SettingsSheet(onDismiss = ::close) {
        LyricsOrderContent(order, AppSettings.lyricsSource.value == MusicApi.SRC_AUTO, ::keep, ::close)
    }
}

/** What the sheet shows; stateless, so the host tests draw it as it is. */
@Composable
internal fun LyricsOrderContent(order: List<String>, automatic: Boolean, onOrder: (List<String>) -> Unit, onClose: () -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp).testTag("lyrics-order"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSheetHeader("Ordre des sources de paroles",
            AppText.t("Les sources sont essayées dans cet ordre : la première qui a des paroles gagne. Faites glisser une source pour la déplacer."),
            onClose = onClose)
        if (!automatic) Text("Une source choisie dans « Source des paroles » est utilisée seule : cet ordre sert à la source Automatique.",
            color = theme.secondary, style = Ket27.Type.footnote)
        var dragging by remember { mutableStateOf<String?>(null) }
        var offset by remember { mutableFloatStateOf(0f) }
        var rowHeight by remember { mutableIntStateOf(1) }
        val latest by rememberUpdatedState(order)
        Column(Modifier.fillMaxWidth().settingsGroup()) {
            order.forEachIndexed { index, id ->
                val up = AppText.t("Monter"); val down = AppText.t("Descendre")
                Row(Modifier.fillMaxWidth().heightIn(min = Ket27.Metrics.row).onSizeChanged { rowHeight = it.height.coerceAtLeast(1) }
                    .graphicsLayer { translationY = if (dragging == id) offset else 0f; shadowElevation = if (dragging == id) 12f else 0f }
                    .background(if (dragging == id) theme.card else androidx.compose.ui.graphics.Color.Transparent)
                    .semantics { customActions = listOfNotNull(
                        if (index > 0) CustomAccessibilityAction(up) { onOrder(LyricsOrder.move(latest, index, index - 1)); true } else null,
                        if (index < order.lastIndex) CustomAccessibilityAction(down) { onOrder(LyricsOrder.move(latest, index, index + 1)); true } else null) }
                    .padding(horizontal = 16.dp, vertical = 10.dp).testTag("lyrics-order-" + id), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(theme.onBackground.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
                        Text((index + 1).toString(), localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(MusicApi.sourceName(id), color = theme.onBackground, fontSize = 17.sp,
                        modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
                    Icon(Icons.Filled.DragHandle, AppText.t("Déplacer"), tint = theme.secondary,
                        modifier = Modifier.size(44.dp).padding(10.dp).testTag("lyrics-order-handle").pointerInput(id) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = id; offset = 0f },
                                onVerticalDrag = { change, delta ->
                                    change.consume()
                                    offset += delta
                                    val at = latest.indexOf(id)
                                    val steps = kotlin.math.round(offset / rowHeight).toInt()
                                    val target = (at + steps).coerceIn(0, latest.lastIndex)
                                    if (target != at) { offset -= (target - at) * rowHeight; onOrder(LyricsOrder.move(latest, at, target)) }
                                },
                                onDragEnd = { dragging = null; offset = 0f },
                                onDragCancel = { dragging = null; offset = 0f })
                        })
                }
                if (index != order.lastIndex) SettingsCellDivider()
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            KetTextButton("Rétablir l'ordre par défaut", { onOrder(LyricsOrder.default) }, enabled = order != LyricsOrder.default)
        }
    }
}
