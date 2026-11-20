package com.ketvoice.app.ui.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.util.Romanizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The pronunciation of every line (null where none is shown), worked out once per lyrics, off the main thread. */
@Composable
internal fun rememberRomanized(lines: List<MusicApi.LyricLine>, enabled: Boolean): State<List<String?>> =
    produceState(emptyList(), lines, enabled) {
        value = if (!enabled || lines.isEmpty()) emptyList()
            else withContext(Dispatchers.Default) { Romanizer.lines(lines.map { it.text }) }
    }

/** « Romanisation », at the top of the lyrics source menu: one global switch, saved, backed up and synced. */
@Composable
internal fun RomanizationOption() {
    val on = com.ketvoice.app.data.AppSettings.lyricsRomanization
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Romanisation", color = Color.White, style = com.ketvoice.app.theme.Ket27.Type.headline)
            Text("La prononciation sous chaque ligne : japonais (kana), coréen, chinois, cyrillique, grec…",
                color = Color.White.copy(alpha = .78f), style = com.ketvoice.app.theme.Ket27.Type.footnote)
        }
        com.ketvoice.app.ui.common.LiquidGlassSwitch(on.value, { on.value = it; com.ketvoice.app.data.SettingsStore.save() },
            Modifier.testTag("lyrics-romanization").semantics { contentDescription = com.ketvoice.app.data.AppText.t("Romanisation") })
    }
}

/**
 * Under the line, like the translation but smaller and dimmer; never blurred (6.1 rule). It takes the line's own
 * colour, so it follows the Cascade dimming of lines still to come.
 */
@Composable
internal fun RomanizedLine(text: String, lineColor: Color, fontScale: Float) {
    Text(
        text,
        localize = false,
        color = lineColor.copy(alpha = lineColor.alpha * .56f),
        fontSize = (17f * fontScale).sp,
        lineHeight = (22f * fontScale).sp,
        letterSpacing = (-.1).sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
    )
}
