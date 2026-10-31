package com.ketvoice.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.theme.LocalTheme

@Composable
internal fun CompactSearchField(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit,
                                modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    BasicTextField(query, { onQueryChange(it.replace('\r', ' ').replace('\n', ' ')) },
        modifier.widthIn(max = 640.dp).fillMaxWidth()
            .background(theme.onBackground.copy(alpha = if (theme.isDark) .075f else .055f), RoundedCornerShape(18.dp)),
        singleLine = true, textStyle = TextStyle(color = theme.onBackground, fontSize = 16.sp),
        cursorBrush = SolidColor(theme.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        decorationBox = { input ->
            Row(Modifier.heightIn(min = 52.dp).padding(start = 16.dp, end = if (query.isEmpty()) 16.dp else 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = theme.secondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Rechercher un titre ou un artiste…", color = theme.secondary,
                        fontSize = 16.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    input()
                }
                if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, "Effacer", tint = theme.secondary, modifier = Modifier.size(18.dp))
                }
            }
        })
}
