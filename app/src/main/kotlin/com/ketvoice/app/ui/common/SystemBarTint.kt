package com.ketvoice.app.ui.common

import androidx.compose.runtime.mutableStateOf

/**
 * A page drawn under the status bar may ask for light status-bar icons while a picture sits there (7.4: the
 * artist page's photo). The root applies it only while that page is the one shown.
 */
object SystemBarTint {
    val lightIconsOnTop = mutableStateOf(false)
}
