package com.ketvoice.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The back button of iOS 27 (Ket 27): a 44 dp circle of glass with a chevron. */
@Composable
fun GlassBackButton(onBack: () -> Unit) {
    val theme = com.ketvoice.app.theme.LocalTheme.current
    LiquidGlassButton(onClick = onBack, modifier = Modifier.size(44.dp), shape = CircleShape) {
        Icon(KetIcons.ChevronRight, "Retour", tint = theme.onBackground, modifier = Modifier.size(20.dp).turnedBack())
    }
}
