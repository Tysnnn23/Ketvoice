package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ketvoice.app.theme.LocalTheme

/** Quiet placeholders: no perpetual shader/allocation cost while a request waits. */
@Composable
internal fun CatalogSkeletonRow() {
    val color = LocalTheme.current.secondary.copy(alpha = .12f)
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(color))
        Column(Modifier.weight(1f).padding(start = 14.dp, top = 5.dp)) {
            Box(Modifier.fillMaxWidth(.7f).height(13.dp).clip(RoundedCornerShape(6.dp)).background(color))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(.36f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        }
    }
}
