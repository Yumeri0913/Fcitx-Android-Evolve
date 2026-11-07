package org.fcitx.fcitx5.android.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.fcitx.fcitx5.android.BuildConfig

class ClipboardPairingControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action.orEmpty()
        if (action != ACTION_START_PAIRING && action != LEGACY_ACTION_START_PAIRING) {
            Log.w(TAG, "ignoring unexpected action=$action")
            return
        }
        Log.i(TAG, "received clipboard pairing start request")
        ClipboardSyncService.startPairing(context)
    }

    companion object {
        const val TAG = "ClipboardPairing"
        val ACTION_START_PAIRING = "${BuildConfig.APPLICATION_ID}.action.START_CLIPBOARD_PAIRING"
        const val LEGACY_ACTION_START_PAIRING =
            "org.fcitx.fcitx5.android.action.START_CLIPBOARD_PAIRING"
    }
}
