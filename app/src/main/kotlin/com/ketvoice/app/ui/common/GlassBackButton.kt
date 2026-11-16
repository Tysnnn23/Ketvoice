package com.ketvoice.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GlassBackButton(onBack: () -> Unit) {
    val theme = com.ketvoice.app.theme.LocalTheme.current
    // Ket 27: the back button of iOS 27 — a 44 dp circle of glass with a chevron.
    if (com.ketvoice.app.theme.Ket27.on) {
        LiquidGlassButton(onClick = onBack, modifier = Modifier.size(44.dp), shape = CircleShape) {
            Icon(KetIcons.ChevronRight, "Retour", tint = theme.onBackground, modifier = Modifier.size(20.dp).turnedBack())
        }
        return
    }
    LiquidGlassButton(
        onClick = onBack,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
    ) {
        Icon(KetIcons.ArrowLeft, "Retour", tint = theme.onBackground, modifier = Modifier.size(24.dp))
    }
}
