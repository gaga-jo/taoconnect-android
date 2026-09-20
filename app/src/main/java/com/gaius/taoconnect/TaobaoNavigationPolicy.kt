package com.gaius.taoconnect

import java.net.URI
import java.util.Locale

/** Shared trust boundary for navigation, injection and translation requests. */
internal object TaobaoNavigationPolicy {
    const val HOME = "https://m.taobao.com/"
    const val TEST_ORIGIN = "https://appassets.androidplatform.net"
    val origins = setOf("https://taobao.com", "https://*.taobao.com")

    fun isTaobao(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        uri.scheme == "https" && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            (host == "taobao.com" || host.endsWith(".taobao.com"))
    }.getOrDefault(false)

    fun isTestPage(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme == "https" && uri.host == "appassets.androidplatform.net" &&
            uri.rawUserInfo == null && uri.port == -1 &&
            (uri.path ?: "").startsWith("/assets/qa/")
    }.getOrDefault(false)

    fun canLoad(url: String?, testMode: Boolean): Boolean =
        isTaobao(url) || (testMode && isTestPage(url))
}
