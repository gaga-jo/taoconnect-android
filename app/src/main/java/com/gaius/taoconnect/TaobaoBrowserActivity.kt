package com.gaius.taoconnect

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

class TaobaoBrowserActivity : AppCompatActivity() {
    internal lateinit var webView: WebView
    private lateinit var translator: BilingualTranslator
    private lateinit var statusView: TextView
    private lateinit var domainView: TextView
    private lateinit var toggle: Button
    private var enabled = true
    private var epoch = 0
    private var requestWindow = 0L
    private var requestCount = 0
    private var pageFailed = false
    private var testMode = false
    private var lastStatus = "Chinois + français · Google Traduction"
    private var script = ""
    private var bridgeSupported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.browserRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        statusView = findViewById(R.id.browserStatus)
        domainView = findViewById(R.id.browserDomain)
        toggle = findViewById(R.id.bilingualToggle)
        webView = findViewById(R.id.taobaoWebView)
        enabled = getPreferences(MODE_PRIVATE).getBoolean("bilingual", true)
        val requestedTestPage = intent.getStringExtra(EXTRA_TEST_PAGE)
        testMode = BuildConfig.DEBUG && TaobaoNavigationPolicy.isTestPage(requestedTestPage)
        translator = BilingualTranslator {
            lastStatus = it
            if (enabled && !pageFailed) statusView.text = it
        }
        script = assets.open("tao-bilingual.js").bufferedReader().use { it.readText() }
        configureWebView()
        toggle.setOnClickListener { setBilingual(!enabled) }
        findViewById<Button>(R.id.browserBack).setOnClickListener { goBack() }
        findViewById<Button>(R.id.browserMenu).setOnClickListener { showMenu(it) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBack() }
        })
        updateToggle()
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(if (testMode) requestedTestPage!! else TaobaoNavigationPolicy.HOME)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true // Taobao and the bilingual DOM engine require JavaScript.
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        bridgeSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (bridgeSupported) {
            val origins = TaobaoNavigationPolicy.origins.toMutableSet()
            if (testMode) origins += TaobaoNavigationPolicy.TEST_ORIGIN
            WebViewCompat.addWebMessageListener(webView, "TaoNative", origins) {
                    _, message, sourceOrigin, _, reply ->
                onMessage(message, sourceOrigin, reply)
            }
        } else {
            lastStatus = "Mettez Android System WebView à jour pour le mode bilingue"
            statusView.text = lastStatus
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) {
                findViewById<ProgressBar>(R.id.pageProgress).apply {
                    this.progress = progress
                    visibility = if (progress < 100) View.VISIBLE else View.INVISIBLE
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (TaobaoNavigationPolicy.canLoad(request.url.toString(), testMode)) return false
                if (request.hasGesture()) offerExternal(request.url)
                else statusView.text = "Lien hors Taobao bloqué · aucune traduction hors Taobao"
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (testMode && TaobaoNavigationPolicy.isTestPage(url)) {
                    return assetLoader.shouldInterceptRequest(request.url)
                }
                if (request.isForMainFrame && !TaobaoNavigationPolicy.isTaobao(url)) {
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Outside Taobao",
                        emptyMap(), ByteArrayInputStream("Navigation limitée à Taobao".toByteArray()))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                epoch++
                translator.cancelPage()
                pageFailed = false
                domainView.text = Uri.parse(url ?: "").host ?: "Taobao"
                statusView.text = if (enabled) lastStatus else "Français masqué · chinois conservé"
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (bridgeSupported && TaobaoNavigationPolicy.canLoad(url, testMode) && !pageFailed) {
                    installEngine()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    pageFailed = true
                    statusView.text = "Page indisponible · vérifiez Internet puis Actualiser"
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                pageFailed = true
                statusView.text = "Connexion non sécurisée bloquée"
            }
        }
    }

    private fun installEngine() {
        val config = JSONObject().put("enabled", enabled).put("testMode", testMode)
        webView.evaluateJavascript("window.__taoConfig=$config;\n$script", null)
    }

    private fun onMessage(message: WebMessageCompat, origin: Uri, reply: JavaScriptReplyProxy) {
        if (!enabled || !TaobaoNavigationPolicy.canLoad(webView.url, testMode)) return
        if (!TaobaoNavigationPolicy.isTaobao(origin.toString()) &&
            !(testMode && origin.toString() == TaobaoNavigationPolicy.TEST_ORIGIN)) return
        val raw = runCatching { message.data }.getOrNull() ?: return
        if (raw.length > 16000) return
        val now = SystemClock.elapsedRealtime()
        if (now - requestWindow > 1000) { requestWindow = now; requestCount = 0 }
        if (++requestCount > 12) return
        val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (payload.optString("type") != "translate") return
        val id = payload.optString("id")
        if (id.length !in 1..100) return
        val texts = payload.optJSONArray("texts") ?: return
        if (texts.length() !in 1..12) return
        if ((0 until texts.length()).any { texts.optString(it).length > 600 }) return
        val requestEpoch = epoch
        val results = arrayOfNulls<String>(texts.length())
        var remaining = texts.length()
        for (index in 0 until texts.length()) {
            translator.translate(texts.optString(index)) { translated ->
                results[index] = translated
                remaining--
                if (remaining == 0 && requestEpoch == epoch && enabled && !isDestroyed) {
                    val json = JSONObject().put("id", id).put("translations",
                        JSONArray().also { a -> results.forEach { a.put(it ?: JSONObject.NULL) } })
                    runCatching { reply.postMessage(json.toString()) }
                }
            }
        }
    }

    internal fun setBilingual(value: Boolean) {
        enabled = value
        getPreferences(MODE_PRIVATE).edit().putBoolean("bilingual", value).apply()
        if (!value) { epoch++; translator.cancelPage() }
        webView.evaluateJavascript("window.__taoConnect && window.__taoConnect.setEnabled($value)", null)
        updateToggle()
    }

    private fun updateToggle() {
        toggle.text = if (enabled) "中 + FR" else "中"
        toggle.contentDescription = if (enabled) "Masquer le français" else "Afficher le français sous le chinois"
        statusView.text = if (enabled) lastStatus else "Français masqué · chinois conservé"
    }

    private fun goBack() { if (webView.canGoBack()) webView.goBack() else finish() }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Accueil Taobao")
            menu.add(0, 2, 1, "Actualiser / réessayer")
            menu.add(0, 3, 2, "Effacer le cache de traduction")
            menu.add(0, 4, 3, "À propos des traductions")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> webView.loadUrl(TaobaoNavigationPolicy.HOME)
                    2 -> { translator.prepare(); webView.reload() }
                    3 -> { translator.clearCache(); webView.reload() }
                    4 -> AlertDialog.Builder(this@TaobaoBrowserActivity)
                        .setTitle("Le chinois reste la référence")
                        .setMessage("Traduction automatique locale par Google, complétée par un glossaire Taobao. " +
                            "Le texte français peut contenir des erreurs. Les images et les pages externes " +
                            "ne sont pas traduites. Aucun texte saisi ni capture d’écran n’est envoyé à un serveur de traduction.")
                        .setPositiveButton("Compris", null).show()
                }
                true
            }
            show()
        }
    }

    private fun offerExternal(uri: Uri) {
        if (uri.scheme != "https") {
            statusView.text = "Cette fonction exige peut-être l’application officielle Taobao"
            return
        }
        AlertDialog.Builder(this).setTitle("Quitter Taobao ?")
            .setMessage("Ce lien ouvre ${uri.host}. Tao Connect ne traduit pas les sites externes.")
            .setNegativeButton("Rester ici", null)
            .setPositiveButton("Ouvrir le navigateur") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                catch (_: ActivityNotFoundException) { statusView.text = "Aucun navigateur disponible" }
            }.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() { webView.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); if (::webView.isInitialized) webView.onResume() }
    override fun onDestroy() {
        epoch++
        translator.close()
        webView.stopLoading()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object { const val EXTRA_TEST_PAGE = "tao_internal_test_page" }
}
