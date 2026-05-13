package com.butter.professorscinema

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var uiWebView: WebView
    private lateinit var cinebyWebView: WebView
    private lateinit var cinebyContainer: LinearLayout
    private lateinit var cinebyTopBar: LinearLayout
    private lateinit var cinebyUrlBar: TextView
    private lateinit var storage: StorageBridge

    // For fullscreen video support
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenContainer: FrameLayout

    // Auto-hide top bar
    private val barHandler = Handler(Looper.getMainLooper())
    private val hideBarRunnable = Runnable { hideTopBar() }

    // URL polling for history tracking
    private val pollHandler = Handler(Looper.getMainLooper())
    private var lastPolledUrl = ""
    private val pollRunnable = object : Runnable {
        override fun run() {
            val url = cinebyWebView.url ?: ""
            if (url.isNotEmpty() && url != "about:blank" && url != lastPolledUrl) {
                lastPolledUrl = url
                cinebyUrlBar.text = url
                notifyUrlChange(url)
            }
            pollHandler.postDelayed(this, 1500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        goFullscreen()

        uiWebView        = findViewById(R.id.webView)
        cinebyWebView    = findViewById(R.id.cinebyWebView)
        cinebyContainer  = findViewById(R.id.cinebyContainer)
        cinebyTopBar     = findViewById(R.id.cinebyTopBar)
        cinebyUrlBar     = findViewById(R.id.cinebyUrlBar)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        storage          = StorageBridge(this)

        // ---- UI WebView (home screen) ----
        configureWebView(uiWebView)
        uiWebView.addJavascriptInterface(storage, "AndroidStorage")
        uiWebView.addJavascriptInterface(NavigationBridge(), "AndroidNav")
        uiWebView.loadUrl("file:///android_asset/web/index.html")

        // ---- Cineby WebView ----
        configureWebView(cinebyWebView)

        cinebyWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
        }

        cinebyWebView.webChromeClient = object : WebChromeClient() {

            // ---- FULLSCREEN VIDEO ----
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    onHideCustomView()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                cinebyContainer.visibility = View.GONE
                goFullscreen()
            }

            override fun onHideCustomView() {
                fullscreenContainer.visibility = View.GONE
                fullscreenContainer.removeAllViews()
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                cinebyContainer.visibility = View.VISIBLE
                goFullscreen()
            }

            override fun getVideoLoadingProgressView(): View? = null

            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                android.util.Log.d("CinebyWV", "${msg?.message()} @ ${msg?.sourceId()}:${msg?.lineNumber()}")
                return true
            }
        }

        // ---- Top bar buttons ----
        findViewById<Button>(R.id.cinebyHomeBtn).setOnClickListener { goHome() }
        findViewById<Button>(R.id.cinebyBackBtn).setOnClickListener {
            if (cinebyWebView.canGoBack()) cinebyWebView.goBack()
            scheduleHideBar()
        }
        findViewById<Button>(R.id.cinebyForwardBtn).setOnClickListener {
            if (cinebyWebView.canGoForward()) cinebyWebView.goForward()
            scheduleHideBar()
        }
        findViewById<Button>(R.id.cinebyReloadBtn).setOnClickListener {
            cinebyWebView.reload()
            scheduleHideBar()
        }
        findViewById<Button>(R.id.cinebySaveBtn).setOnClickListener {
            uiWebView.evaluateJavascript(
                "if(window.saveCurrentFromAndroid) window.saveCurrentFromAndroid();", null
            )
            scheduleHideBar()
        }

        // Show bar on touch anywhere in cineby container
        cinebyWebView.setOnTouchListener { _, _ ->
            showTopBar()
            false
        }
    }

    private fun notifyUrlChange(url: String) {
        val escaped = url.replace("\\", "\\\\").replace("'", "\\'")
        uiWebView.post {
            uiWebView.evaluateJavascript(
                "if(window.onCinebyUrlChange) window.onCinebyUrlChange('$escaped');", null
            )
        }
    }

    fun openCinebyUrl(url: String) {
        runOnUiThread {
            lastPolledUrl = ""
            cinebyContainer.visibility = View.VISIBLE
            cinebyUrlBar.text = url
            cinebyWebView.resumeTimers()
            cinebyWebView.onResume()
            cinebyWebView.loadUrl(url)
            pollHandler.post(pollRunnable)
            showTopBar()
        }
    }

    fun goHome() {
        runOnUiThread {
            // STOP audio/video immediately
            cinebyWebView.onPause()
            cinebyWebView.pauseTimers()
            // Mute via JS as a belt-and-suspenders approach
            cinebyWebView.evaluateJavascript(
                "document.querySelectorAll('video,audio').forEach(function(m){m.pause();m.src='';});", null
            )
            pollHandler.removeCallbacks(pollRunnable)
            barHandler.removeCallbacks(hideBarRunnable)
            cinebyContainer.visibility = View.GONE
            uiWebView.evaluateJavascript("if(window.onReturnHome) window.onReturnHome();", null)
        }
    }

    private fun showTopBar() {
        cinebyTopBar.visibility = View.VISIBLE
        scheduleHideBar()
    }

    private fun hideTopBar() {
        cinebyTopBar.visibility = View.GONE
    }

    private fun scheduleHideBar() {
        barHandler.removeCallbacks(hideBarRunnable)
        barHandler.postDelayed(hideBarRunnable, 3500)
    }

    inner class NavigationBridge {
        @JavascriptInterface
        fun openUrl(url: String) { openCinebyUrl(url) }

        @JavascriptInterface
        fun goHome() { this@MainActivity.goHome() }

        @JavascriptInterface
        fun isInCinebyView(): Boolean = cinebyContainer.visibility == View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        val s: WebSettings = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        // Desktop user agent so cineby renders its full layout
        s.userAgentString =
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; K) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    override fun onPause() {
        super.onPause()
        if (cinebyContainer.visibility == View.VISIBLE) {
            cinebyWebView.onPause()
            cinebyWebView.pauseTimers()
        }
    }

    override fun onResume() {
        super.onResume()
        if (cinebyContainer.visibility == View.VISIBLE) {
            cinebyWebView.onResume()
            cinebyWebView.resumeTimers()
        }
    }

    override fun onDestroy() {
        pollHandler.removeCallbacks(pollRunnable)
        barHandler.removeCallbacks(hideBarRunnable)
        cinebyWebView.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        // If fullscreen video is playing, exit fullscreen first
        if (customView != null) {
            cinebyWebView.webChromeClient?.onHideCustomView()
            return
        }
        if (cinebyContainer.visibility == View.VISIBLE) {
            if (cinebyWebView.canGoBack()) cinebyWebView.goBack()
            else goHome()
            return
        }
        uiWebView.evaluateJavascript(
            "(function(){ if(window.handleAndroidBack) return window.handleAndroidBack(); return false; })()"
        ) { result ->
            if (result != "true") super.onBackPressed()
        }
    }
}

// ===================
// STORAGE BRIDGE
// ===================
class StorageBridge(private val context: Context) {

    private val prefs = context.getSharedPreferences("cineby_data", Context.MODE_PRIVATE)

    @JavascriptInterface
    fun getHistory(): String = prefs.getString("history", "[]") ?: "[]"

    @JavascriptInterface
    fun addHistory(entryJson: String): String {
        val newEntry = JSONObject(entryJson)
        val newUrl = newEntry.optString("url")
        val current = JSONArray(prefs.getString("history", "[]"))
        val updated = JSONArray()
        for (i in 0 until current.length()) {
            val item = current.getJSONObject(i)
            if (item.optString("url") != newUrl) updated.put(item)
        }
        val result = JSONArray()
        result.put(newEntry)
        for (i in 0 until updated.length()) {
            if (i >= 49) break
            result.put(updated.getJSONObject(i))
        }
        prefs.edit().putString("history", result.toString()).apply()
        return result.toString()
    }

    @JavascriptInterface
    fun removeHistory(url: String): String {
        val current = JSONArray(prefs.getString("history", "[]"))
        val updated = JSONArray()
        for (i in 0 until current.length()) {
            val item = current.getJSONObject(i)
            if (item.optString("url") != url) updated.put(item)
        }
        prefs.edit().putString("history", updated.toString()).apply()
        return updated.toString()
    }

    @JavascriptInterface
    fun clearHistory(): String {
        prefs.edit().putString("history", "[]").apply()
        return "[]"
    }

    @JavascriptInterface
    fun getBookmarks(): String = prefs.getString("bookmarks", "[]") ?: "[]"

    @JavascriptInterface
    fun addBookmark(entryJson: String): String {
        val newEntry = JSONObject(entryJson)
        val newUrl = newEntry.optString("url")
        val current = JSONArray(prefs.getString("bookmarks", "[]"))
        val updated = JSONArray()
        for (i in 0 until current.length()) {
            val item = current.getJSONObject(i)
            if (item.optString("url") != newUrl) updated.put(item)
        }
        val result = JSONArray()
        result.put(newEntry)
        for (i in 0 until updated.length()) {
            result.put(updated.getJSONObject(i))
        }
        prefs.edit().putString("bookmarks", result.toString()).apply()
        return result.toString()
    }

    @JavascriptInterface
    fun removeBookmark(url: String): String {
        val current = JSONArray(prefs.getString("bookmarks", "[]"))
        val updated = JSONArray()
        for (i in 0 until current.length()) {
            val item = current.getJSONObject(i)
            if (item.optString("url") != url) updated.put(item)
        }
        prefs.edit().putString("bookmarks", updated.toString()).apply()
        return updated.toString()
    }

    @JavascriptInterface
    fun getApiKey(): String = prefs.getString("tmdbApiKey", "") ?: ""

    @JavascriptInterface
    fun setApiKey(key: String): Boolean {
        prefs.edit().putString("tmdbApiKey", key).apply()
        return true
    }

    @JavascriptInterface
    fun getStartUrl(): String = prefs.getString("startUrl", "https://www.cineby.app") ?: "https://www.cineby.app"

    @JavascriptInterface
    fun setStartUrl(url: String): Boolean {
        prefs.edit().putString("startUrl", url).apply()
        return true
    }

    @JavascriptInterface
    fun getStoragePath(): String = "Android SharedPreferences (internal)"
}
