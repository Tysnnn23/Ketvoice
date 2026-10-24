package com.ketvoice.app.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.ui.common.Text

/** Original import artwork, not an official Spotify logo or an audio preview. */
@Composable
fun PlaylistArtwork(playlist: LocalPlaylist?, label: String, modifier: Modifier = Modifier) {
    val imported=playlist==null||playlist.spotifyUrl!=null
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(
        if(imported) listOf(Color(0xFF113E31),Color(0xFF081C20)) else listOf(Color(0xFF514479),Color(0xFF151C2C))))) {
        val cover=playlist?.coverUrl
        if(cover!=null)AsyncImage(cover,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop,alpha=.52f)
        Canvas(Modifier.fillMaxSize()) {
            val tint=if(imported)Color(0xFF80E0BA) else Color(0xFFCEC1FC)
            repeat(4){i->drawCircle(tint.copy(alpha=.16f),size.minDimension*(.22f+i*.11f),
                Offset(size.width*.82f,size.height*.22f),style=Stroke(1.dp.toPx()))}
            drawLine(tint.copy(alpha=.7f),Offset(size.width*.12f,size.height*.22f),Offset(size.width*.12f,size.height*.55f),3.dp.toPx())
        }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(if(imported)"SPOTIFY" else "KETVOICE",localize=false,color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold)
            Text(if(imported)"Import" else label.take(1).uppercase(),localize=imported,color=Color.White,fontSize=19.sp,fontWeight=FontWeight.Bold,maxLines=1)
        }
    }
}
