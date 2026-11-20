package com.ketvoice.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.GlassSlider
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text

/**
 * « Transparence du verre » (Settings → Appearance → Glass): a live preview of the glass over the
 * cover playing (the Ketvoice logo when nothing plays) above a stepped Ket 27 slider, from very
 * clear glass to tinted, more legible glass, like the Liquid Glass slider of iOS 27. The whole
 * app follows the slider while it moves (every glass surface reads [GlassClarity]); the level is
 * saved when the thumb is released.
 */
@Composable
internal fun GlassTransparencyCard(modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val state by Player.state.collectAsState()
    val covers by ArtworkRepo.covers.collectAsState()
    val cover = state.current?.let { com.ketvoice.app.data.MediaOverrides.cover(it, covers["t:${it.id}"] ?: it.coverUrl) }
    val backdrop = rememberGlassBackdrop(theme.background)
    val level = GlassClarity.sanitize(AppSettings.glassIntensity.value)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp)).testTag("glass-preview")) {
            BackdropCapture(backdrop, Modifier.matchParentSize()) {
                AsyncImage(cover ?: com.ketvoice.app.R.drawable.logo_main, null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                GlassPanel(Modifier.align(Alignment.TopEnd).padding(12.dp).size(44.dp), CircleShape,
                    GlassParams.circle(dark = theme.isDark)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(KetIcons.Shuffle, null, tint = LocalTheme.current.onBackground, modifier = Modifier.size(20.dp))
                    }
                }
                GlassPanel(Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().testTag("glass-preview-sample"),
                    RoundedCornerShape(28.dp), GlassParams.pill(dark = theme.isDark)) {
                    val ink = LocalTheme.current.onBackground
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(KetIcons.Play, null, tint = ink, modifier = Modifier.size(20.dp))
                        Text("Lecture · Aléatoire", color = ink, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        Icon(KetIcons.Shuffle, null, tint = ink, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Text(if (cover != null) "Aperçu sur la pochette en cours" else "Aperçu sur le logo Ketvoice", color = theme.secondary,
            style = Ket27.Type.footnote)
        // 7.5: one continuous intensity, from very clear to opaque (it replaces the steps of 6.0 and Clair / Teinté of 7.1).
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Intensité du verre", color = theme.onBackground, style = Ket27.Type.body, modifier = Modifier.weight(1f))
            Text(GlassClarity.label(level), color = theme.secondary, style = Ket27.Type.body,
                modifier = Modifier.testTag("glass-tint-level"))
        }
        val sliderLabel = AppText.t("Intensité du verre")
        val levelLabel = AppText.t(GlassClarity.label(level))
        GlassSlider(value = level,
            onValueChange = { AppSettings.glassIntensity.value = GlassClarity.sanitize(it) },
            onValueChangeFinished = { SettingsStore.save() },
            valueRange = GlassClarity.MIN..GlassClarity.MAX,
            steps = GlassClarity.TICKS,
            modifier = Modifier.testTag("glass-tint-slider").semantics {
                contentDescription = sliderLabel
                stateDescription = levelLabel
            })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Transparent", color = theme.secondary, style = Ket27.Type.footnote)
            Text("Teinté", color = theme.secondary, style = Ket27.Type.footnote)
        }
        if (com.ketvoice.app.glass.HighContrastText.on.value && level < GlassClarity.TINTED)
            Text("Le texte à contraste élevé d'Android garde le verre teinté.", color = theme.secondary, style = Ket27.Type.footnote)
        Text("Plus le verre est teinté, plus le texte posé dessus est lisible.", color = theme.secondary,
            style = Ket27.Type.footnote)
    }
    SettingsCellDivider()
}
