package com.ketvoice.app.data

import android.content.Context
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Private sessions (YouTube cookie, publisher token) kept encrypted, the desktop counterpart of
 * the Android Keystore store. On Windows the value is sealed with DPAPI for the signed-in user
 * (CryptProtectData through JNA); on other systems (tests) with AES-GCM and a key file only the
 * user can read. Same API and preferences name as on Android.
 */
class SecureSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("private-sessions", Context.MODE_PRIVATE)
    private val keyFile = File(context.filesDir, "session.key")
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    fun put(name: String, value: String) {
        prefs.edit().putString(name, seal(value.toByteArray(Charsets.UTF_8))).apply()
    }

    fun get(name: String): String? = runCatching {
        val stored = prefs.getString(name, null) ?: return null
        String(open(stored), Charsets.UTF_8)
    }.getOrNull()

    fun remove(name: String) { prefs.edit().remove(name).apply() }

    private fun seal(plain: ByteArray): String {
        if (windows) return "dpapi:" + b64(com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(plain))
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return b64(iv) + ":" + b64(cipher.doFinal(plain))
    }

    private fun open(stored: String): ByteArray {
        if (stored.startsWith("dpapi:")) return com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(unb64(stored.removePrefix("dpapi:")))
        val (iv, data) = stored.split(':').let { it[0] to it[1] }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(iv))) }
        return cipher.doFinal(unb64(data))
    }

    @Synchronized private fun key(): SecretKeySpec {
        if (!keyFile.isFile) {
            keyFile.parentFile?.mkdirs()
            keyFile.writeBytes(ByteArray(32).also { SecureRandom().nextBytes(it) })
            keyFile.setReadable(false, false); keyFile.setReadable(true, true)
            keyFile.setWritable(false, false); keyFile.setWritable(true, true)
        }
        return SecretKeySpec(keyFile.readBytes(), "AES")
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String) = Base64.getDecoder().decode(text)
}
