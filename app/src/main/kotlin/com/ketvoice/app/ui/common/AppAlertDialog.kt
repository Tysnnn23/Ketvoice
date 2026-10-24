package com.ketvoice.app.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.DesignChoices
import com.ketvoice.app.theme.LocalTheme

/** Shared, readable modal finish. The opt-out retains Material's previous surface. */
@Composable
fun AppAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null) {
    val th = LocalTheme.current
    val refined = DesignChoices.enabled("D5")
    val shape = RoundedCornerShape(if (refined) 26.dp else 28.dp)
    AlertDialog(onDismissRequest, confirmButton,
        modifier = modifier.then(if (refined) Modifier.border(.7.dp,th.hairline,shape) else Modifier),
        dismissButton = dismissButton, title = title, text = text, shape = shape,
        containerColor = if (refined) th.card.copy(alpha = .98f) else AlertDialogDefaults.containerColor,
        titleContentColor = th.onBackground, textContentColor = th.secondary,
        tonalElevation = if (refined) 0.dp else AlertDialogDefaults.TonalElevation)
}
