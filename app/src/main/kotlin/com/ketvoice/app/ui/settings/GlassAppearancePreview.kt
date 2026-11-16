package com.ketvoice.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
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
    val initialTint = remember { GlassTuning.sanitize(AppSettings.glassTint.value) }
    val theme = LocalTheme.current
    val state by Player.state.collectAsState()
    val covers by ArtworkRepo.covers.collectAsState()
    val cover = state.current?.let { com.ketvoice.app.data.MediaOverrides.cover(it, covers["t:${it.id}"] ?: it.coverUrl) }
    val backdrop = rememberGlassBackdrop(theme.background)
    fun cancel() {
        AppSettings.glassTint.value = initialTint
        SettingsStore.save()
        onClose()
    }
    SettingsSheet(onDismiss = ::cancel) {
        // Annuler / Appliquer stay pinned under the scrollable preview, always reachable.
        Column(Modifier.fillMaxWidth().heightIn(max = com.ketvoice.app.ui.common.sheetMaxHeight())) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsSheetHeader("Aperçu du verre", "Régler la transparence du verre avant d'appliquer", ::cancel)
            Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(20.dp))) {
                BackdropCapture(backdrop, Modifier.matchParentSize()) {
                    AsyncImage(cover ?: com.ketvoice.app.R.drawable.logo_main, null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                    GlassPanel(Modifier.align(Alignment.BottomCenter).padding(14.dp).fillMaxWidth().testTag("glass-preview-sample"),
                        RoundedCornerShape(28.dp), GlassParams.pill(dark = theme.isDark)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(KetIcons.Play, null, tint = theme.onBackground, modifier = Modifier.size(20.dp))
                            Text("Lecture · Aléatoire", color = theme.onBackground, fontSize = 14.sp,
                                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                            Icon(KetIcons.Shuffle, null, tint = theme.onBackground, modifier = Modifier.size(20.dp))
                        }
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
        }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End) {
                SettingsAction("Annuler", ::cancel)
                SettingsAction("Appliquer", { SettingsStore.save(); onClose() }, primary = true)
            }
        }
    }
}
