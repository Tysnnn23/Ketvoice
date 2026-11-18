package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.BackLink
import com.ketvoice.app.ui.common.Text

/** « Auto »: a title « Hors ligne intelligent » downloaded on its own (7.0), beside the format of Downloads. */
@Composable
internal fun AutoBadge() {
    val theme = LocalTheme.current
    Text("Auto", color = theme.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(theme.accent.copy(alpha = .14f))
            .padding(horizontal = 6.dp, vertical = 2.dp).testTag("download-auto"))
}

/** Downloads opened on the automatic downloads alone: the way back to all of them. */
@Composable
internal fun SmartOfflineCrumb(onAll: () -> Unit) {
    val theme = LocalTheme.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("downloads-auto-only")) {
        BackLink("Tous", onAll)
        Text("Téléchargés automatiquement", color = theme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
