package com.libertyshow.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var usbMountPoint: String? = null

    companion object {
        private const val STORAGE_PERMISSION_CODE = 1001
    }

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

    private val manageFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) {
            onStoragePermissionGranted()
        } else {
            onStoragePermissionDenied()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen immersive — API-safe
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        webView = WebView(this).also { setContentView(it) }
        configureWebView()
        checkStoragePermissions()
    }

    // ── Storage Permissions ───────────────────────────────────────────
    private fun checkStoragePermissions() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    onStoragePermissionGranted()
                } else {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    manageFilesLauncher.launch(intent)
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                val read  = android.Manifest.permission.READ_EXTERNAL_STORAGE
                val write = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                val denied = listOf(read, write).filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }
                if (denied.isEmpty()) onStoragePermissionGranted()
                else ActivityCompat.requestPermissions(
                    this, denied.toTypedArray(), STORAGE_PERMISSION_CODE
                )
            }
            else -> onStoragePermissionGranted()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                onStoragePermissionGranted()
            else
                onStoragePermissionDenied()
        }
    }

    private fun onStoragePermissionGranted() {
        intent?.data?.toString()?.let { uri ->
            if (uri.startsWith("magnet:")) {
                webView.evaluateJavascript(
                    "openStorageModal('${uri.replace("'", "\\'")}');", null
                )
            }
        }
        webView.loadUrl("file:///android_asset/web/index.html")
    }

    private fun onStoragePermissionDenied() {
        Toast.makeText(
            this,
            "يتطلب التطبيق إذن الوصول إلى التخزين لتشغيل التنزيلات.",
            Toast.LENGTH_LONG
        ).show()
        webView.loadUrl("file:///android_asset/web/index.html")
        webView.evaluateJavascript(
            "window.onStoragePermissionDenied && window.onStoragePermissionDenied()", null
        )
    }

    // ── WebView Setup ─────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs      = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode                        = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls  = false
            displayZoomControls  = false
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        webView.addJavascriptInterface(AndroidBridge(), "Android")

        webView.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null
            private var cb: CustomViewCallback? = null
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view; cb = callback; setContentView(view)
            }
            override fun onHideCustomView() {
                setContentView(webView); customView = null; cb?.onCustomViewHidden()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, req: WebResourceRequest
            ): Boolean {
                val url = req.url.toString()
                if (url.startsWith("magnet:")) {
                    view.evaluateJavascript(
                        "openStorageModal('${url.replace("'", "\\'")}');", null
                    )
                    return true
                }
                return false
            }
        }
    }

    // ── JS Bridge ─────────────────────────────────────────────────────
    inner class AndroidBridge {

        @JavascriptInterface
        fun getUsbStatus(): String {
            val point = detectUsb()
            return if (point != null) {
                val st   = StatFs(point)
                val free = (st.availableBlocksLong * st.blockSizeLong).toDouble() / (1 shl 30)
                JSONObject()
                    .put("available", true)
                    .put("freeGB", "%.2f".format(free).toDouble())
                    .put("label", File(point).name)
                    .put("path", point)
                    .toString()
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
            val svc  = Intent(this@MainActivity, TorrentService::class.java).apply {
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

    // ── Helpers ───────────────────────────────────────────────────────
    private fun detectUsb(): String? =
        File("/storage").listFiles()?.firstOrNull { dir ->
            val n = dir.name.lowercase()
            dir.isDirectory && n != "emulated" && n != "self" && dir.canRead()
        }?.absolutePath

    private fun magnetName(magnet: String): String {
        val dn = Regex("dn=([^&]+)").find(magnet)?.groupValues?.get(1)
        if (dn != null) return try {
            URLDecoder.decode(dn, "UTF-8")
        } catch (e: Exception) { dn }
        val h = Regex(
            "btih:([a-fA-F0-9]+)", RegexOption.IGNORE_CASE
        ).find(magnet)?.groupValues?.get(1)
        return if (h != null) "Torrent_${h.take(8).uppercase()}" else "Unknown"
    }

    // ── Back Button (TV) ──────────────────────────────────────────────
    @Deprecated("Deprecated")
    override fun onBackPressed() {
        webView.evaluateJavascript(
            "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))",
            null
        )
    }

    // ── Memory ────────────────────────────────────────────────────────
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
