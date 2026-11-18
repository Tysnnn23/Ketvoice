package com.ketvoice.app.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
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
 * - Dossier par défaut : scan MediaStore (permissions Android standard), avec l'artiste de l'album, les numéros
 *   de disque et de piste, l'année, le genre, le drapeau compilation et le dossier de chaque fichier.
 * - Dossier choisi : arbre SAF récursif, formats audio courants, étiquettes intégrées (ID3, Vorbis, MP4) via
 *   MediaMetadataRetriever, pochette intégrée extraite sur disque, sinon `folder.jpg`/`cover.jpg` du dossier ;
 *   un `.lrc` du même nom donne les paroles.
 * - 7.0 « Musique locale pro » : les étiquettes sont nettoyées ([LocalTagNormalizer]), regroupées en vrais albums
 *   ([LocalLibrary]) ; le catalogue propose des corrections que l'auditeur accepte ([LocalCorrections]) — gardées
 *   dans l'app, jamais écrites dans ses fichiers sauf s'il choisit « Écrire dans le fichier » ([LocalFileWriter]).
 */
object LocalMusic {
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    /** The titles as the library shows them: the file's tags with the accepted corrections laid over. */
    val tracks = _tracks.asStateFlow()
    private val _tags = MutableStateFlow<Map<String, LocalTags>>(emptyMap())
    /** The cleaned-up (and corrected) tags of each title, by id. */
    internal val tags = _tags.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()
    private val _scanned = MutableStateFlow(false)
    val scanned = _scanned.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    private val _source = MutableStateFlow("")
    val source = _source.asStateFlow()
    private val _needsPermission = MutableStateFlow(false)
    /** True when the whole-device scan is waiting for the audio permission. */
    val needsPermission = _needsPermission.asStateFlow()

    /** What the files say, before corrections: the base the corrections are laid over. */
    private data class Scanned(val track: Track, val tags: LocalTags, val hasOwnCover: Boolean)
    @Volatile private var scannedFiles: List<Scanned> = emptyList()

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
    /** A folder's picture, in the order taggers and players look for it. */
    private val folderCovers = listOf("cover", "folder", "front", "album", "albumart", "albumartsmall")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    fun scan(context: Context) {
        if (_scanning.value) return
        val tree = AppSettings.localMusicFolder.value
        // A chosen folder carries its own grant; the whole device needs the audio permission.
        if (tree == null && !hasPermission(context)) {
            _needsPermission.value = true
            _tracks.value = emptyList()
            _scanned.value = false
            _failed.value = false
            return
        }
        _needsPermission.value = false
        _scanning.value = true
        _failed.value = false
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            LocalCorrections.init(app); LocalLyrics.init(app)
            val list = mutableListOf<Scanned>()
            try {
                if (tree != null) scanTree(app, Uri.parse(tree), list)
                else scanMediaStore(app, list)
            } catch (_: Exception) {
                _failed.value = true
            }
            scannedFiles = list
            if (list.isEmpty()) { _tracks.value = emptyList(); _tags.value = emptyMap() } else publish()
            _scanning.value = false
            _scanned.value = true
            _source.value = if (tree != null) folderName().orEmpty() else "Appareil"
            if (list.isNotEmpty() && AppSettings.localMusicEnrich.value) findCorrections()
        }
    }

    /** Lays the accepted corrections and borrowed covers over what the files say, and shows the result. */
    internal fun publish() {
        val files = scannedFiles
        if (files.isEmpty()) return
        val tags = HashMap<String, LocalTags>(files.size)
        _tracks.value = files.map { file ->
            val t = LocalCorrections.corrected(file.track.id, file.tags)
            tags[file.track.id] = t
            file.track.copy(title = t.title, artist = t.artist ?: "Artiste inconnu", album = t.album.orEmpty(),
                coverUrl = file.track.coverUrl ?: LocalCorrections.borrowedCover(file.track.id))
        }
        _tags.value = tags
    }

    /** Asks the catalogue about the files not asked yet; what it finds waits for the listener as proposals. */
    fun findCorrections() {
        val files = scannedFiles.map { it.track.id to LocalCorrections.corrected(it.track.id, it.tags) }
        if (files.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { LocalCorrections.findAll(files, { MusicApi.searchYtMusic(it) }) }
            publish()
        }
    }

    /** Fetches synced lyrics ahead for the local titles, with the app's providers. */
    fun findLyrics() {
        val list = _tracks.value
        CoroutineScope(Dispatchers.IO).launch { runCatching { LocalLyrics.fetchAll(list) } }
    }

    /** The address of a title's file, to write its tags (« Écrire dans le fichier »). */
    internal fun uriOf(id: String): Uri? = scannedFiles.firstOrNull { it.track.id == id }?.track?.streamUrl?.let(Uri::parse)

    /** Nom lisible du dossier choisi (dernier segment de l'arbre SAF). */
    fun folderName(): String? {
        val tree = AppSettings.localMusicFolder.value ?: return null
        return runCatching {
            DocumentsContract.getTreeDocumentId(Uri.parse(tree)).split(':').lastOrNull()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** À appeler quand l'utilisateur choisit un dossier : permission persistante (écriture aussi, si Android l'a donnée). */
    fun acceptFolder(context: Context, treeUri: Uri) {
        val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, read or write) }
            .onFailure { runCatching { context.contentResolver.takePersistableUriPermission(treeUri, read) } }
        AppSettings.localMusicFolder.value = treeUri.toString()
        SettingsStore.save()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Revient au scan MediaStore de tout l'appareil. */
    fun clearFolder() {
        AppSettings.localMusicFolder.value = null
        SettingsStore.save()
    }

    // ------------------------------------------------------------------ the whole device (MediaStore)

    private val mediaColumns = arrayOf(
        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.ALBUM_ARTIST, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.DISC_NUMBER,
        MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.GENRE, MediaStore.Audio.Media.COMPILATION,
        MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DATA)

    /** Scan par défaut : MediaStore, tous les fichiers audio de l'appareil. */
    private fun scanMediaStore(context: Context, list: MutableList<Scanned>) {
        if (!hasPermission(context)) {
            _tracks.value = emptyList()
            _scanned.value = false
            _failed.value = false
            return
        }
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 30000"
        val covers = HashMap<String, String?>()
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaColumns, selection, null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            while (c.moveToNext() && list.size < MAX_FILES) list += mediaRow(context, c, covers)
        }
    }

    private fun Cursor.text(column: String): String? = getColumnIndex(column).takeIf { it >= 0 }?.let { getString(it) }
        ?.takeUnless { it == MediaStore.UNKNOWN_STRING }

    private fun mediaRow(context: Context, c: Cursor, covers: HashMap<String, String?>): Scanned {
        val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val track = c.text(MediaStore.Audio.Media.TRACK)?.toIntOrNull()?.let(LocalTagNormalizer::mediaStoreTrack)
        val raw = RawTags(
            title = c.text(MediaStore.Audio.Media.TITLE), artist = c.text(MediaStore.Audio.Media.ARTIST),
            album = c.text(MediaStore.Audio.Media.ALBUM), albumArtist = c.text(MediaStore.Audio.Media.ALBUM_ARTIST),
            track = track?.first?.toString(), disc = c.text(MediaStore.Audio.Media.DISC_NUMBER) ?: track?.second?.toString(),
            year = c.text(MediaStore.Audio.Media.YEAR), genre = c.text(MediaStore.Audio.Media.GENRE),
            compilation = c.text(MediaStore.Audio.Media.COMPILATION),
            durationMs = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)),
            fileName = c.text(MediaStore.Audio.Media.DISPLAY_NAME).orEmpty(),
            folder = c.text(MediaStore.Audio.Media.RELATIVE_PATH).orEmpty())
        val tags = LocalTagNormalizer.normalize(raw)
        val albumId = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID))
        val cover = covers.getOrPut("$albumId\u0000${tags.folder}") { mediaCover(context, albumId, c.text(MediaStore.Audio.Media.DATA)) }
        return Scanned(Track(id = "local:$id", title = tags.title, artist = tags.artist ?: "Artiste inconnu",
            album = tags.album.orEmpty(), durationMs = tags.durationMs, streamUrl = uri.toString(), coverUrl = cover), tags, cover != null)
    }

    /** The cover of an album of the device: its embedded picture, else the folder's picture when it can be read. */
    private fun mediaCover(context: Context, albumId: Long, path: String?): String? {
        val art = albumArtUri(albumId)
        val embedded = runCatching { context.contentResolver.openFileDescriptor(Uri.parse(art), "r")?.use { true } }.getOrNull() == true
        if (embedded) return art
        val dir = path?.let { File(it).parentFile } ?: return null
        return folderCovers.firstNotNullOfOrNull { name -> imageExtensions.firstNotNullOfOrNull { ext ->
            File(dir, "$name.$ext").takeIf { runCatching { it.canRead() && it.length() > 1024 }.getOrDefault(false) } } }
            ?.let { Uri.fromFile(it).toString() }
    }

    // ------------------------------------------------------------------ a chosen folder (SAF)

    /** Scan du dossier choisi : arbre SAF récursif + métadonnées intégrées. */
    private fun scanTree(context: Context, treeUri: Uri, list: MutableList<Scanned>) {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return
        val coversDir = File(context.filesDir, "local_covers").apply { if (!exists()) mkdirs() }
        LocalLyrics.clearSidecars()
        walkTree(context, Walk(treeUri, list, coversDir), rootDocId, "", depth = 0)
        list.sortBy { it.track.title.lowercase(java.util.Locale.ROOT) }
    }

    private class Walk(val tree: Uri, val list: MutableList<Scanned>, val coversDir: File)
    private class Child(val docId: String, val name: String, val ext: String)

    private fun walkTree(context: Context, walk: Walk, dirDocId: String, path: String, depth: Int) {
        if (depth > MAX_DEPTH || walk.list.size >= MAX_FILES) return
        val children = listChildren(context, walk.tree, dirDocId) ?: return
        val (dirs, files) = children.partition { it.ext == DIR }
        val image = folderImage(files)?.let { DocumentsContract.buildDocumentUriUsingTree(walk.tree, it.docId).toString() }
        val lyrics = files.filter { it.ext == "lrc" }.associateBy { it.name.substringBeforeLast('.').lowercase() }
        for (file in files) {
            if (walk.list.size >= MAX_FILES) break
            if (file.ext !in audioExtensions) continue
            val docUri = DocumentsContract.buildDocumentUriUsingTree(walk.tree, file.docId)
            val scanned = trackFromDocument(context, docUri, file, path, walk.coversDir, image)
            lyrics[file.name.substringBeforeLast('.').lowercase()]?.let {
                LocalLyrics.noteSidecar(scanned.track.id, DocumentsContract.buildDocumentUriUsingTree(walk.tree, it.docId))
            }
            walk.list += scanned
        }
        dirs.forEach { walkTree(context, walk, it.docId, if (path.isEmpty()) it.name else "$path/${it.name}", depth + 1) }
    }

    private const val DIR = "\u0000dir"

    private fun listChildren(context: Context, tree: Uri, dirDocId: String): List<Child>? {
        val childrenUri = runCatching { DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirDocId) }.getOrNull() ?: return null
        val cursor = runCatching {
            context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
        }.getOrNull() ?: return null
        val out = ArrayList<Child>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                out += Child(docId, name, if (dir) DIR else name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT))
            }
        }
        return out
    }

    /** `cover.jpg`, `folder.jpg`, `front.png`… in that order. */
    private fun folderImage(files: List<Child>): Child? = folderCovers.firstNotNullOfOrNull { wanted ->
        files.firstOrNull { it.ext in imageExtensions && it.name.substringBeforeLast('.').lowercase() == wanted }
    }

    private fun trackFromDocument(context: Context, uri: Uri, file: Child, folder: String, coversDir: File, folderImage: String?): Scanned {
        val retriever = MediaMetadataRetriever()
        var raw = RawTags(fileName = file.name, folder = folder)
        var embedded: String? = null
        try {
            retriever.setDataSource(context, uri)
            raw = rawTags(retriever, file.name, folder)
            embedded = embeddedCover(retriever, uri, coversDir)
        } catch (_: Exception) {
            // Fichier illisible en métadonnées : nom de fichier seul.
        } finally {
            runCatching { retriever.release() }
        }
        val tags = LocalTagNormalizer.normalize(raw)
        val cover = embedded ?: folderImage
        return Scanned(Track(id = "local:doc:${uri.hashCode()}", title = tags.title, artist = tags.artist ?: "Artiste inconnu",
            album = tags.album.orEmpty(), durationMs = tags.durationMs, streamUrl = uri.toString(), coverUrl = cover), tags, cover != null)
    }

    /** ID3, Vorbis or MP4 tags as Android's own reader gives them. */
    private fun rawTags(r: MediaMetadataRetriever, name: String, folder: String) = RawTags(
        title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
        artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
        album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
        albumArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
        track = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
        disc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
        year = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
        genre = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
        compilation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPILATION),
        durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
        fileName = name, folder = folder)

    /** The picture inside the file, kept on disk once. */
    private fun embeddedCover(r: MediaMetadataRetriever, uri: Uri, coversDir: File): String? {
        val art = r.embeddedPicture ?: return null
        if (art.size <= 1024) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(art, 0, art.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val file = File(coversDir, "${uri.hashCode().toUInt()}.jpg")
        if (!file.exists() || file.length() == 0L) runCatching { file.writeBytes(art) }
        return if (file.exists() && file.length() > 0) Uri.fromFile(file).toString() else null
    }

    fun albumArtUri(albumId: Long): String =
        Uri.parse("content://media/external/audio/albumart/$albumId").toString()

    private const val PREFS = "ketvoice_local_music"
}
