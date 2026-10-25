package com.ketvoice.app.ui.sub

import androidx.compose.runtime.Composable
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.player.FullPlayerScreen
import com.ketvoice.app.ui.player.PlayerPage

@Composable
fun QueueScreen() {
    FullPlayerScreen(initialPage = PlayerPage.QUEUE, onDismiss = {
        AppUi.overlay.value = AppUi.NONE
        AppUi.showLyrics.value = false
        AppUi.showPlayer.value = false
    })
}
