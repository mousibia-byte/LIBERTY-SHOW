package com.libertyshow.app

import android.app.Application
import android.os.StrictMode

class LibertyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Allow file:// URIs for WebView in dev
        val policy = StrictMode.VmPolicy.Builder()
            .detectLeakedSqlLiteObjects()
            .detectLeakedClosableObjects()
            .build()
        StrictMode.setVmPolicy(policy)
    }
}
