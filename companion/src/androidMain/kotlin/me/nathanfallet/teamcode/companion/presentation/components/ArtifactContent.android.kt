package me.nathanfallet.teamcode.companion.presentation.components

import android.annotation.SuppressLint
import android.os.Message
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.OpenArtifactUseCase

actual val embedsArtifacts: Boolean = true

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun ArtifactContentView(contentUrl: String, prefix: String, onExternal: (String) -> Unit, onMissing: () -> Unit, modifier: Modifier) {
    val currentPrefix by rememberUpdatedState(prefix)
    val external by rememberUpdatedState(onExternal)
    val missing by rememberUpdatedState(onMissing)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            CookieManager.getInstance().setAcceptCookie(false)
            WebView(context).apply {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                with(settings) {
                    // Pages may run their own scripts (the server's sandbox allows them); nothing of the app is reachable.
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    setGeolocationEnabled(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    safeBrowsingEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    // Pop-ups become a request to open the link outside (onCreateWindow).
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (OpenArtifactUseCase.allows(currentPrefix, url)) return false
                        if (request.isForMainFrame && request.hasGesture() && isWebLink(url)) external(url)
                        return true
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame && errorResponse.statusCode == 404) missing()
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                        val url = view.hitTestResult.extra
                        if (isUserGesture && url != null && isWebLink(url)) external(url)
                        return false
                    }

                    override fun onPermissionRequest(request: PermissionRequest) = request.deny()

                    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
                }
                tag = contentUrl
                loadUrl(contentUrl)
            }
        },
        update = { view -> if (view.tag != contentUrl) { view.tag = contentUrl; view.loadUrl(contentUrl) } },
        onRelease = { view -> view.clearCache(true); view.destroy() },
    )
}
