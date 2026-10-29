package com.ketvoice.app.data

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Musique locale : MediaStore (tout l'appareil) ou dossier choisi via SAF.
 *
 * - Dossier par défaut : scan MediaStore (permissions Android standard).
 * - Dossier choisi : arbre SAF récursif, formats audio courants, métadonnées
 *   intégrées via MediaMetadataRetriever, pochette embarquée extraite sur disque.
 * - Enrichissement : les titres sans artiste sont déduits du nom de fichier
 *   « Artiste - Titre » (déterministe), puis une correspondance en ligne n'est
 *   acceptée que si elle est CERTAINE — jamais de remplacement incertain des
 *   métadonnées originales.
 */
object LocalMusic {
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks = _tracks.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()
    private val _scanned = MutableStateFlow(false)
    val scanned = _scanned.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    private val _source = MutableStateFlow("")
    val source = _source.asStateFlow()

    val permission: String get() = if (android.os.Build.VERSION.SDK_INT >= 33)
        android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
    fun hasPermission(context: Context): Boolean = androidx.core.content.ContextCompat.checkSelfPermission(
        context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private const val MAX_FILES = 3000
    private const val MAX_DEPTH = 8

    /** Formats audio acceptés dans le dossier choisi. */
    private val audioExtensions = setOf(
        "mp3", "flac", "wav", "aac", "m4a", "m4b", "ogg", "oga", "opus", "wma", "aiff", "aif", "alac"
    )

    fun scan(context: Context) {
        if (_scanning.value) return
        val tree = AppSettings.localMusicFolder.value
        _scanning.value = true
        _failed.value = false
        CoroutineScope(Dispatchers.IO).launch {
            val list = mutableListOf<Track>()
            try {
                if (tree != null) scanTree(context, Uri.parse(tree), list)
                else scanMediaStore(context, list)
            } catch (_: Exception) {
                _failed.value = true
            }
            _tracks.value = list
            _scanning.value = false
            _scanned.value = true
            _source.value = if (tree != null) folderName().orEmpty() else "Appareil"
            if (list.isNotEmpty()) enrichMissing(context, list)
        }
    }

    /** Nom lisible du dossier choisi (dernier segment de l'arbre SAF). */
    fun folderName(): String? {
        val tree = AppSettings.localMusicFolder.value ?: return null
        return runCatching {
            DocumentsContract.getTreeDocumentId(Uri.parse(tree)).split(':').lastOrNull()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** À appeler quand l'utilisateur choisit un dossier : permission persistante. */
    fun acceptFolder(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        AppSettings.localMusicFolder.value = treeUri.toString()
        SettingsStore.save()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Revient au scan MediaStore de tout l'appareil. */
    fun clearFolder() {
        AppSettings.localMusicFolder.value = null
        SettingsStore.save()
    }

    /** Scan par défaut : MediaStore, tous les fichiers audio de l'appareil. */
    private suspend fun scanMediaStore(context: Context, list: MutableList<Track>) {
        if (!hasPermission(context)) {
            _tracks.value = emptyList()
            _scanned.value = false
            _failed.value = false
            return
        }
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
            while (c.moveToNext() && list.size < MAX_FILES) {
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
    }

    /** Scan du dossier choisi : arbre SAF récursif + métadonnées intégrées. */
    private fun scanTree(context: Context, treeUri: Uri, list: MutableList<Track>) {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return
        val coversDir = File(context.filesDir, "local_covers").apply { if (!exists()) mkdirs() }
        walkTree(context, treeUri, rootDocId, list, coversDir, depth = 0)
        list.sortBy { it.title.lowercase(java.util.Locale.ROOT) }
    }

    private fun walkTree(context: Context, treeUri: Uri, dirDocId: String,
                         list: MutableList<Track>, coversDir: File, depth: Int) {
        if (depth > MAX_DEPTH || list.size >= MAX_FILES) return
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        }.getOrNull() ?: return
        val children = runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ), null, null, null
            ) ?: return
        }.getOrNull() ?: return
        val pendingDirs = mutableListOf<String>()
        children.use { c ->
            val idC = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameC = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeC = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext() && list.size < MAX_FILES) {
                val docId = c.getString(idC) ?: continue
                val name = c.getString(nameC) ?: continue
                val mime = c.getString(mimeC).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    pendingDirs += docId
                    continue
                }
                val ext = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                if (ext !in audioExtensions) continue
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                list.add(trackFromDocument(context, docUri, name, ext, coversDir))
            }
        }
        pendingDirs.forEach { walkTree(context, treeUri, it, list, coversDir, depth + 1) }
    }

    private fun trackFromDocument(context: Context, uri: Uri, name: String,
                                  ext: String, coversDir: File): Track {
        val stem = name.removeSuffix(".$ext")
        var title = stem
        var artist = "Artiste inconnu"
        var album = ""
        var durationMs = 0L
        var coverUrl: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val rawDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            // Métadonnées intégrées prioritaires ; sinon déduction déterministe
            // du nom de fichier « Artiste - Titre » (jamais d'invention).
            if (!rawTitle.isNullOrBlank()) title = rawTitle
            if (!rawArtist.isNullOrBlank()) artist = rawArtist
            if (!rawAlbum.isNullOrBlank()) album = rawAlbum
            durationMs = rawDuration?.toLongOrNull() ?: 0L
            val art = retriever.embeddedPicture
            if (art != null && art.size > 1024) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(art, 0, art.size, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    val file = File(coversDir, "${uri.hashCode().toUInt()}.jpg")
                    if (!file.exists() || file.length() == 0L) {
                        runCatching { file.writeBytes(art) }
                    }
                    if (file.exists() && file.length() > 0) coverUrl = Uri.fromFile(file).toString()
                }
            }
        } catch (_: Exception) {
            // Fichier illisible en métadonnées : nom de fichier seul.
        } finally {
            runCatching { retriever.release() }
        }
        if (artist == "Artiste inconnu" && title == stem && stem.contains(" - ")) {
            // Artiste - Titre : découpage déterministe du nom de fichier.
            val parts = stem.split(" - ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                artist = parts[0].trim()
                title = parts[1].trim()
            }
        }
        return Track(
            id = "local:doc:${uri.hashCode()}",
            title = title.ifBlank { stem },
            artist = artist,
            album = album,
            durationMs = durationMs,
            streamUrl = uri.toString(),
            coverUrl = coverUrl
        )
    }

    /**
     * Enrichissement en ligne des titres locaux incomplets. Une correspondance
     * n'est retenue que si le titre trouvé est IDENTIQUE au titre attendu
     * (normalisation stricte) : on n'écrase jamais une métadonnée originale
     * par une hypothèse. Un cache par identifiant évite les recherches en boucle.
     */
    private fun enrichMissing(context: Context, tracks: List<Track>) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val todo = tracks.filter {
            it.artist == "Artiste inconnu" && !prefs.contains(it.id)
        }.take(80)
        if (todo.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            for (track in todo) {
                if (!AppSettings.localMusicEnrich.value) return@launch
                val candidate = runCatching {
                    MusicApi.searchYtMusic("${track.title} ${track.artist}".trim()).firstOrNull()
                }.getOrNull()
                val match = candidate?.takeIf { c ->
                    normalize(c.title) == normalize(track.title) &&
                        normalize(c.artist).contains(normalize(track.artist).substringBefore(','))
                }
                if (match != null && match.title.isNotBlank()) {
                    prefs.edit().putString(track.id, match.id).apply()
                    _tracks.value = _tracks.value.map { existing ->
                        if (existing.id != track.id) existing else existing.copy(
                            artist = match.artist.ifBlank { existing.artist },
                            album = match.album ?: existing.album,
                            coverUrl = existing.coverUrl ?: match.coverUrl
                        )
                    }
                } else {
                    // Correspondance incertaine : marqué pour ne pas re-chercher en boucle.
                    prefs.edit().putString(track.id, "").apply()
                }
            }
        }
    }

    private fun normalize(value: String): String = value.lowercase(java.util.Locale.ROOT)
        .replace(Regex("[\\p{Punct}\\s]+"), " ").trim()

    fun albumArtUri(albumId: Long): String =
        Uri.parse("content://media/external/audio/albumart/$albumId").toString()

    private const val PREFS = "ketvoice_local_music"
}
