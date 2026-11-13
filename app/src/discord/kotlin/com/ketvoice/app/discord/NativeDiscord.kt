package com.ketvoice.app.discord

/**
 * JNI bridge to the owner-supplied Discord Social SDK. The native library is
 * only packaged when app/libs/discord/discord_partner_sdk.aar is present, so this
 * file lives in the optional `discord` source set.
 */
internal object NativeDiscord {
    init {
        System.loadLibrary("discordbridge")
    }

    external fun nativeCreate(appId: Long): Boolean
    external fun nativeReset(): Boolean
    external fun nativeDestroy()
    external fun nativeAuthorize(scopes: String?): Boolean
    external fun nativeExchange(code: String, verifier: String, redirectUri: String): Boolean
    external fun nativeSetToken(token: String): Boolean
    external fun nativeRestore(refreshToken: String): Boolean
    external fun nativeConnect()
    external fun nativeDisconnect()
    external fun nativeSetPresence(values: Array<String>, startEpochMs: Long): Boolean
    external fun nativeClearPresence()
    external fun nativeFetchUser()
    external fun nativeIsAuthenticated(): Boolean
    /** Delivers queued SDK callbacks on the calling (main) thread. Never call it under a native lock. */
    external fun nativeRunCallbacks()

    /** 0 disconnected, 1 connecting, 2 connected, 3 ready, 4 reconnecting, 5 disconnecting, 6 http wait. */
    @JvmStatic
    fun onStatus(status: Int) = DiscordControllerImpl.onStatus(status)

    @JvmStatic
    fun onAuth(ok: Boolean, message: String?, refreshToken: String?) =
        DiscordControllerImpl.onAuth(ok, message.orEmpty(), refreshToken.orEmpty())

    @JvmStatic
    fun onPresence(ok: Boolean, message: String?) =
        DiscordControllerImpl.onPresence(ok, message.orEmpty())

    @JvmStatic
    fun onLog(message: String?) = DiscordControllerImpl.onLog(message.orEmpty())

    @JvmStatic
    fun onUser(id: Long, username: String?, displayName: String?) =
        DiscordControllerImpl.onUser(id, username.orEmpty(), displayName.orEmpty())
}
