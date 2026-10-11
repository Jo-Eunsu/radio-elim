package Radio.Elim

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {
    companion object {
        // iOS and Android use the same GitHub Pages site.
        private const val APP_URL = "https://elim-radio.github.io/radio-elim/"
    }

    private var appWebView: WebView? = null
    private var playbackConnection: NativeAudioConnection? = null
    private var loading by mutableStateOf(true)
    private var pageError by mutableStateOf<String?>(null)
    private var mainFrameFailed = false
    private var backgroundSince = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val loadTimeout = Runnable {
        appWebView?.stopLoading()
        failPage("연결이 지연되고 있습니다. 인터넷 연결을 확인한 뒤 다시 시도해 주세요.")
    }

    private fun isAppPage(uri: Uri): Boolean = PlaybackRules.isDocument(uri.toString())

    private fun exitApp() {
        playbackConnection?.stopPlayback()
        finishAndRemoveTask()
    }

    private fun failPage(message: String) {
        handler.removeCallbacks(loadTimeout)
        mainFrameFailed = true
        loading = false
        pageError = message
    }

    private fun loadLatestPage() {
        val view = appWebView ?: return
        if (playbackConnection == null) {
            failPage("Google Play에서 Android 시스템 WebView와 Chrome을 업데이트한 뒤 앱을 다시 열어 주세요.")
            return
        }
        handler.removeCallbacks(loadTimeout)
        view.stopLoading()
        mainFrameFailed = false
        pageError = null
        loading = true
        // Fresh document URL; same origin preserves website localStorage.
        val latest = Uri.parse(APP_URL).buildUpon()
            .appendQueryParameter("_app_reload", System.currentTimeMillis().toString())
            .build().toString()
        view.loadUrl(latest, mapOf("Cache-Control" to "no-cache"))
        handler.postDelayed(loadTimeout, 25_000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                BackHandler {
                    val view = appWebView
                    if (view == null || pageError != null) {
                        exitApp()
                    } else {
                        view.evaluateJavascript(
                            """
                            (() => {
                                if (typeof currentRoute === 'undefined') return false;
                                if (currentRoute === 'player' && typeof backToList === 'function') {
                                    backToList(); return true;
                                }
                                if (currentRoute === 'favorites' && typeof navigate === 'function') {
                                    navigate('home'); return true;
                                }
                                return false;
                            })();
                            """.trimIndent()
                        ) { handled ->
                            if (handled != "true") {
                                // Single-page app: do not reopen stale refresh URLs.
                                exitApp()
                            }
                        }
                    }
                }

                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                appWebView = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                settings.cacheMode = WebSettings.LOAD_DEFAULT
                                webChromeClient = WebChromeClient()
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView, request: WebResourceRequest
                                    ): Boolean {
                                        if (isAppPage(request.url)) return false
                                        if (request.isForMainFrame && request.hasGesture() &&
                                            request.url.scheme in listOf("https", "http", "mailto", "tel")) {
                                            try {
                                                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                            } catch (_: ActivityNotFoundException) {
                                                Toast.makeText(context, "링크를 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        return true
                                    }

                                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                        playbackConnection?.markPageLoading()
                                        mainFrameFailed = false
                                        loading = true
                                        pageError = null
                                        handler.removeCallbacks(loadTimeout)
                                        handler.postDelayed(loadTimeout, 25_000)
                                    }

                                    override fun onReceivedError(
                                        view: WebView, request: WebResourceRequest, error: WebResourceError
                                    ) {
                                        if (request.isForMainFrame) {
                                            failPage("인터넷 연결을 확인한 뒤 다시 시도해 주세요. (오류 ${error.errorCode})")
                                        }
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView, request: WebResourceRequest, response: WebResourceResponse
                                    ) {
                                        if (request.isForMainFrame) {
                                            failPage("웹페이지에 연결할 수 없습니다. (HTTP ${response.statusCode})")
                                        }
                                    }

                                    override fun onReceivedSslError(
                                        view: WebView, sslHandler: SslErrorHandler, error: SslError
                                    ) {
                                        sslHandler.cancel()
                                        if (error.url == view.url) failPage("보안 연결을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.")
                                    }

                                    override fun onRenderProcessGone(
                                        view: WebView, detail: RenderProcessGoneDetail
                                    ): Boolean {
                                        (view.parent as? ViewGroup)?.removeView(view)
                                        playbackConnection?.release()
                                        playbackConnection = null
                                        if (appWebView === view) appWebView = null
                                        view.destroy()
                                        failPage("웹 화면이 종료되었습니다. 다시 시도해 주세요.")
                                        return true
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        if (mainFrameFailed || !isAppPage(Uri.parse(url))) return
                                        view.evaluateJavascript(
                                            """
                                            (() => {
                                                let style = document.getElementById('elim-android-viewport');
                                                if (!style) {
                                                    style = document.createElement('style');
                                                    style.id = 'elim-android-viewport';
                                                    style.textContent = 'html,body{height:100vh!important;height:100dvh!important;min-height:100vh;min-height:100dvh}#app{height:100%;min-height:100%}';
                                                    document.head.appendChild(style);
                                                }
                                                if (!window.__elimAndroidLinks) {
                                                    document.addEventListener('click', function(event) {
                                                        const anchor = event.target.closest && event.target.closest('a[target="_blank"]');
                                                        if (anchor) anchor.target = '_self';
                                                    }, true);
                                                    window.__elimAndroidLinks = true;
                                                }
                                                const main = document.getElementById('main-content');
                                                return !!window.__elimNativeReady && !!document.body && (main ? main.children.length > 0 : document.body.innerText.trim().length > 0);
                                            })();
                                            """.trimIndent()
                                        ) { ready ->
                                            if (!mainFrameFailed) {
                                                handler.removeCallbacks(loadTimeout)
                                                if (ready == "true") {
                                                    loading = false
                                                    pageError = null
                                                } else {
                                                    failPage("화면을 준비하지 못했습니다. 다시 시도해 주세요.")
                                                }
                                            }
                                        }
                                    }
                                }
                                if (NativeAudioConnection.isSupported()) {
                                    playbackConnection = NativeAudioConnection(this) { failPage(it) }
                                }
                                loadLatestPage()
                            }
                        },
                        onRelease = { view ->
                            handler.removeCallbacks(loadTimeout)
                            if (appWebView === view) {
                                playbackConnection?.release()
                                playbackConnection = null
                                appWebView = null
                                view.stopLoading()
                                view.destroy()
                            }
                        }
                    )

                    if (loading || pageError != null) {
                        Column(
                            modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC)).padding(28.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("라디오 엘림", style = MaterialTheme.typography.titleLarge)
                            if (pageError == null) {
                                CircularProgressIndicator()
                                Text("최신 방송 목록을 불러오고 있습니다.", textAlign = TextAlign.Center)
                            } else {
                                Text(pageError.orEmpty(), textAlign = TextAlign.Center)
                                Button(onClick = {
                                    if (appWebView == null) recreate() else loadLatestPage()
                                }) { Text("다시 시도") }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appWebView?.onResume()
        playbackConnection?.setForeground(true)
        // Refresh after a longer break, never during active use.
        if (backgroundSince > 0 && SystemClock.elapsedRealtime() - backgroundSince > 60_000 &&
            playbackConnection?.hasActivePlayback == false) {
            loadLatestPage()
        }
        backgroundSince = 0L
    }

    override fun onStop() {
        // The service owns playback; only suspend UI synchronization here.
        playbackConnection?.setForeground(false)
        appWebView?.onPause()
        backgroundSince = SystemClock.elapsedRealtime()
        super.onStop()
    }
}
