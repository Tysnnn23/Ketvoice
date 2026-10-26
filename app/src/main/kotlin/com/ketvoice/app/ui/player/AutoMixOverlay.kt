package com.ketvoice.app.ui.player

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.player.AutoMix
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.delay

@Composable
fun AutoMixOverlay(modifier: Modifier = Modifier) {
    if (!AppSettings.automix.value) return
    val transition by AutoMix.transition.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(transition?.id) {
        while (transition != null) {
            now = SystemClock.elapsedRealtime()
            delay(50)
        }
    }
    Column(modifier.statusBarsPadding().padding(top = 15.dp, end = 18.dp)
        .width(transition?.let { 184.dp } ?: 88.dp)
        .clip(RoundedCornerShape(18.dp))
        .background(Color(0xE90F1119)).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("AutoMix", color = Color(0xFFD5C5FF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        transition?.let { mix ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Cover(mix.fromTitle, mix.fromCover, size = 36.dp, radius = 7.dp)
                Text("→", color = Color.White.copy(alpha = .64f), localize = false)
                Cover(mix.toTitle, mix.toCover, size = 36.dp, radius = 7.dp)
            }
            Text(mix.toTitle, color = Color.White, fontSize = 10.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
            val progress = ((now - mix.startedAt).toFloat() / mix.fadeMs.coerceAtLeast(1)).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()
                .padding(top = 6.dp).height(2.dp).clip(RoundedCornerShape(2.dp)),
                color = Color(0xFFD5C5FF), trackColor = Color.White.copy(alpha = .18f))
        }
    }
}
