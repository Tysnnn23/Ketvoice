package com.ketvoice.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.Text

@Composable
internal fun HomeEditorial(tracks: List<Track>) {
    val track=tracks.firstOrNull() ?: return
    val th=LocalTheme.current
    Column(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=6.dp)) {
        Text("À l'affiche",color=th.secondary,fontSize=12.sp,fontWeight=FontWeight.SemiBold,
            modifier=Modifier.padding(bottom=10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(th.card)
            .border(.7.dp,th.hairline,RoundedCornerShape(22.dp)).clickable{Player.playQueue(tracks)}
            .padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Box(Modifier.width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(th.accent))
                Text(track.title,localize=false,color=th.onBackground,fontSize=20.sp,fontWeight=FontWeight.Bold,maxLines=3)
                Text(track.artist,localize=false,color=th.secondary,fontSize=13.sp,maxLines=2)
                Text("Écouter",color=th.accent,fontSize=14.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(vertical=8.dp))
            }
            Cover(track.title,ArtworkRepo.coverForTrack(track),112.dp,16.dp)
        }
    }
}
