package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.music.bitchord.R
import com.music.bitchord.data.vk.VkWebAuth

/**
 * VK's own sign-in page, and nothing of ours behind it.
 *
 * The app never sees the password: VK renders 2FA, the captcha and the
 * "confirm it is you" screen, and every one of those ends as a redirect to
 * `oauth.vk.com/blank.html` with the token in the fragment — which is where
 * [VkWebAuth.parseRedirect] picks it up. A refusal arrives at the same address
 * and is handed over just the same, so the caller can say what VK said.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VkWebLoginScreen(
    onRedirect: (Uri) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var loading by remember { mutableStateOf(true) }
    // A page that never arrived used to leave a blank white sheet behind the
    // "Sign in" label, with no way to tell a blocked network from a broken
    // screen (issue #45). The refusal is shown instead, with the one action
    // that could change the answer.
    var failed by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<WebView?>(null) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    page = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // VK's login opens its captcha and consent pages in a new
                    // window; a WebView without the chrome to show one drew an
                    // empty sheet over the page instead (issue #58).
                    settings.setSupportMultipleWindows(false)
                    settings.loadsImagesAutomatically = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loading = true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                        }

                        /**
                         * The main page failing is the case worth showing: a
                         * blocked host, no network, a captive portal. Sub-resource
                         * failures are left alone — one dead image is not a
                         * reason to abandon a page that did load.
                         */
                        override fun onReceivedError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                            error: android.webkit.WebResourceError,
                        ) {
                            if (request.isForMainFrame) {
                                loading = false
                                failed = true
                            }
                        }

                        /**
                         * A certificate refusal cancels the load without an error
                         * page, which is the other way this sheet went white.
                         * Since there is no way past a bad certificate that is
                         * worth offering, the refusal screen is the honest
                         * answer — and its retry is what a listener needs after
                         * switching their VPN off.
                         */
                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: android.webkit.SslErrorHandler?,
                            error: android.net.http.SslError?,
                        ) {
                            handler?.cancel()
                            loading = false
                            failed = true
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ): Boolean = handOver(request.url)

                        /** VK redirects in ways the callback above does not always see. */
                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            url?.let { handOver(Uri.parse(it)) }
                        }

                        private fun handOver(uri: Uri): Boolean {
                            val redirect = VkWebAuth.parseRedirect(uri) ?: return false
                            val settled = if (redirect.error != null) uri else uri
                            onRedirect(settled)
                            return true
                        }
                    }
                    loadUrl(VkWebAuth.authorizationUrl())
                }
            },
        )
        if (loading) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = stringResource(R.string.sign_in),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
        )
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(
                androidx.compose.material.icons.Icons.Rounded.Close,
                contentDescription = stringResource(R.string.close),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (failed) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.no_internet_connection),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Button(onClick = {
                    failed = false
                    loading = true
                    page?.loadUrl(VkWebAuth.authorizationUrl())
                }) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}
