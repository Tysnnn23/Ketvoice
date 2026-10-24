package com.ketvoice.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.*
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.Icon
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistScreen() {
    val rows by PlaylistStore.lists.collectAsState()
    val playlist=rows.firstOrNull{it.id==AppUi.playlistId.value}
    val th=LocalTheme.current
    val scope=rememberCoroutineScope()
    var rename by remember{mutableStateOf(false)}
    var deleting by remember{mutableStateOf(false)}
    var name by remember(playlist?.id){mutableStateOf(playlist?.name.orEmpty())}
    var editing by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    fun action(block:suspend ()->Unit){ if(busy)return;busy=true;error="";scope.launch{
        try{block()}catch(_:Exception){error="Impossible d'enregistrer la playlist"}finally{busy=false}
    }}
    Column(Modifier.fillMaxSize().background(th.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={AppUi.selectTab(AppUi.LIBRARY)}){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Retour",tint=th.onBackground)}
            Text("Playlist",color=th.onBackground)
        }
        if(playlist==null){EmptyState("Playlist introuvable","Cette collection n'est plus disponible sur cet appareil.","Bibliothèque"){AppUi.selectTab(AppUi.LIBRARY)};return@Column}
        val playable=playlist.entries.mapNotNull{it.track}
        Row(Modifier.padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            PlaylistArtwork(playlist,playlist.name,Modifier.size(100.dp))
            Column(Modifier.weight(1f).padding(start=16.dp)){
                Text(playlist.name,localize=false,color=th.onBackground,fontSize=23.sp,maxLines=3)
                Text("${playable.size} / ${playlist.entries.size} titres disponibles",color=th.secondary,fontSize=13.sp)
                if(playlist.spotifyUrl!=null)Text("Import d'un aperçu public Spotify",color=th.secondary,fontSize=12.sp)
            }
        }
        if(playlist.spotifyUrl!=null)Text("La liste Spotify peut être incomplète. Retrouvez manuellement les titres manquants en les sélectionnant.",color=th.secondary,fontSize=12.sp,modifier=Modifier.padding(horizontal=20.dp,vertical=6.dp))
        FlowRow(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            TextButton(enabled=playable.isNotEmpty(),onClick={Player.playQueue(playable)}){Text("Écouter",color=th.accent)}
            TextButton(enabled=!busy,onClick={editing=!editing}){Text(if(editing)"Terminer" else "Modifier l'ordre",color=th.accent)}
            TextButton(enabled=!busy,onClick={name=playlist.name;rename=true}){Text("Renommer",color=th.accent)}
            TextButton(enabled=!busy,onClick={deleting=true}){Text("Supprimer",color=th.secondary)}
        }
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(16.dp))
        if(playlist.entries.isEmpty()) EmptyState("Votre playlist est vide","Ouvrez le menu d'un titre et choisissez Ajouter à une playlist.","Rechercher des titres",onAction=::openTrackSearch)
        else LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(bottom=LocalAdaptiveLayout.current.bottomPaddingDp.dp)){
            itemsIndexed(playlist.entries,key={_,entry->entry.id}){index,entry->
                Row(Modifier.fillMaxWidth().clickable(enabled=!busy){
                    if(entry.track!=null) Player.playQueue(playable,playlist.entries.take(index).count{it.track!=null})
                    else {AppUi.playlistResolution.value=AppUi.PlaylistResolution(playlist.id,entry.id)
                        AppUi.searchSeed.value="${entry.title} ${entry.artist}";AppUi.overlay.intValue=AppUi.SEARCH}
                }.padding(horizontal=18.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                    Text("${index+1}",localize=false,color=th.secondary,fontSize=12.sp,modifier=Modifier.width(28.dp))
                    Column(Modifier.weight(1f)){
                        TrackTitle(entry.title,th.onBackground)
                        Text(entry.artist,localize=false,color=th.secondary,fontSize=12.sp,maxLines=1)
                        if(entry.track==null)Text("Non retrouvé · choisir une version",color=th.accent,fontSize=12.sp)
                    }
                    if(editing){
                        IconButton(enabled=!busy&&index>0,onClick={action{PlaylistStore.move(playlist.id,index,index-1)}}){Icon(Icons.Filled.KeyboardArrowUp,"Monter",tint=th.secondary)}
                        IconButton(enabled=!busy&&index<playlist.entries.lastIndex,onClick={action{PlaylistStore.move(playlist.id,index,index+1)}}){Icon(Icons.Filled.KeyboardArrowDown,"Descendre",tint=th.secondary)}
                    }
                    IconButton(enabled=!busy,onClick={action{PlaylistStore.remove(playlist.id,entry.id)}}){Icon(Icons.Filled.Close,"Retirer le titre",tint=th.secondary)}
                }
            }
        }
        if(rename)AppAlertDialog(onDismissRequest={if(!busy)rename=false},title={Text("Renommer la playlist")},
            text={OutlinedTextField(name,{name=it.take(80)},label={Text("Nom de la playlist")},singleLine=true)},
            confirmButton={TextButton(enabled=!busy&&name.isNotBlank(),onClick={action{PlaylistStore.rename(playlist.id,name);rename=false}}){Text("Enregistrer")}},
            dismissButton={TextButton(onClick={rename=false}){Text("Annuler")}})
        if(deleting)AppAlertDialog(onDismissRequest={if(!busy)deleting=false},title={Text("Supprimer cette playlist ?")},
            text={Text("Seule la playlist est supprimée. Vos titres et téléchargements sont conservés.")},
            confirmButton={TextButton(enabled=!busy,onClick={action{PlaylistStore.delete(playlist.id);AppUi.selectTab(AppUi.LIBRARY)}}){Text("Supprimer")}},
            dismissButton={TextButton(onClick={deleting=false}){Text("Annuler")}})
    }
}
