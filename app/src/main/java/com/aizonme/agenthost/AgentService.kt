package com.aizonme.agenthost

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the embedded Node.js agent alive while a job is
 * running. It is meant to be started when work begins and stopped as soon as the
 * work is done — it is not a permanently-running service.
 *
 * Lifecycle:
 *  - [start] (or the "Start Agent Service" button) launches it as a foreground
 *    service of type `dataSync`, showing a persistent low-priority notification.
 *  - [stop] (or the notification's Stop action) tears it down: releases the wake
 *    lock and removes the notification.
 */
class AgentService : Service() {

    private lateinit var nodeEngine: NodeEngineManager
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "agent_service"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.aizonme.agenthost.action.START_AGENT"
        const val ACTION_STOP = "com.aizonme.agenthost.action.STOP_AGENT"

        /** Carries the job payload (e.g. the command/prompt from the push message). */
        const val EXTRA_COMMAND = "com.aizonme.agenthost.extra.COMMAND"

        /** Default wake-lock ceiling; a job should outlive this only deliberately. */
        private const val DEFAULT_WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L // 10 min

        /**
         * Starts the agent as a foreground service. Call when a job begins.
         * [command] is the work to run (e.g. the prompt delivered by FCM); null
         * for a manual/dev start with no job attached.
         */
        fun start(context: Context, command: String? = null) {
            val intent = Intent(context, AgentService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_COMMAND, command)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops the agent. Call when there is no more ongoing job. */
        fun stop(context: Context) {
            val intent = Intent(context, AgentService::class.java).setAction(ACTION_STOP)
            // The service is already running in the foreground, so delivering a
            // command intent is allowed even from the background.
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // The runtime is owned by the service for its whole lifetime.
        nodeEngine = NodeEngineManager(this)
        Log.i(TAG, "AgentService created; Node engine instantiated")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means START_STICKY re-delivered us after a process kill.
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Received STOP; shutting down")
            stopAgent()
            return START_NOT_STICKY
        }

        startForegroundWithNotification()
        // Keep the CPU awake while the job runs.
        acquireWakeLock(DEFAULT_WAKE_LOCK_TIMEOUT_MS)

        val command = intent?.getStringExtra(EXTRA_COMMAND)
        Log.i(TAG, "Starting job (command=${command ?: "<none>"})")

        // TODO: run the job on the Node runtime, e.g.
        //   nodeEngine.start(scriptPath, callback)   // first job boots the runtime
        //   command?.let { nodeEngine.sendCommandToNode(it) }
        // and call AgentService.stop(this) once the job reports completion so
        // the service does not linger while idle.

        return START_STICKY
    }

    override fun onDestroy() {
        // Safety net in case we are torn down without an explicit ACTION_STOP.
        releaseWakeLock()
        Log.i(TAG, "AgentService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Promotes the service to the foreground with the persistent notification. */
    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun stopAgent() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent Service",
            // Low importance keeps it silent and collapsed — no sound or peek.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification while the agent is running"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Agent is Listening")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    /**
     * Acquires a [PowerManager.PARTIAL_WAKE_LOCK] so the CPU keeps running while
     * the screen is off. [timeoutMs] bounds how long the lock is held as a
     * safeguard against leaks. Re-acquiring while held is a no-op.
     */
    fun acquireWakeLock(timeoutMs: Long) {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:AgentService"
        ).apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
        Log.i(TAG, "Wake lock acquired (timeout ${timeoutMs}ms)")
    }

    /** Releases the wake lock if held. Safe to call when none is held. */
    fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }
}
