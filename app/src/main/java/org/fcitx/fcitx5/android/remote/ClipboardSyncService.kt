package org.fcitx.fcitx5.android.remote

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import android.util.Log

class ClipboardSyncService : Service() {
    private val tag = "ClipboardSyncService"
    private var httpServer: ClipboardHttpServer? = null
    private var discoveryServer: ClipboardLanDiscoveryServer? = null
    private var pairingBroadcaster: ClipboardLanPairingBroadcaster? = null

    override fun onCreate() {
        super.onCreate()
        httpServer = ClipboardHttpServer(
            context = this,
            port = ClipboardLanDiscoveryServer.DEFAULT_HTTP_PORT,
            onClientAccess = ::stopPairing
        ).also { it.start() }
        discoveryServer = ClipboardLanDiscoveryServer(
            context = this,
            udpPort = ClipboardLanDiscoveryServer.DEFAULT_UDP_PORT,
            httpPort = ClipboardLanDiscoveryServer.DEFAULT_HTTP_PORT
        ).also { it.start() }
        pairingBroadcaster = ClipboardLanPairingBroadcaster(this)
        Log.i(tag, "clipboard sync service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PAIRING -> startPairing()
            ACTION_STOP_PAIRING -> stopPairing()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pairingBroadcaster?.stop()
        pairingBroadcaster = null
        discoveryServer?.stop()
        discoveryServer = null
        httpServer?.stop()
        httpServer = null
        Log.i(tag, "clipboard sync service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPairing() {
        pairingBroadcaster?.start()
        Log.i(tag, "clipboard pairing mode enabled")
    }

    private fun stopPairing() {
        if (pairingBroadcaster?.isRunning() == true) {
            pairingBroadcaster?.stop()
            Log.i(tag, "clipboard pairing mode disabled")
        }
    }

    companion object {
        fun start(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                appContext.startService(Intent(appContext, ClipboardSyncService::class.java))
            }.onFailure {
                Log.e("ClipboardSyncService", "failed to start clipboard sync service", it)
            }
        }

        fun startPairing(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                appContext.startService(
                    Intent(appContext, ClipboardSyncService::class.java).apply {
                        action = ACTION_START_PAIRING
                    }
                )
            }.onFailure {
                Log.e("ClipboardSyncService", "failed to start clipboard pairing", it)
            }
        }

        fun deviceId(context: Context): String {
            return Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ).orEmpty().ifBlank { "unknown-device" }
        }

        private const val ACTION_START_PAIRING =
            "org.fcitx.fcitx5.android.remote.action.START_CLIPBOARD_PAIRING"
        private const val ACTION_STOP_PAIRING =
            "org.fcitx.fcitx5.android.remote.action.STOP_CLIPBOARD_PAIRING"
    }
}
