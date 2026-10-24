package com.ketvoice.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

/**
 * Cache d'images Coil permanent : stocké dans filesDir (jamais purgé par le
 * bouton « vider le cache musical », ni par le nettoyage du cache système).
 * Plafond 2 Go, entrées LRU gérées par Coil.
 */
class KetvoiceApp : Application(), ImageLoaderFactory {

    companion object {
        @JvmStatic
        lateinit var instance: KetvoiceApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.ketvoice.app.data.AppText.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        val dir = File(filesDir, "images").apply { if (!exists()) mkdirs() }
        return ImageLoader.Builder(this)
            .components { add(com.ketvoice.app.data.PermanentImageInterceptor(this@KetvoiceApp)) }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(dir)
                    .maxSizeBytes(2L * 1024L * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
