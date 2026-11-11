package com.ketvoice.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Text

@Composable
internal fun PlayerPageSelector(page: PlayerPage, onPage: (PlayerPage) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    GlassPanel(modifier.height(48.dp).testTag("player-tabs"), RoundedCornerShape(20.dp), GlassParams.pill(dark = theme.isDark)) {
        Row(Modifier.fillMaxSize().padding(4.dp).selectableGroup()) {
            listOf(PlayerPage.ARTWORK to "Pochette", PlayerPage.LYRICS to "Paroles", PlayerPage.QUEUE to "File d'attente").forEach { (target, label) ->
                val selected = target == page
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp))
                    .background(if (selected) theme.onBackground.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected, role = Role.Tab) { onPage(target) }.padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) theme.onBackground else theme.secondary, fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
