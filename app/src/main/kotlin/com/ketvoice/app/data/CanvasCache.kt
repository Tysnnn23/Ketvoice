package com.ketvoice.app.data

import android.content.Context
import java.io.File

/**
 * The Canvas files kept on the device (`filesDir/canvas`), seen from Settings (6.2.3): what they use, split into what
 * downloaded titles depend on and the rest, the limit the listener chose, and « Vider le cache des Canvas ».
 * A Canvas a downloaded title depends on is never deleted here (its own file, or its album's).
 */
object CanvasCache {
    private fun dir(context: Context) = File(context.filesDir, "canvas")

    private fun kept(context: Context): (File) -> Boolean =
        DiskBudget.canvasKept(DiskBudget.downloadRefs(File(context.filesDir, "downloads"))) { album, artist ->
            CanvasRepo.albumFile(album, artist)
        }

    /** Reads the folder: call from a background thread. */
    fun usage(context: Context): CanvasCachePlan.Usage =
        CanvasCachePlan.usage(CanvasCachePlan.items(dir(context), kept(context)))

    /** Deletes what the limit no longer allows (least recently used first). Returns the bytes freed. */
    fun applyLimit(context: Context, mb: Int = AppSettings.canvasCacheMb.value): Long {
        val freed = DiskBudget.trim(dir(context), CanvasCachePlan.limitBytes(mb), protected = kept(context))
        if (freed > 0) CanvasRepo.dropMissing()
        return freed
    }

    /** Deletes every Canvas file no downloaded title depends on. Returns the bytes freed. */
    fun clear(context: Context): Long {
        val folder = dir(context)
        val doomed = CanvasCachePlan.toClear(CanvasCachePlan.items(folder, kept(context))).map { it.name }.toSet()
        var freed = 0L
        folder.listFiles()?.filter { it.isFile && it.name in doomed }?.forEach { file ->
            val size = file.length()
            if (file.delete()) freed += size
        }
        CanvasRepo.dropMissing()
        return freed
    }
}
