package com.ketvoice.app.ui.sub

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Speaker
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.T
import com.ketvoice.app.ui.AppUi

private val noRip: MutableInteractionSource = MutableInteractionSource()

/** Sous-écrans simples (Téléchargements, Musique locale, Replay, etc.) v1 : placeholders propres. */
@Composable
fun SimpleSubScreens(title: String, subtitle: String = "") {
    val th = LocalTheme.current
    Column(
        Modifier
            .fillMaxSize()
            .background(th.background)
    ) {
        Box(Modifier.padding(top = 48.dp, start = 14.dp, end = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, null,
                    tint = th.onBackground,
                    modifier = Modifier
                        .size(30.dp)
                        .clickable(interactionSource = noRip, indication = null) {
                            AppUi.closeOverlay()
                        }
                )
                Spacer(Modifier.width(12.dp))
                Text(title, style = T.hugeTitle, color = th.onBackground, fontSize = 30.sp)
            }
        }
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = T.sub, color = th.secondary, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Speaker, null, tint = th.secondary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Rien ici pour l'instant.\nBranchement des sources à l'étape des URLs.",
                    style = T.sub,
                    color = th.secondary,
                    modifier = Modifier.padding(4.dp),
                    lineHeight = 20.sp
                )
            }
        }
    }
}

/** Écrans ad-hoc mappés à l'overlay AppUi */
@Composable
fun SubScreensRouter() {
    when (AppUi.overlay.value) {
        AppUi.DOWNLOADS -> DownloadsScreen()
        AppUi.LOCAL_FILES -> LocalMusicScreen()
        AppUi.REPLAY -> ReplayScreen()
        else -> {}
    }
}
