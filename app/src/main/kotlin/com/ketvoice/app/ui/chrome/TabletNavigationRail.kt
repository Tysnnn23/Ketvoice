package com.ketvoice.app.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.AccountAvatar
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.quest.questPointer

@Composable
fun TabletNavigationRail(modifier: Modifier = Modifier) {
    val layout = LocalAdaptiveLayout.current
    val theme = LocalTheme.current
    Column(modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding().padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        AccountAvatar("K", 42.dp, Modifier.padding(vertical = 18.dp).clickable { AppUi.showAccount.value = true })
        Spacer(Modifier.height(16.dp))
        GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(28.dp),
            GlassParams.pill(dark = theme.isDark), enabled = AppSettings.liquidGlass.value) {
            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val items = listOf(
                    Triple(Icons.Filled.Home, "Accueil", AppUi.HOME),
                    Triple(Icons.Filled.GridView, "Explorer", AppUi.EXPLORER),
                    Triple(Icons.Filled.LibraryMusic, "Bibliothèque", AppUi.LIBRARY),
                    Triple(Icons.Filled.Search, "Rechercher", -1)
                )
                items.forEach { (icon, label, tab) ->
                    val selected = if (tab == -1) AppUi.overlay.value == AppUi.SEARCH
                        else AppUi.tab.value == tab && AppUi.overlay.value == AppUi.NONE
                    val itemModifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                        .questPointer()
                        .background(if (selected) theme.onBackground.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable {
                            if (tab == -1) AppUi.openSearch() else AppUi.selectTab(tab)
                        }.padding(horizontal = 5.dp, vertical = 14.dp)
                    if (layout.expandedRail) Row(itemModifier, verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = if (selected) theme.accent else theme.secondary, modifier = Modifier.size(24.dp))
                        Text(label, color = theme.onBackground, fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
                    } else Column(itemModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, null, tint = if (selected) theme.accent else theme.secondary, modifier = Modifier.size(24.dp))
                        Text(label, color = theme.onBackground, fontSize = 10.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.Settings, "Réglages", tint = theme.secondary,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(20.dp))
                .questPointer()
                .clickable { AppUi.openSettings() }.padding(12.dp))
    }
}
