package com.ketvoice.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams

@Composable
fun GlassBackButton(onBack: () -> Unit) {
    val theme = com.ketvoice.app.theme.LocalTheme.current
    GlassPanel(
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        params = GlassParams.circle(dark = theme.isDark),
        enabled = AppSettings.liquidGlass.value,

        captured = true
    ) {
        Box(Modifier.size(48.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = theme.onBackground, modifier = Modifier.size(24.dp))
        }
    }
}
