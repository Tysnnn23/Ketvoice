package com.ketvoice.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme

/**
 * A text field as iOS 27 draws one (Ket 27), in place of Material's outlined field: its name in a
 * footnote above, the text (17, Inter) in a rounded well of the page's ink, the cursor in the
 * app's colour. [label] is a French literal, translated at display and given to accessibility
 * services as the field's name. [modifier] applies to the field itself (its tag, its width).
 */
@Composable
fun KetTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
                 enabled: Boolean = true, singleLine: Boolean = true,
                 visualTransformation: VisualTransformation = VisualTransformation.None,
                 keyboardOptions: KeyboardOptions = KeyboardOptions.Default) {
    val theme = LocalTheme.current
    val name = AppText.t(label)
    Column(Modifier.fillMaxWidth()) {
        Text(name, localize = false, color = theme.secondary, style = Ket27.Type.footnote,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        BasicTextField(value, onValueChange, modifier.semantics { contentDescription = name }
            .clip(RoundedCornerShape(12.dp)).background(theme.onBackground.copy(alpha = if (enabled) .07f else .04f))
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 13.dp),
            enabled = enabled, singleLine = singleLine, visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
            textStyle = Ket27.Type.body.copy(color = theme.onBackground.copy(alpha = if (enabled) 1f else .5f), fontFamily = Ket27.font),
            cursorBrush = SolidColor(theme.accent))
    }
}
