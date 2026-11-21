package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebStorage
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Public client id used by the official Yandex Music Android OAuth flow. */
internal const val YANDEX_MUSIC_CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d"

private const val LOGIN_URL =
    "https://oauth.yandex.ru/authorize?response_type=token&client_id=$YANDEX_MUSIC_CLIENT_ID"

/**
 * The official Music client finishes the implicit flow on music.yandex.ru,
 * while authorization errors can remain on the OAuth host itself.
 */
private val TRUSTED_OAUTH_RESULT_HOSTS = setOf(
    "oauth.yandex.ru",
    "oauth.yandex.com",
    "music.yandex.ru",
)

internal sealed interface YandexOAuthResult {
    data class Token(val value: String) : YandexOAuthResult
    data class Error(val description: String) : YandexOAuthResult
}

/** Parses the implicit-flow fragment without ever sending it to another host. */
internal fun parseYandexOAuthRedirect(rawUrl: String?): YandexOAuthResult? {
    if (rawUrl.isNullOrBlank()) return null
    val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || host !in TRUSTED_OAUTH_RESULT_HOSTS) return null
    val parameters = sequenceOf(uri.rawFragment, uri.rawQuery)
        .filterNotNull()
        .flatMap { it.split('&').asSequence() }
        .mapNotNull { part ->
            val key = part.substringBefore('=', "")
            if (key.isEmpty()) return@mapNotNull null
            decode(key) to decode(part.substringAfter('=', ""))
        }
        .toMap()
    parameters["access_token"]?.takeIf { it.isNotBlank() }?.let {
        return YandexOAuthResult.Token(it)
    }
    parameters["error"]?.let { error ->
        return YandexOAuthResult.Error(parameters["error_description"] ?: error)
    }
    return null
}

private fun decode(value: String): String =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

/**
 * Removes Yandex's browser session: its cookies, and the page storage the web
 * player keeps beside them.
 *
 * Cookies are cleared one by one, so Google's, VK's and Discord's survive. Page
 * storage cannot be cleared that finely — [WebStorage] deletes all of it — but
 * it holds no login: every session this app keeps is a cookie, and the WebViews
 * that write local storage are the login ones, which are gone by this point.
 */
fun clearYandexMusicWebSession() {
    val manager = CookieManager.getInstance()
    val urls = listOf(
        "https://oauth.yandex.ru",
        "https://passport.yandex.ru",
        "https://music.yandex.ru",
        "https://yandex.ru",
        "https://oauth.yandex.com",
        "https://passport.yandex.com",
    )
    urls.forEach { url ->
        manager.getCookie(url)?.split(';')
            ?.map { it.substringBefore('=').trim() }
            ?.filter(String::isNotEmpty)
            ?.forEach { name ->
                val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
                manager.setCookie(url, expired)
                manager.setCookie(url, "$expired; Domain=.yandex.ru")
                manager.setCookie(url, "$expired; Domain=.yandex.com")
            }
    }
    manager.flush()
    // The web player keeps its own session in localStorage/IndexedDB, which is
    // what would otherwise sign the next visit straight back in — the cookies
    // above are not the whole of it.
    WebStorage.getInstance().deleteAllData()
}

/** Embedded Yandex OAuth login which captures the access token from the final URL fragment. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YandexMusicLoginScreen(
    onTokenCaptured: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.userAgentString = browserUserAgent(settings.userAgentString)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                var completed = false

                fun inspect(url: String?): Boolean {
                    if (completed) return true
                    when (val result = parseYandexOAuthRedirect(url)) {
                        is YandexOAuthResult.Token -> {
                            completed = true
                            visibility = View.GONE
                            onTokenCaptured(result.value)
                            return true
                        }
                        is YandexOAuthResult.Error -> {
                            completed = true
                            onError(result.description)
                            return true
                        }
                        null -> Unit
                    }
                    return false
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (inspect(request.url.toString())) return true
                        return request.url.scheme != "https"
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        inspect(url)
                        canGoBack = view.canGoBack()
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        inspect(url)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        inspect(url)
                        canGoBack = view.canGoBack()
                    }

                    override fun onPageCommitVisible(view: WebView, url: String?) {
                        inspect(url)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame && !completed) onError(error.description.toString())
                    }
                }
                // The access token is in a URL fragment. A same-document hash
                // navigation does not reliably trigger every WebViewClient
                // callback on every Chromium version, so also inspect the
                // current visible URL while the page advances.
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        inspect(view.url)
                    }
                }
                webView = this
                loadUrl(LOGIN_URL)
            }
        },
        onRelease = { it.destroy() },
    )

    BackHandler(enabled = canGoBack) { webView?.goBack() }
}
