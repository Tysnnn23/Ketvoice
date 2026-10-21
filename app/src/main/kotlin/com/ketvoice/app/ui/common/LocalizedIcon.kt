package com.ketvoice.app.ui.common

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.ketvoice.app.data.AppText

/** Keep accessibility labels in the same language as visible controls. */
@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    MaterialIcon(imageVector, contentDescription?.let(AppText::t), modifier, tint)
}
