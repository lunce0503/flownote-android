package kr.flownote.remote.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.*
import android.view.inputmethod.InputMethodManager
import android.view.ViewGroup
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
class TerminalView(context: Context, private val receive: (JSONObject) -> Unit) : WebView(context) {
    val renderer: (JSONObject) -> Unit = ::deliver
    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        fun supported() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    }
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(android.graphics.Color.rgb(16, 18, 20))
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false; allowContentAccess = false
            domStorageEnabled = false; databaseEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptCookie(false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                return loader.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }
        }
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { "Android System WebView를 업데이트하세요." }
        WebViewCompat.addWebMessageListener(this, "NativeTerminal", setOf(ORIGIN)) { _, message, origin, mainFrame, _ ->
            if (mainFrame && origin.toString() == ORIGIN) {
                val data = message.data
                if (data != null && data.length <= 512 * 1024) runCatching {
                    val value = JSONObject(data)
                    if (value.optString("type") == "keyboard") {
                        requestFocus()
                        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    } else receive(value)
                }
            }
        }
        loadUrl("$ORIGIN/assets/terminal/index.html")
    }
    fun deliver(message: JSONObject) {
        WebViewCompat.postWebMessage(this, WebMessageCompat(message.toString()), Uri.parse(ORIGIN))
    }
    fun release() { stopLoading(); WebViewCompat.removeWebMessageListener(this, "NativeTerminal"); destroy() }
}
