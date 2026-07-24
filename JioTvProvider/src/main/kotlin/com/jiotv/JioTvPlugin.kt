package com.jiotv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import android.util.Log

@CloudstreamPlugin
class JioTvPlugin: Plugin() {
    override fun load(context: Context) {
        // Start the NanoHTTPD proxy server
        try {
            if (!JioProxyServer.isRunning) {
                JioProxyServer.startServer(context)
            }
        } catch (e: Exception) {
            Log.e("JioTV", "Failed to start proxy server", e)
        }
        
        // Register the provider
        registerMainAPI(JioTvProvider())
    }
}