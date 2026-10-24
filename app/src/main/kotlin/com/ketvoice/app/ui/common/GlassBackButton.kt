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

/** Bouton retour en verre liquide, réutilisé par tous les sous-écrans. */
@Composable
fun GlassBackButton(onBack: () -> Unit) {
    GlassPanel(
        modifier = Modifier.size(52.dp),
        shape = CircleShape,
        params = GlassParams.circle(dark = false),
        enabled = AppSettings.liquidGlass.value,
        // Posé dans le contenu capturé (overlay screens) : verre statique pour
        // éviter la boucle drawBackdrop → crash RenderThread.
        captured = true
    ) {
        Box(Modifier.size(52.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", modifier = Modifier.size(26.dp))
        }
    }
}
