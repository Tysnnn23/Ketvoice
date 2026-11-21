package com.ketvoice.app.ui.common

import com.ketvoice.app.ui.motion.growSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme

/** The app's alert: Ket 27's glass card (see [Ket27Alert]). */
@Composable
fun AppAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null) =
    Ket27Alert(onDismissRequest, confirmButton, modifier, dismissButton, title, text)

/**
 * A plain button as iOS 27 writes one, in a page, an alert or a sheet: its label alone (17, the
 * app's colour — or [tint] — semibold when it is the one to press, red when it destroys something),
 * with a 44 dp target. In an alert, the whole capsule around it is pressed. It replaces Material's
 * text button everywhere. [label] is a French literal, translated at display.
 */
@Composable
fun KetTextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false,
                  destructive: Boolean = false, enabled: Boolean = true, localize: Boolean = true, tint: Color? = null) {
    val theme = LocalTheme.current
    val color = when {
        !enabled -> theme.secondary
        destructive -> DESTRUCTIVE
        else -> tint ?: theme.accent
    }
    Box(modifier.heightIn(min = 44.dp).growSource().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, localize = localize, color = color, style = Ket27.Type.body,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The red of iOS 27 for an action that destroys something. */
internal val DESTRUCTIVE = Color(0xFFFF3B30)
