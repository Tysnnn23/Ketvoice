package com.ketvoice.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.motion.Motion

/** The layout of an empty page, kept apart so it can be tested. */
internal object EmptyStateLook {
    /** The symbol's disc and the symbol in it (Quest's are a little larger for its pointer and distance). */
    fun discDp(quest: Boolean = BuildConfig.IS_QUEST): Float = if (quest) 72f else 64f
    fun symbolDp(quest: Boolean = BuildConfig.IS_QUEST): Float = if (quest) 34f else 30f
    /** Lines of explanation stay short enough to read at a glance, as on iOS. */
    const val MAX_TEXT_WIDTH_DP = 320f
    /** How far the content rises as it appears (none under reduced motion). */
    fun riseDp(reduced: Boolean): Float = if (reduced) 0f else 12f
}

/**
 * An empty page, as iOS 27 draws one ("content unavailable"): a symbol of what is missing on a soft
 * disc, a title, a short centred explanation, and the one action that fills the page. It rises into
 * place once as it appears (at once under reduced motion).
 */
@Composable
fun EmptyState(title: String, description: String, action: String, modifier: Modifier = Modifier,
               icon: ImageVector = Icons.Filled.MusicNote, onAction: () -> Unit) {
    val theme = LocalTheme.current
    val reduced = Motion.reduced
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) appear.animateTo(1f, com.ketvoice.app.ui.motion.Motion.Springs.APPEAR.spec()) }
    val quest = BuildConfig.IS_QUEST
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp).testTag("empty-state")
        .graphicsLayer {
            val p = appear.value
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * EmptyStateLook.riseDp(reduced).dp.toPx()
        },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(EmptyStateLook.discDp(quest).dp).clip(CircleShape)
            .background(theme.onBackground.copy(alpha = if (theme.isDark) .10f else .06f)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = theme.secondary, modifier = Modifier.size(EmptyStateLook.symbolDp(quest).dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, color = theme.onBackground, style = com.ketvoice.app.theme.Ket27.Type.title3,
            fontSize = if (quest) 21.sp else 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EmptyStateLook.MAX_TEXT_WIDTH_DP.dp).semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Text(description, color = theme.secondary, style = com.ketvoice.app.theme.Ket27.Type.subheadline,
            fontSize = if (quest) 16.sp else 15.sp,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = EmptyStateLook.MAX_TEXT_WIDTH_DP.dp))
        Spacer(Modifier.height(14.dp))
        KetTextButton(action, onAction, Modifier.heightIn(min = 48.dp), primary = true)
    }
}

fun openTrackSearch() {
    AppUi.showPlayer.value = false
    AppUi.showLyrics.value = false
    AppUi.searchSeed.value = ""
    AppUi.openSearch()
}
