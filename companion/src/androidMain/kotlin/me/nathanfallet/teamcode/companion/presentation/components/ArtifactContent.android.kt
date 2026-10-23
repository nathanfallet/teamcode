package me.nathanfallet.teamcode.companion.presentation.components

import android.annotation.SuppressLint
import android.os.Message
import android.view.ContextThemeWrapper
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.OpenArtifactUseCase
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode

actual val embedsArtifacts: Boolean = true

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun ArtifactContentView(contentUrl: String, prefix: String, reload: Int, onExternal: (String) -> Unit, onMissing: (String) -> Unit, onCrashed: () -> Unit, modifier: Modifier) {
    val currentPrefix by rememberUpdatedState(prefix)
    val external by rememberUpdatedState(onExternal)
    val missing by rememberUpdatedState(onMissing)
    val crashed by rememberUpdatedState(onCrashed)
    // The page's prefers-color-scheme follows the theme of the web view's context: the app's appearance, not the
    // activity's fixed theme.
    val dark = TeamCode.colors.isDark
    // A new web view per URL, reload and appearance: a renewed ticket may have the same URL, and a crashed renderer is
    // never reused.
    key(contentUrl, reload, dark) { AndroidView(
        modifier = modifier,
        factory = { context ->
            CookieManager.getInstance().setAcceptCookie(false)
            val themed = ContextThemeWrapper(context, if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
            WebView(themed).apply {
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
                        // A frame of the page's own making (srcdoc, a blob or data document) stays inside its sandbox.
                        if (!request.isForMainFrame && request.url.scheme in FRAME_SCHEMES) return false
                        if (request.isForMainFrame && request.hasGesture() && isWebLink(url)) external(url)
                        return true
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame && errorResponse.statusCode == 404) missing(request.url.toString())
                    }

                    /** The page's renderer died (memory, a heavy script): the app lives on, and the screen reloads it once. */
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        crashed()
                        return true
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
                loadUrl(contentUrl)
            }
        },
        onRelease = { view -> view.clearCache(true); view.destroy() },
    ) }
}

private val FRAME_SCHEMES = setOf("about", "blob", "data")
