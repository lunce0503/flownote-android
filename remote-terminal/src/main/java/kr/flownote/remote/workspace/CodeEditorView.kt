package kr.flownote.remote.workspace

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import androidx.webkit.*
import kr.flownote.remote.terminal.TerminalView
import org.json.JSONObject
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
class CodeEditorView(context: Context, receive: (JSONObject) -> Unit) : WebView(context) {
    val renderer: (JSONObject) -> Unit = { WebViewCompat.postWebMessage(this, WebMessageCompat(it.toString()), Uri.parse(TerminalView.ORIGIN)) }
    private val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(android.graphics.Color.rgb(16, 18, 20))
        settings.apply {
            javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
            domStorageEnabled = false; databaseEnabled = false; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptCookie(false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = loader.shouldInterceptRequest(request.url)
                ?: WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) receive(JSONObject().put("type", "failed"))
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) receive(JSONObject().put("type", "failed"))
                return true
            }
        }
        WebViewCompat.addWebMessageListener(this, "NativeEditor", setOf(TerminalView.ORIGIN)) { _, message, origin, mainFrame, _ ->
            if (mainFrame && origin.toString() == TerminalView.ORIGIN) message.data?.takeIf { it.length <= 2 * 1024 * 1024 }?.let { raw ->
                runCatching {
                    val value = JSONObject(raw)
                    if (value.optString("type") == "keyboard") {
                        requestFocus()
                        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    } else receive(value)
                }
            }
        }
        loadUrl("${TerminalView.ORIGIN}/assets/editor/index.html")
    }
    fun release() { stopLoading(); WebViewCompat.removeWebMessageListener(this, "NativeEditor"); destroy() }
}
