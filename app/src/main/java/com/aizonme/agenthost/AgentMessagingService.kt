package com.aizonme.agenthost

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * The "doorbell" for the agent.
 *
 * The device sits idle until a high-priority FCM **data** message arrives (sent
 * by the relay that receives Telegram webhooks). [onMessageReceived] then spins
 * up [AgentService] to run the job and hands it the payload. High-priority data
 * messages are one of the few signals allowed to start a foreground service from
 * the background on Android 12+, which is what makes the start-on-job /
 * stop-when-idle model work without an always-on listener.
 *
 * Note: this only fires for **data** messages. A message with a `notification`
 * block delivered while the app is backgrounded goes straight to the tray and
 * does NOT call this method — the relay must send data-only, high-priority
 * messages.
 */
class AgentMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "AgentMessaging"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // The relay encodes the job in the data payload. Accept either key.
        val command = message.data["command"] ?: message.data["prompt"]
        Log.i(TAG, "Push received; starting agent (command=${command ?: "<none>"})")
        AgentService.start(this, command)
    }

    override fun onNewToken(token: String) {
        // This device's FCM token. The relay needs it to target this device,
        // so report it to wherever the relay reads tokens from.
        Log.i(TAG, "New FCM token: $token")
        // TODO: send `token` to the relay/backend (e.g. POST to your server).
    }
}
