package com.ketvoice.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.glass.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.GlassSlider
import com.ketvoice.app.ui.common.Text

@Composable
internal fun GlassAppearancePreview(onClose: () -> Unit) {
    var choice by remember { mutableIntStateOf(AppSettings.glassStyle.value) }
    val initialTint = remember { GlassTuning.sanitize(AppSettings.glassTint.value) }
    val theme = LocalTheme.current
    val state by Player.state.collectAsState()
    val covers by ArtworkRepo.covers.collectAsState()
    val cover = state.current?.let { covers["t:${it.id}"] ?: it.coverUrl }
    val backdrop = rememberGlassBackdrop(theme.background)
    fun cancel() {
        AppSettings.glassTint.value = initialTint
        SettingsStore.save()
        onClose()
    }
    SettingsSheet(onDismiss = ::cancel) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp)
                .clip(RoundedCornerShape(50)).background(theme.secondary.copy(alpha = .4f)))
            Text("Aperçu du verre", color = theme.onBackground, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(20.dp))) {
                BackdropCapture(backdrop, Modifier.matchParentSize()) {
                    AsyncImage(cover ?: com.ketvoice.app.R.drawable.logo_main, null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                    GlassPanel(Modifier.align(Alignment.BottomCenter).padding(14.dp).fillMaxWidth().testTag("glass-preview-sample"),
                        RoundedCornerShape(28.dp), GlassParams.pill(dark = theme.isDark), styleOverride = choice) {
                        Text("Lecture · Aléatoire", color = theme.onBackground,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp))
                    }
                }
            }
            Text(if (cover != null) "Aperçu sur la pochette en cours" else "Aperçu sur le logo Ketvoice", color = theme.secondary, fontSize = 12.sp)
            Text("Transparence du verre", color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            val sliderLabel = AppText.t("Transparence du verre")
            GlassSlider(value = AppSettings.glassTint.value,
                onValueChange = { AppSettings.glassTint.value = GlassTuning.sanitize(it) },
                onValueChangeFinished = { SettingsStore.save() },
                modifier = Modifier.testTag("glass-tint-slider").semantics { contentDescription = sliderLabel })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Clair", color = theme.secondary, fontSize = 12.sp)
                Text("Teinté", color = theme.secondary, fontSize = 12.sp)
            }
            listOf("Clair", "Dépoli", "Opaque").forEachIndexed { index, label ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { choice = index }
                    .heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = theme.onBackground, modifier = Modifier.weight(1f))
                    if (choice == index) Text("✓", color = theme.accent, fontSize = 18.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SettingsAction("Annuler", ::cancel)
                SettingsAction("Appliquer", { AppSettings.glassStyle.value = choice; SettingsStore.save(); onClose() }, primary = true)
            }
        }
    }
}
