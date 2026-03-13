package com.libertyshow.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var usbMountPoint: String? = null

    private val safLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            usbMountPoint = uri.toString()
            webView.evaluateJavascript("window.onSafGranted('$uri')", null)
        } else {
            webView.evaluateJavascript("window.onSafDenied()", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full-screen immersive for TV & phone
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        webView = WebView(this).also { setContentView(it) }
        configureWebView()

        // Handle magnet link from intent (e.g., from browser)
        intent?.data?.toString()?.let { uri ->
            if (uri.startsWith("magnet:")) {
                webView.evaluateJavascript("openStorageModal('${uri.replace("'", "\\'")}');", null)
            }
        }
        webView.loadUrl("file:///android_asset/web/index.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = true
            allowFileAccessFromFileURLs      = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode                        = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls  = false
            displayZoomControls  = false
        }
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        webView.addJavascriptInterface(AndroidBridge(), "Android")

        webView.webChromeClient = object : WebChromeClient() {
            private var customView: android.view.View? = null
            private var cb: CustomViewCallback? = null
            override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
                customView = view; cb = callback; setContentView(view)
            }
            override fun onHideCustomView() {
                setContentView(webView); customView = null; cb?.onCustomViewHidden()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.startsWith("magnet:")) {
                    val escaped = url.replace("'", "\\'")
                    view.evaluateJavascript("openStorageModal('$escaped')", null)
                    return true
                }
                return false
            }
        }
    }

    // ── Android ↔ JS Bridge ──────────────────────────────────────────
    inner class AndroidBridge {

        @JavascriptInterface
        fun getUsbStatus(): String {
            val point = detectUsb()
            return if (point != null) {
                val st = StatFs(point)
                val free = (st.availableBlocksLong * st.blockSizeLong).toDouble() / (1 shl 30)
                JSONObject().put("available", true).put("freeGB", "%.2f".format(free).toDouble())
                    .put("label", File(point).name).put("path", point).toString()
            } else {
                JSONObject().put("available", false).put("freeGB", 0).toString()
            }
        }

        @JavascriptInterface
        fun getInternalFree(): String {
            val path = Environment.getExternalStorageDirectory().absolutePath
            val st   = StatFs(path)
            val free = (st.availableBlocksLong * st.blockSizeLong).toDouble() / (1 shl 30)
            return JSONObject().put("freeGB", "%.2f".format(free).toDouble()).toString()
        }

        @JavascriptInterface
        fun requestSafPermission(path: String): String {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runOnUiThread { safLauncher.launch(null) }
                "pending"
            } else "granted"
        }

        @JavascriptInterface
        fun startTorrentEngine(magnet: String, savePath: String): String {
            val id   = "tor_${System.currentTimeMillis()}"
            val name = magnetName(magnet)
            // Launch background download service
            val svc = Intent(this@MainActivity, TorrentService::class.java).apply {
                putExtra("magnet",   magnet)
                putExtra("savePath", savePath)
                putExtra("id",       id)
            }
            startForegroundService(svc)
            return JSONObject().put("id", id).put("name", name).toString()
        }

        @JavascriptInterface fun pauseTorrent(id: String)  = TorrentService.pause(id)
        @JavascriptInterface fun resumeTorrent(id: String) = TorrentService.resume(id)
        @JavascriptInterface fun deleteTorrent(id: String) = TorrentService.delete(id)

        @JavascriptInterface
        fun openNativePlayer(filePath: String): Boolean {
            return try {
                val file = File(filePath)
                if (!file.exists()) return false
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity, "${packageName}.fileprovider", file
                )
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                true
            } catch (e: Exception) { false }
        }

        @JavascriptInterface
        fun clearCache() = runOnUiThread {
            webView.clearCache(false)
            cacheDir.deleteRecursively()
        }

        @JavascriptInterface
        fun showNativeToast(msg: String) =
            runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
    }

    // ── Helpers ──────────────────────────────────────────────────────
    private fun detectUsb(): String? =
        File("/storage").listFiles()?.firstOrNull { dir ->
            val n = dir.name.lowercase()
            dir.isDirectory && n != "emulated" && n != "self" && dir.canRead()
        }?.absolutePath

    private fun magnetName(magnet: String): String {
        val dn = Regex("dn=([^&]+)").find(magnet)?.groupValues?.get(1)
        if (dn != null) return try { URLDecoder.decode(dn, "UTF-8") } catch (e: Exception) { dn }
        val h = Regex("btih:([a-fA-F0-9]+)", RegexOption.IGNORE_CASE).find(magnet)?.groupValues?.get(1)
        return if (h != null) "Torrent_${h.take(8).uppercase()}" else "Unknown"
    }

    // ── TV Back Button ───────────────────────────────────────────────
    @Deprecated("Deprecated")
    override fun onBackPressed() {
        webView.evaluateJavascript(
            "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))", null
        )
    }

    // ── Memory Management ────────────────────────────────────────────
    override fun onLowMemory() {
        super.onLowMemory()
        webView.clearCache(false)
        webView.evaluateJavascript("performCleanup()", null)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) webView.clearCache(false)
    }
}
