package android.webkit

/**
 * On Android the YouTube Music sign-in runs in a WebView whose cookies are cleared on sign-out.
 * Ketvoice for Windows signs in through the system browser (loopback, Phase 4) and keeps no
 * browser cookie store, so there is nothing to clear: these calls only report completion.
 */
class CookieManager private constructor() {
    fun removeAllCookies(callback: ((Boolean) -> Unit)?) { callback?.invoke(true) }
    fun flush() = Unit

    companion object {
        private val instance = CookieManager()
        @JvmStatic fun getInstance(): CookieManager = instance
    }
}
