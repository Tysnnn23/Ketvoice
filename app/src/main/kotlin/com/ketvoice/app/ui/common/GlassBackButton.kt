package com.ketvoice.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GlassBackButton(onBack: () -> Unit) {
    val theme = com.ketvoice.app.theme.LocalTheme.current
    LiquidGlassButton(
        onClick = onBack,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = theme.onBackground, modifier = Modifier.size(24.dp))
    }
}
