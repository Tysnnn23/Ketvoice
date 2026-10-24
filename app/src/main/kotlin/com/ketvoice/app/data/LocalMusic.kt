package com.ketvoice.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Musique locale : scan MediaStore et lecture directe des fichiers de l'appareil.
 * Les pistes utilisent l'id "local:<mediaStoreId>" et un streamUrl "content://…"
 * qui est joué nativement par ExoPlayer/Media3.
 */
object LocalMusic {
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks = _tracks.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()
    private val _scanned = MutableStateFlow(false)
    val scanned = _scanned.asStateFlow()

    fun scan(context: Context) {
        if (_scanning.value) return
        _scanning.value = true
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val list = mutableListOf<Track>()
            try {
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.ALBUM_ID
                )
                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 30000"
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                )?.use { c ->
                    val idC = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val albumIdC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    while (c.moveToNext()) {
                        val id = c.getLong(idC)
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        list.add(
                            Track(
                                id = "local:$id",
                                title = c.getString(titleC) ?: "Sans titre",
                                artist = (c.getString(artistC))?.takeUnless { it == MediaStore.UNKNOWN_STRING } ?: "Artiste inconnu",
                                album = (c.getString(albumC))?.takeUnless { it == MediaStore.UNKNOWN_STRING } ?: "",
                                durationMs = c.getLong(durC),
                                streamUrl = uri.toString(),
                                coverUrl = albumArtUri(albumId = c.getLong(albumIdC))
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
            _tracks.value = list
            _scanning.value = false
            _scanned.value = true
        }
    }

    /** Pochette d'album via l'ALBUM_ID MediaStore (miniature système). */
    fun albumArtUri(albumId: Long): String =
        Uri.parse("content://media/external/audio/albumart/$albumId").toString()
}
