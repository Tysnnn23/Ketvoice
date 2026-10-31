package com.ketvoice.app.ui.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

internal data class UpdateApkIdentity(val packageName: String, val versionCode: Long, val certificates: Set<String>)

internal object UpdateInstaller {
    private val downloadLock = Mutex()
    private val redirectHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

    fun identityMatches(candidate: UpdateApkIdentity, installed: UpdateApkIdentity): Boolean =
        candidate.packageName == installed.packageName && candidate.versionCode > installed.versionCode &&
            candidate.certificates.isNotEmpty() && candidate.certificates == installed.certificates

    fun payloadMatches(size: Long, expectedSize: Long, sha256: String, expectedSha256: String?): Boolean =
        size > 0 && expectedSize > 0 && size == expectedSize &&
            (expectedSha256 == null || sha256.equals(expectedSha256, ignoreCase = true))

    private fun open(url: String): HttpURLConnection {
        require(url.startsWith(UpdateFeed.DOWNLOAD_PREFIX)) { "Invalid update URL" }
        var next = URL(url)
        repeat(6) {
            require(next.protocol == "https" && next.host in redirectHosts) { "Invalid update redirect" }
            val connection = (next.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "Ketvoice-updater")
            }
            try {
                when (connection.responseCode) {
                    200 -> return connection
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect")
                        next = URL(next, location)
                    }
                    else -> throw IOException("Download HTTP ${connection.responseCode}")
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw IOException("Too many update redirects")
    }

    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit,
        connectionFactory: (String) -> HttpURLConnection = ::open,
        validate: (Context, File, UpdateInfo) -> Unit = ::verifyArchive): File =
        withContext(Dispatchers.IO) { downloadLock.withLock {
            require(UpdateFeed.validTag(info.tag) && info.sizeBytes > 0) { "Invalid release metadata" }
            val directory = File(context.cacheDir, "updates").apply { check(mkdirs() || isDirectory) }
            val target = File(directory, "ketvoice-${info.versionLabel()}.apk")
            // Keep an .apk suffix for Android's archive parser, but never expose
            // this staging file to the package installer before verification.
            val partial = File(directory, "ketvoice-${info.versionLabel()}.part.apk")
            val connection = connectionFactory(info.apkUrl)
            try {
                val announced = connection.contentLengthLong
                if (announced > 0 && announced != info.sizeBytes) throw IOException("Unexpected APK size")
                var size = 0L
                var percent = -1
                val digest = MessageDigest.getInstance("SHA-256")
                connection.inputStream.use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        if (size > info.sizeBytes) throw IOException("Unexpected APK size")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        val nextPercent = (size * 100 / info.sizeBytes).toInt()
                        if (nextPercent != percent) {
                            percent = nextPercent
                            withContext(Dispatchers.Main) { onProgress((size.toFloat() / info.sizeBytes).coerceIn(0f, 1f)) }
                        }
                    }
                } }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!payloadMatches(size, info.sizeBytes, hash, info.sha256)) throw IOException("APK integrity check failed")
                validate(context, partial, info)
                if (target.exists() && !target.delete()) throw IOException("Cannot replace update file")
                if (!partial.renameTo(target)) throw IOException("Cannot finish update download")
                withContext(Dispatchers.Main) { onProgress(1f) }
                target
            } finally {
                connection.disconnect()
                partial.delete()
            }
        } }

    private fun verifyArchive(context: Context, file: File, info: UpdateInfo) {
        require(file.isFile && file.canonicalFile.parentFile == File(context.cacheDir, "updates").canonicalFile) {
            "Invalid update file"
        }
        require(file.length() == info.sizeBytes) { "Incomplete APK" }
        info.sha256?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)) { "APK integrity check failed" }
        }
        ZipFile(file).use { zip ->
            require(listOf("AndroidManifest.xml", "classes.dex", "resources.arsc").all { zip.getEntry(it) != null }) { "Invalid APK" }
        }
        val pm = context.packageManager
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw IOException("Invalid APK")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fun identity(pkg: android.content.pm.PackageInfo) = UpdateApkIdentity(pkg.packageName, pkg.longVersionCode,
            pkg.signingInfo?.apkContentsSigners.orEmpty().map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
            }.toSet())
        require(identityMatches(identity(candidate), identity(installed))) { "APK package, version or signature mismatch" }
        require(candidate.versionName == info.versionLabel()) { "APK release version mismatch" }
        require((candidate.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "Unsupported Android version" }
    }

    /** False means permission is required; launch errors are never reported as success. */
    suspend fun install(context: Context, file: File, info: UpdateInfo): Boolean {
        withContext(Dispatchers.IO) { verifyArchive(context, file, info) }
        return withContext(Dispatchers.Main) {
            if (!context.packageManager.canRequestPackageInstalls()) return@withContext false
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("Ketvoice update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }
    }
}
