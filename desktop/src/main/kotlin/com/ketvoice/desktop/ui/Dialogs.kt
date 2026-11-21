package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/** The system red, for what deletes something (iOS 27 / macOS destructive actions). */
val destructiveRed: Color @Composable get() = if (LocalKet.current.dark) Color(0xFFFF453A) else Color(0xFFFF3B30)

/**
 * A Ket 27 alert in the window: title and message (already translated), « Annuler » and the action. Escape or a click outside
 * cancels; nothing is chosen for the listener, the destructive action needs its own click.
 */
@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = true) {
    val ket = LocalKet.current
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true),
        onKeyEvent = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (ket.dark) .45f else .25f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss), contentAlignment = Alignment.Center) {
            Column(Modifier.width(300.dp).shadow(24.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(ket.raised)
                .clickable(remember { MutableInteractionSource() }, null) {}.padding(20.dp).testTag("confirm-dialog"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                KText(title, Ket27Desk.Type.headline, weight = FontWeight.SemiBold, translate = false, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.Text(message, color = ket.secondary, textAlign = TextAlign.Center,
                    style = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.footnote))
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DialogButton("Annuler", ket.label, Modifier.weight(1f).testTag("confirm-cancel"), onDismiss)
                    DialogButton(confirm, if (destructive) destructiveRed else ket.accent, Modifier.weight(1f).testTag("confirm-ok")) { onConfirm(); onDismiss() }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val ket = LocalKet.current
    Box(modifier.height(34.dp).clip(RoundedCornerShape(9.dp)).background(ket.fill).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        KText(label, Ket27Desk.Type.subheadline, color = color, weight = FontWeight.SemiBold)
    }
}
