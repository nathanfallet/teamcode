package me.nathanfallet.teamcode.companion.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.OpenArtifactUseCase
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationResponse
import platform.WebKit.WKNavigationResponsePolicy
import platform.WebKit.WKNavigationTypeLinkActivated
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject

actual val embedsArtifacts: Boolean = true

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun ArtifactContentView(contentUrl: String, prefix: String, onExternal: (String) -> Unit, onMissing: () -> Unit, modifier: Modifier) {
    val currentPrefix by rememberUpdatedState(prefix)
    val external by rememberUpdatedState(onExternal)
    val missing by rememberUpdatedState(onMissing)
    // Held here: the web view keeps its delegates weakly.
    val delegate = remember { ArtifactNavigation({ currentPrefix }, { external(it) }, { missing() }) }
    // The URL the web view was last asked to load.
    val shown = remember { arrayOfNulls<String>(1) }
    UIKitView(
        factory = {
            val configuration = WKWebViewConfiguration().apply {
                // Nothing kept between viewers: no cookie, no storage, no cache on disk.
                websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
                allowsInlineMediaPlayback = true
            }
            WKWebView(frame = CGRectZero.readValue(), configuration = configuration).apply {
                navigationDelegate = delegate
                UIDelegate = delegate
                shown[0] = contentUrl
                NSURL.URLWithString(contentUrl)?.let { loadRequest(NSURLRequest.requestWithURL(it)) }
            }
        },
        modifier = modifier,
        update = { view ->
            if (shown.lastOrNull() != contentUrl) {
                shown[0] = contentUrl
                NSURL.URLWithString(contentUrl)?.let { view.loadRequest(NSURLRequest.requestWithURL(it)) }
            }
        },
        onRelease = { view -> view.navigationDelegate = null; view.UIDelegate = null; view.stopLoading() },
    )
}

/** Keeps the main frame under the ticket's prefix, sends pop-ups and user links outside, and reports a missing page. */
@OptIn(ExperimentalForeignApi::class)
private class ArtifactNavigation(
    private val prefix: () -> String,
    private val onExternal: (String) -> Unit,
    private val onMissing: () -> Unit,
) : NSObject(), WKNavigationDelegateProtocol, WKUIDelegateProtocol {
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString
        if (url != null && OpenArtifactUseCase.allows(prefix(), url)) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            return
        }
        val mainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame ?: true
        if (url != null && mainFrame && decidePolicyForNavigationAction.navigationType == WKNavigationTypeLinkActivated && isWebLink(url)) onExternal(url)
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, decidePolicyForNavigationResponse: WKNavigationResponse, decisionHandler: (WKNavigationResponsePolicy) -> Unit) {
        val status = (decidePolicyForNavigationResponse.response as? NSHTTPURLResponse)?.statusCode
        if (decidePolicyForNavigationResponse.forMainFrame && status == 404L) onMissing()
        decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow)
    }

    /** A pop-up never opens a second web view: a link the member followed is offered to the browser. */
    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        val url = forNavigationAction.request.URL?.absoluteString
        if (url != null && forNavigationAction.navigationType == WKNavigationTypeLinkActivated && isWebLink(url)) onExternal(url)
        return null
    }
}
