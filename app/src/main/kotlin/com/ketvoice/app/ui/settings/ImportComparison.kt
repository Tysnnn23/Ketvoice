package com.ketvoice.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.player.formatSecs
import java.io.File

@Composable
internal fun ImportComparison(current: Track?, audio: File?, cover: File?, filename: String?, duration: Long) {
    val th=LocalTheme.current
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        if(current!=null)MediaCard("Actuel",current.coverUrl,null,current.durationMs,false,Modifier.weight(1f))
        MediaCard(if(current==null)"À publier" else "Remplacement",cover ?: current?.coverUrl,filename,audio?.let{duration} ?: current?.durationMs ?: 0L,
            audio!=null||cover!=null,Modifier.weight(1f))
    }
    if(audio!=null)Text("${audio.length()/1024} Ko · ${audio.extension.uppercase()}",localize=false,color=th.secondary,fontSize=12.sp)
    if(audio==null&&cover==null)Text("Aucun changement de fichier sélectionné",color=th.secondary,fontSize=12.sp)
}

@Composable
private fun MediaCard(label: String, image: Any?, filename: String?, duration: Long, changed: Boolean, modifier: Modifier) {
    val th=LocalTheme.current
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(th.card)
        .border(.7.dp,if(changed)th.accent.copy(alpha=.6f) else th.hairline,RoundedCornerShape(18.dp)).padding(12.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(label,color=th.secondary,fontSize=12.sp)
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(th.hairline),contentAlignment=Alignment.Center){
            if(image!=null)AsyncImage(image,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            else Text("Pochette",color=th.secondary,fontSize=12.sp)
        }
        if(filename!=null)Text(filename,localize=false,color=th.onBackground,fontSize=13.sp,maxLines=2)
        Text(if(changed)"Fichier sélectionné" else if(image!=null)"Conservé" else "En attente de fichier",color=if(changed)th.accent else th.secondary,fontSize=12.sp)
        if(duration>0)Text(formatSecs(duration),localize=false,color=th.secondary,fontSize=12.sp)
    }
}
