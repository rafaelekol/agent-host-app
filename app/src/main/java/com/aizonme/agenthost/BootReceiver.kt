package com.aizonme.agenthost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the agent after a device reboot or an app update.
 *
 * Listens for:
 *  - [Intent.ACTION_BOOT_COMPLETED] — fired once the device finishes booting.
 *  - [Intent.ACTION_MY_PACKAGE_REPLACED] — fired after this app is updated.
 *
 * Both deliveries place the app on a short temporary allowlist, which is what
 * permits starting a foreground service from the background here.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        Log.i(TAG, "Received $action; launching AgentService")
        try {
            AgentService.start(context)
        } catch (e: Exception) {
            // On Android 12+ a background foreground-service start can be
            // rejected (ForegroundServiceStartNotAllowedException) if the app
            // isn't currently allowlisted. Never let that crash the broadcast.
            Log.e(TAG, "Could not start AgentService for $action", e)
        }
    }
}
