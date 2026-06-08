package com.aizonme.agenthost

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * The agent's "doorbell": a high-priority FCM **data** message (sent by the
 * relay that receives Telegram webhooks) wakes the device here.
 *
 * When the data payload carries a `command`, it is handed to [AgentService],
 * which acquires a wake lock, forwards the command to the embedded Node runtime
 * via [NodeEngineManager.sendCommandToNode], and releases the lock once Node
 * replies.
 *
 * Only **data** messages reach [onMessageReceived] while backgrounded — the
 * relay must send data-only, high-priority messages (a `notification` block
 * would be routed straight to the system tray instead).
 */
class PushReceiver : FirebaseMessagingService() {

    companion object {
        private const val TAG = "PushReceiver"

        /** Wake lock is held for at most 3 minutes while Node handles a command. */
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 1000L // 3 minutes
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val command = message.data["command"]
        if (command.isNullOrEmpty()) {
            Log.d(TAG, "Push without a 'command' key; ignoring")
            return
        }

        Log.i(TAG, "Command push received; dispatching to AgentService")
        // The service is a separate component, so we reach it via an intent
        // rather than calling acquireWakeLock()/sendCommandToNode() directly.
        AgentService.runCommand(this, command, WAKE_LOCK_TIMEOUT_MS)
    }

    override fun onNewToken(token: String) {
        // This device's FCM token. The relay needs it to target this device.
        Log.i(TAG, "New FCM token: $token")
        // TODO: report `token` to the relay/backend.
    }
}
