package android.content.res

import java.io.FileNotFoundException
import java.io.InputStream

/** The app's assets/ folder, packaged on the desktop classpath under `assets/` (see build.gradle.kts). */
class AssetManager {
    fun open(fileName: String): InputStream =
        AssetManager::class.java.classLoader.getResourceAsStream("assets/$fileName")
            ?: throw FileNotFoundException("assets/$fileName")
}
