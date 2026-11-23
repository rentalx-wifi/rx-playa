package com.rentalx.playa.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.rentalx.playa.data.DebugLog as Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI

private const val MUSIC_ORIGIN = "https://music.youtube.com"
private const val MUSIC_WEB_URL = "$MUSIC_ORIGIN/?app=desktop"

/**
 * Where a listener who has to sign in with Google is sent.
 *
 * Opened in the phone's own browser, not in a WebView: Google refuses its
 * sign-in page to embedded browsers for most accounts, and the cookie that
 * comes out of a real browser is the one this app can use — see the sheet that
 * asks for it.
 */
const val YOUTUBE_MUSIC_LOGIN_URL =
    "https://accounts.google.com/ServiceLogin" +
        "?ltmpl=music&service=youtube&prompt=select_account" +
        "&continue=https%3A%2F%2Fmusic.youtube.com%2F%3Fapp%3Ddesktop"

private const val TAG = "Playa"

/**
 * In-app Google sign-in for YouTube Music, and the way to change which channel
 * it listens as.
 *
 * [WebSessionMode.SIGN_IN] loads the standard Google web login with
 * `continue=music.youtube.com`. The user authenticates directly against
 * accounts.google.com (2FA, passkeys etc. all work — it's the real page). When
 * Google redirects back to music.youtube.com the listener confirms the profile
 * shown by the live page. This deliberately leaves a multiple-channel account
 * enough time to choose its Personal or Brand identity before anything is
 * saved. With [initialCookie], Music opens directly using the listener's own
 * browser session instead of attempting Google sign-in in an embedded browser.
 * The browser's local Google cookies are cleared on the way in without
 * visiting Google's logout endpoint, so adding an account cannot invalidate a
 * previously saved session.
 *
 * [WebSessionMode.SWITCH_CHANNEL] keeps those cookies and opens YouTube Music
 * itself, so the listener can use the avatar menu's own Accounts list — the one
 * screen that authoritatively knows which channels exist and which is which.
 * Nothing is taken automatically there: the session is read when they say so,
 * by raising [captureRequest].
 *
 * Either way what is read is the page's own `ytcfg`, not a guess made later
 * from a server-side fetch. Passwords are handled by Google, never by the app.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YtMusicLoginScreen(
    mode: WebSessionMode,
    /** An imported browser session, or the snapshot for an existing account's channel picker. */
    initialCookie: String? = null,
    onCaptured: (CapturedSession) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Raise to take the session from the page as it stands. Ignored at its
     * initial value, so arriving on the screen doesn't capture anything.
     */
    captureRequest: Int = 0,
    /** Told when a capture was asked for and there was no session to take. */
    onCaptureUnavailable: () -> Unit = {},
    /** True once a signed-in YouTube Music page is available for confirmation. */
    onPageReady: (Boolean) -> Unit = {},
    /** For a host that needs the WebView set up differently — the TV app, for its remote. */
    configureWebView: (WebView) -> Unit = {},
    /**
     * Called once when Google refuses this embedded browser outright.
     *
     * The message is policy, not something a user agent can fix: Google blocks
     * WebView sign-in on many accounts. Detecting it is what turns a dead end
     * into the cookie import that does work.
     */
    onBlocked: () -> Unit = {},
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnUnavailable by rememberUpdatedState(onCaptureUnavailable)
    val currentOnPageReady by rememberUpdatedState(onPageReady)
    val currentOnBlocked by rememberUpdatedState(onBlocked)
    var blockedReported by remember { mutableStateOf(false) }

    LaunchedEffect(captureRequest) {
        if (captureRequest == 0) return@LaunchedEffect
        val view = webView
        if (view == null) currentOnUnavailable()
        else captureFrom(view, currentOnCaptured, currentOnUnavailable)
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                if (mode == WebSessionMode.SIGN_IN) {
                    clearCache(true)
                    clearHistory()
                    clearFormData()
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                // A mobile/WebView UA makes music.youtube.com advertise its
                // Android APK and redirect to Play instead of loading the web
                // client. Keep the device's actual Chromium version but present
                // it as desktop Chrome, which is a supported YouTube Music web
                // surface and still gives us the same account cookies.
                settings.userAgentString = youtubeMusicWebUserAgent(settings.userAgentString)
                settings.useWideViewPort = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                configureWebView(this)

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val target = request.url
                        if (target.host.equals("play.google.com", ignoreCase = true) ||
                            target.scheme in setOf("market", "intent", "vnd.youtube.music")
                        ) {
                            view.loadUrl(MUSIC_WEB_URL)
                            return true
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        currentOnPageReady(false)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!blockedReported && url?.contains("accounts.google.com") == true) {
                            view?.evaluateJavascript(
                                "(document.body && document.body.innerText || '').slice(0, 4000)",
                            ) { text ->
                                val lowered = text.orEmpty().lowercase()
                                val refused = lowered.contains("secure") ||
                                    lowered.contains("couldn't sign you in") ||
                                    lowered.contains("unsupported browser") ||
                                    lowered.contains("не удалось войти")
                                if (refused && !blockedReported) {
                                    blockedReported = true
                                    currentOnBlocked()
                                }
                            }
                        }
                        // Reaching the Music origin only enables confirmation.
                        // A multi-channel login can still be waiting for the
                        // listener to choose an identity on this very page.
                        // Capturing automatically here is the race that used to
                        // create a fake "Personal" profile and close too soon.
                        currentOnPageReady(isYouTubeMusicWebPage(url) && isYouTubeMusicWebPage(view?.url))
                    }
                }

                webView = this
                val view = this
                val load = {
                    // Cookie callbacks can outlive a fast Back press. Never load
                    // into a WebView Compose has already released and destroyed.
                    if (webView === view) {
                        view.loadUrl(
                            if (mode == WebSessionMode.SIGN_IN && initialCookie == null) YOUTUBE_MUSIC_LOGIN_URL else MUSIC_WEB_URL,
                        )
                    }
                }
                when {
                    initialCookie != null -> BrowserSession.installGoogleCookies(initialCookie, load)
                    mode == WebSessionMode.SIGN_IN -> BrowserSession.clearGoogleCookies(load)
                    else -> load()
                }
            }
        },
        onRelease = { view ->
            webView = null
            view.stopLoading()
            view.destroy()
        },
    )
}

internal fun isYouTubeMusicWebPage(rawUrl: String?): Boolean {
    val uri = rawUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("music.youtube.com", ignoreCase = true) &&
        uri.userInfo == null && uri.port in setOf(-1, 443)
}

private val CHROME_VERSION = Regex("""Chrome/[0-9.]+""")

internal fun youtubeMusicWebUserAgent(platformUserAgent: String?): String {
    val chrome = CHROME_VERSION.find(platformUserAgent.orEmpty())?.value ?: "Chrome/129.0.0.0"
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "$chrome Safari/537.36"
}

/**
 * Takes the session from [view], if it is holding one.
 *
 * @return whether there was one to take. False means the cookie jar has no
 *   signing secret in it yet — the page is mid-login, or is not a YouTube page
 *   at all — and the caller should leave the screen open rather than saving
 *   something that cannot sign a request. See [AuthStore.hasApiSid].
 */
private fun captureFrom(
    view: WebView,
    onCaptured: (CapturedSession) -> Unit,
    onUnavailable: () -> Unit,
) {
    if (!isYouTubeMusicWebPage(view.url)) {
        Log.w(TAG, "capture ignored: page is not music.youtube.com (url=${view.url?.take(120)})")
        onUnavailable()
        return
    }
    val cookies = CookieManager.getInstance().getCookie(MUSIC_ORIGIN)
    if (cookies == null || !AuthStore.hasApiSid(cookies)) {
        // Names, not values: which cookies arrived is the whole question here,
        // and a jar with SID missing is a cookie that was never pasted right.
        Log.w(TAG, "capture refused: cookie jar has ${cookieNames(cookies)}, no signing SID")
        onUnavailable()
        return
    }
    // Flushed here rather than left to the WebView's own schedule: the screen
    // is usually closing in the next frame, and a cookie jar written after
    // that is a jar the next sign-in reads instead of this one.
    CookieManager.getInstance().flush()

    view.evaluateJavascript(YTCFG_PROBE) { raw ->
        if (!isYouTubeMusicWebPage(view.url)) {
            onUnavailable()
            return@evaluateJavascript
        }
        val config = raw.parseConfig()
        val loggedIn = config?.get("loggedIn").let {
            it is JsonPrimitive && it.content == "true"
        }
        if (config == null || !loggedIn) {
            Log.w(TAG, "capture refused: page is not signed in, ytcfg=${raw.orEmpty().take(400)}")
            onUnavailable()
            return@evaluateJavascript
        }
        val pageId = config.string("pageId")
        onCaptured(
            CapturedSession(
                cookie = cookies,
                // A delegated identity is the most specific answer the live
                // page can give. Otherwise normalise its DATASYNC_ID.
                dataSyncId = pageId ?: normalizeDataSyncId(config.string("dataSyncId")),
                pageId = pageId,
                authUser = config.string("authUser"),
                visitorData = config.string("visitorData"),
                clientVersion = config.string("clientVersion"),
                loggedIn = true,
            ),
        )
    }
}

/**
 * The identity of the page as the page itself has it.
 *
 * Returns an object rather than a string so the WebView serialises it — a
 * probe that stringified its own result would come back double-encoded. A page
 * without `ytcfg` (an error page, a redirect that hasn't landed) returns null,
 * which is a fine answer and not an error.
 */
private const val YTCFG_PROBE = """
(function () {
  try {
    if (!window.ytcfg || !window.ytcfg.get) return null;
    var get = function (key) {
      var value = window.ytcfg.get(key);
      return (value === undefined || value === null || value === '') ? null : String(value);
    };
    return {
      loggedIn: String(!!window.ytcfg.get('LOGGED_IN')),
      pageId: get('DELEGATED_SESSION_ID'),
      dataSyncId: get('DATASYNC_ID'),
      authUser: get('SESSION_INDEX'),
      visitorData: get('VISITOR_DATA'),
      clientVersion: get('INNERTUBE_CLIENT_VERSION')
    };
  } catch (e) {
    return null;
  }
})()
"""

private val json = Json { ignoreUnknownKeys = true }

/** Cookie names from the browser jar, for a log line that stays short and value-free. */
internal fun cookieNames(header: String?): String {
    val names = header.orEmpty().split(';')
        .mapNotNull { it.substringBefore('=', "").trim().takeIf(String::isNotEmpty) }
    return when {
        names.isEmpty() -> "nothing"
        names.size <= 12 -> names.joinToString(" ") { it }
        else -> names.take(12).joinToString(" ") { it } + " +${names.size - 12}"
    }
}

/** The probe's result, or null for anything that isn't the object it promises. */
private fun String?.parseConfig(): JsonObject? =
    this?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
