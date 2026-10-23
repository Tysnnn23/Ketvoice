package com.ketvoice.app.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi

@Composable
fun EmptyState(title: String, description: String, action: String, modifier: Modifier = Modifier,
               onAction: () -> Unit) {
    val theme = LocalTheme.current
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.MusicNote, null, tint = theme.secondary, modifier = Modifier.size(28.dp))
        Text(title, color = theme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(description, color = theme.secondary, fontSize = if (BuildConfig.IS_QUEST) 15.sp else 14.sp,
            modifier = Modifier.widthIn(max = 460.dp))
        TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(action, color = theme.accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

fun openTrackSearch() {
    AppUi.showPlayer.value = false
    AppUi.showLyrics.value = false
    AppUi.searchSeed.value = ""
    AppUi.overlay.value = AppUi.SEARCH
}
