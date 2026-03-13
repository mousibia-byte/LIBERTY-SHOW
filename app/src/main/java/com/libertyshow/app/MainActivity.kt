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
        
        // Full-screen mode
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        // Request All Files Access for Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }

        webView = WebView(this).also { setContentView(it) }
        configureWebView()

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
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.addJavascriptInterface(AndroidBridge(), "Android")
        
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.startsWith("magnet:")) {
                    view.evaluateJavascript("openStorageModal('${url.replace("'", "\\' text-decoration: none;')}')", null)
                    return true
                }
                return false
            }
        }
    }

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
            val st = StatFs(path)
            val free = (st.availableBlocksLong * st.blockSizeLong).toDouble() / (1 shl 30)
            return JSONObject().put("freeGB", "%.2f".format(free).toDouble()).toString()
        }

        @JavascriptInterface
        fun requestSafPermission(path: String): String {
            runOnUiThread { safLauncher.launch(null) }
            return "pending"
        }

        @JavascriptInterface
        fun startTorrentEngine(magnet: String, savePath: String): String {
            val id = "tor_${System.currentTimeMillis()}"
            val intent = Intent(this@MainActivity, TorrentService::class.java).apply {
                putExtra("magnet", magnet)
                putExtra("savePath", savePath)
                putExtra("id", id)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            return JSONObject().put("id", id).put("name", magnetName(magnet)).toString()
        }

        @JavascriptInterface
        fun showNativeToast(msg: String) = 
            runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun detectUsb(): String? =
        File("/storage").listFiles()?.firstOrNull { dir ->
            val n = dir.name.lowercase()
            dir.isDirectory && n != "emulated" && n != "self" && dir.canRead()
        }?.absolutePath

    private fun magnetName(magnet: String): String {
        val dn = Regex("dn=([^&]+)").find(magnet)?.groupValues?.get(1)
        return if (dn != null) URLDecoder.decode(dn, "UTF-8") else "Unknown"
    }
}
