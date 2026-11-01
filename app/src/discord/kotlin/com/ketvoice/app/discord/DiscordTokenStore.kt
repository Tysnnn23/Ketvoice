package com.ketvoice.app.discord

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The SDK access token lasts seven days; the refresh token does not expire.
 * Only the refresh token is persisted, encrypted with an Android Keystore key.
 */
internal class DiscordTokenStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("ketvoice_discord", Context.MODE_PRIVATE)

    fun save(refreshToken: String) {
        if (refreshToken.isBlank()) return
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val payload = cipher.iv + cipher.doFinal(refreshToken.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(KEY, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
        }
    }

    fun load(): String? = runCatching {
        val encoded = prefs.getString(KEY, null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        if (payload.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, payload, 0, IV_BYTES)
        )
        String(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ketvoice.discord.refresh"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY = "refresh"
        const val IV_BYTES = 12
    }
}
