package com.ketvoice.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.glass.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Text

@Composable
internal fun GlassAppearancePreview(onClose: () -> Unit) {
    var choice by remember { mutableIntStateOf(AppSettings.glassStyle.value) }
    val theme = LocalTheme.current
    val state by Player.state.collectAsState()
    val covers by ArtworkRepo.covers.collectAsState()
    val cover = state.current?.let { covers["t:${it.id}"] ?: it.coverUrl }
    val backdrop = rememberGlassBackdrop(theme.background)
    AlertDialog(onDismissRequest = onClose, title = { Text("Aperçu du verre") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(20.dp))) {
                    BackdropCapture(backdrop, Modifier.matchParentSize()) {
                        AsyncImage(cover ?: com.ketvoice.app.R.drawable.logo_main, null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                        GlassPanel(Modifier.align(Alignment.BottomCenter).padding(14.dp).fillMaxWidth(),
                            RoundedCornerShape(28.dp), GlassParams.pill(dark = theme.isDark), styleOverride = choice) {
                            Text("Lecture · Aléatoire", color = theme.onBackground,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp))
                        }
                    }
                }
                Text(if (cover != null) "Aperçu sur la pochette en cours" else "Aperçu sur le logo Ketvoice", color = theme.secondary)
                listOf("Clair", "Dépoli", "Opaque").forEachIndexed { index, label ->
                    TextButton(onClick = { choice = index }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (choice == index) "✓ " else "") + com.ketvoice.app.data.AppText.t(label), localize = false, color = theme.onBackground)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { AppSettings.glassStyle.value = choice; SettingsStore.save(); onClose() }) { Text("Appliquer") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Annuler") } })
}
