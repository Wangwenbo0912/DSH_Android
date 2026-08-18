package com.dshbox.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.common.Constants
import com.dshbox.app.common.AppResult
import com.dshbox.app.sandbox.BundledRuntimeInstaller
import com.dshbox.app.sandbox.SandboxState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the DSH sandbox lifecycle.
 *
 * The sandbox must not be tied to an Activity: when the user opens the DSH
 * WebUI in the system browser, this service keeps PRoot/Node/DSH alive in the
 * background and provides a restart action if the runtime crashes.
 */
class SandboxService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var restartInProgress = false

    private val sandboxManager
        get() = (application as DshApp).container.sandboxManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
        serviceScope.launch {
            sandboxManager.state.collectLatest { state ->
                updateNotification(state)
            }
        }
        serviceScope.launch { startSandbox() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Apply runtime overrides from Intent extras (host, port, timeout).
        Constants.applyIntentExtras(intent)
        when (intent?.action) {
            ACTION_RESTART -> serviceScope.launch { restartSandbox() }
            ACTION_STOP -> {
                serviceScope.launch {
                    sandboxManager.stop()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun startSandbox() {
        sandboxManager.initialize()
        // First boot: extract the runtime bundle embedded in the APK assets so
        // "install the APK, open the app" deploys everything with no adb or
        // separate bundle import. Falls back to the updates dir when absent.
        val container = (application as DshApp).container
        when (val bundled = BundledRuntimeInstaller(applicationContext, container.sandboxConfig).installIfAbsent()) {
            is AppResult.Success -> if (bundled.value) Log.i(TAG, "startSandbox: bundled runtime installed")
            is AppResult.Failure -> Log.w(TAG, "startSandbox: bundled runtime install failed: ${bundled.error.message}")
        }
        if (!sandboxManager.isRuntimeInstalled()) {
            val installed = sandboxManager.installFirstAvailableBundle()
            if (installed is AppResult.Success) {
                sandboxManager.promoteRuntimeBundle()
            }
        }
        if (sandboxManager.isRuntimeInstalled()) {
            sandboxManager.start()
        }
    }

    private suspend fun restartSandbox() {
        if (restartInProgress) return
        restartInProgress = true
        try {
            sandboxManager.restart()
        } finally {
            restartInProgress = false
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: SandboxState) {
        val (title, text) = when (state) {
            SandboxState.READY -> getString(R.string.notification_title_running) to getString(R.string.notification_text_running)
            SandboxState.STARTING, SandboxState.INITIALIZING -> getString(R.string.notification_title_starting) to getString(R.string.notification_text_starting)
            SandboxState.RECOVERING -> getString(R.string.notification_title_starting) to getString(R.string.notification_text_recovering)
            SandboxState.ERROR -> getString(R.string.notification_title_starting) to getString(R.string.notification_text_error)
            SandboxState.STOPPED -> getString(R.string.notification_title_starting) to getString(R.string.notification_text_stopped)
            else -> getString(R.string.notification_title_starting) to getString(R.string.notification_text_fallback, Constants.DSH_BASE_URL)
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(
        contentTitle: String = getString(R.string.notification_title_running),
        contentText: String = getString(R.string.notification_text_running),
    ): Notification {
        val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(Constants.DSH_BASE_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val restartIntent = Intent(this, SandboxService::class.java).setAction(ACTION_RESTART)
        val restartPending = PendingIntent.getService(
            this,
            1,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, SandboxService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF10A37F.toInt())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notification_action_open), openPending)
            .addAction(0, getString(R.string.notification_action_restart), restartPending)
            .addAction(0, getString(R.string.notification_action_stop), stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "SandboxService"
        private const val CHANNEL_ID = "dsh_sandbox"
        private const val NOTIFICATION_ID = 1

        const val ACTION_RESTART = "com.dshbox.app.action.RESTART_DSH"
        const val ACTION_STOP = "com.dshbox.app.action.STOP_SANDBOX"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SandboxService::class.java),
            )
        }

        fun restart(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_RESTART),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
