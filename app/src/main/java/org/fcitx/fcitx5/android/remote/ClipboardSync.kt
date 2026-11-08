/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.remote

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * LAN clipboard sync: HTTP server, UDP discovery and pairing broadcast, running on threads of
 * the app process.
 *
 * It used to be a started Service, but Android 12+ refuses to start services while the app is
 * in background, which is the case when the process is started by the system binding the input
 * method (after a reboot, or after the process got killed). Threads are not restricted, and the
 * process stays alive while the input method is in use.
 */
object ClipboardSync {
    private const val TAG = "ClipboardSync"

    private var httpServer: ClipboardHttpServer? = null
    private var discoveryServer: ClipboardLanDiscoveryServer? = null
    private var pairingBroadcaster: ClipboardLanPairingBroadcaster? = null

    /** Start servers if not running yet. Idempotent. */
    @Synchronized
    fun start(context: Context) {
        if (httpServer != null) return
        val ctx = context.applicationContext
        runCatching {
            httpServer = ClipboardHttpServer(
                context = ctx,
                port = ClipboardLanDiscoveryServer.DEFAULT_HTTP_PORT,
                onClientAccess = ::stopPairing
            ).also { it.start() }
            discoveryServer = ClipboardLanDiscoveryServer(
                context = ctx,
                udpPort = ClipboardLanDiscoveryServer.DEFAULT_UDP_PORT,
                httpPort = ClipboardLanDiscoveryServer.DEFAULT_HTTP_PORT
            ).also { it.start() }
            pairingBroadcaster = ClipboardLanPairingBroadcaster(ctx)
            Log.i(TAG, "clipboard sync started")
        }.onFailure {
            Log.e(TAG, "failed to start clipboard sync", it)
        }
    }

    /** Broadcast this device on the LAN until a client connects */
    @Synchronized
    fun startPairing(context: Context) {
        start(context)
        pairingBroadcaster?.start()
        Log.i(TAG, "clipboard pairing mode enabled")
    }

    @Synchronized
    fun stopPairing() {
        if (pairingBroadcaster?.isRunning() == true) {
            pairingBroadcaster?.stop()
            Log.i(TAG, "clipboard pairing mode disabled")
        }
    }

    fun deviceId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty().ifBlank { "unknown-device" }
    }
}
