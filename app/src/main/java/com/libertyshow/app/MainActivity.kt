
package com.libertyshow.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var usbMountPoint: String? [span_3](start_span)= null[span_3](end_span)

    private val safLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -[span_4](start_span)>[span_4](end_span)
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            usbMountPoint = uri.toString()
            [span_5](start_span)webView.evaluateJavascript("window.onSafGranted('$uri')", null)[span_5](end_span)
        } else {
            webView.evaluateJavascript("window.onSafDenied()", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // إعداد الشاشة الكاملة لأجهزة الـ TV والهاتف
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        [span_6](start_span))

        // --- إضافة كود إصلاح مشكلة الـ 0MB والصلاحيات هنا ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivity(intent)
                }
            }
        }
        // ------------------------------------------------

        webView = WebView(this).also { setContentView(it) }
        configureWebView()

        // معالجة روابط الماجنيت عند فتح التطبيق
        intent?.data?.toString()?.let { uri ->
            if (uri.startsWith("magnet:")) {
                webView.evaluateJavascript("openStorageModal('${uri.replace("'", "\\'")}');", null)[span_6](end_span)
            }
        }
        webView.loadUrl("file:///android_asset/web/index.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            [span_7](start_span)javaScriptEnabled                = true[span_7](end_span)
            [span_8](start_span)domStorageEnabled                = true[span_8](end_span)
            [span_9](start_span)allowFileAccess                  = true[span_9](end_span)
            [span_10](start_span)allowFileAccessFromFileURLs      = true[span_10](end_span)
            [span_11](start_span)mediaPlaybackRequiresUserGesture = false[span_11](end_span)
            [span_12](start_span)cacheMode                        = WebSettings.LOAD_DEFAULT[span_12](end_span)
            setSupportZoom(false)
            builtInZoomControls  = false
            displayZoomControls  = false
        }
 
        [span_13](start_span)webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)[span_13](end_span)
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        webView.addJavascriptInterface(AndroidBridge(), "Android")

        webView.webChromeClient = object : WebChromeClient() {
            private var customView: android.view.View? [span_14](start_span)= null[span_14](end_span)
            private var cb: CustomViewCallback? [span_15](start_span)= null[span_15](end_span)
            override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
                [span_16](start_span)customView = view; cb = callback; setContentView(view)[span_16](end_span)
            }
            override fun onHideCustomView() {
                [span_17](start_span)setContentView(webView); customView = null; cb?.onCustomViewHidden()[span_17](end_span)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.startsWith("magnet:")) {
                    [span_18](start_span)val escaped = url.replace("'", "\\'")[span_18](end_span)
                    view.evaluateJavascript("openStorageModal('$escaped')", null)
                    return true
                }
                return false
            }
        }
    }

    // ── جسر التواصل بين الأندرويد والـ JavaScript ──────────────────
    inner class AndroidBridge {

        @JavascriptInterface
        fun getUsbStatus(): String {
            val point = detectUsb()
            return if (point != null) {
                [span_19](start_span)val st = StatFs(point)[span_19](end_span)
                val free = (st.availableBlocksLong * st.blockSizeLong).toDouble() / (1 shl 30)
                JSONObject().put("available", true).put("freeGB", "%.2f".format(free).toDouble())
                    .put("label", File(point).name).put("path", point).toString()
            } else {
                [span_20](start_span)JSONObject().put("available", false).put("freeGB", 0).toString()[span_20](end_span)
            }
        }

        @JavascriptInterface
        fun getInternalFree(): String {
            val path = Environment.getExternalStorageDirectory().absolutePath
            val st   = StatFs(path)
            val free = (st.availableBlocksLong * st.blockSizeLong).toDouble() / (1 shl 30)
            [span_21](start_span)return JSONObject().put("freeGB", "%.2f".format(free).toDouble()).toString()[span_21](end_span)
        }

        @JavascriptInterface
        fun requestSafPermission(path: String): String {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runOnUiThread { safLauncher.launch(null) }
                "pending"
            [span_22](start_span)} else "granted"[span_22](end_span)
        }

        @JavascriptInterface
        fun startTorrentEngine(magnet: String, savePath: String): String {
            val id   = "tor_${System.currentTimeMillis()}"
            val name = magnetName(magnet)
            val svc = Intent(this@MainActivity, TorrentService::class.java).apply {
                [span_23](start_span)putExtra("magnet",   magnet)[span_23](end_span)
                putExtra("savePath", savePath)
                putExtra("id",       id)
            }
            startForegroundService(svc)
            [span_24](start_span)return JSONObject().put("id", id).put("name", name).toString()[span_24](end_span)
        }

        @JavascriptInterface fun pauseTorrent(id: String)  = TorrentService.pause(id)
        @JavascriptInterface fun resumeTorrent(id: String) = TorrentService.resume(id)
        @JavascriptInterface fun deleteTorrent(id: String) = TorrentService.delete(id)

        @JavascriptInterface
        fun openNativePlayer(filePath: String): Boolean {
            return try {
                val file = File(filePath)
                [span_25](start_span)if (!file.exists()) return false[span_25](end_span)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity, "${packageName}.fileprovider", file
                )
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    [span_26](start_span)setDataAndType(uri, "video/*")[span_26](end_span)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                true
            } catch (e: Exception) { false }
        }

        @JavascriptInterface
        [span_27](start_span)fun clearCache() = runOnUiThread {[span_27](end_span)
            webView.clearCache(false)
            cacheDir.deleteRecursively()
        }

        @JavascriptInterface
        fun showNativeToast(msg: String) =
            runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun detectUsb(): String? =
        File("/storage").listFiles()?.firstOrNull { dir ->
            val n = dir.name.lowercase()
            dir.isDirectory && n != "emulated" && n != "self" && dir.canRead()
        [span_28](start_span)}?.absolutePath[span_28](end_span)

    private fun magnetName(magnet: String): String {
        val dn = Regex("dn=([^&]+)").find(magnet)?.groupValues?.get(1)
        if (dn != null) return try { URLDecoder.decode(dn, "UTF-8") } catch (e: Exception) { dn }
        [span_29](start_span)val h = Regex("btih:([a-fA-F0-9]+)", RegexOption.IGNORE_CASE).find(magnet)?.groupValues?.get(1)[span_29](end_span)
        return if (h != null) "Torrent_${h.take(8).uppercase()}" else "Unknown"
    }

    @Deprecated("Deprecated")
    override fun onBackPressed() {
        webView.evaluateJavascript(
            "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))", null
        )
    }

    override fun onLowMemory() {
        [span_30](start_span)super.onLowMemory()[span_30](end_span)
        webView.clearCache(false)
        webView.evaluateJavascript("performCleanup()", null)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) webView.clearCache(false)
    }
}
