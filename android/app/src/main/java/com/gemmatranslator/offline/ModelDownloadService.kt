package com.gemmatranslator.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.util.concurrent.CancellationException

/** Foreground, resumable installation. Translating never starts this service. */
class ModelDownloadService : Service() {
    private lateinit var store: ModelStore
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var latestStartId = 0
    @Volatile private var paused = false

    override fun onCreate() {
        super.onCreate()
        store = ModelStore(File(filesDir, "models"))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Offline model setup", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_PAUSE) {
            paused = true
            store.cancel()
            worker?.interrupt()
            if (worker == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running) {
            if (worker == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        paused = false
        val notification = notification(DownloadProgress("Preparing offline setup", 0, 0, "Starting"))
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, notification)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ModelSetup")
            .also { it.acquire(2 * 60 * 60 * 1000L) }
        publish(SetupState(DownloadProgress("Preparing offline setup", 0, 0, "Starting")))
        worker = Thread({
            var terminal: SetupState? = null
            try {
                store.installAll { progress ->
                    publish(SetupState(progress))
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(progress))
                }
                terminal = SetupState(DownloadProgress("Setup complete", 1, 1, "Ready for offline use"), finished = true)
            } catch (error: Exception) {
                val message = if (paused || error is CancellationException) "Setup paused. Tap Resume setup to continue."
                    else "Setup stopped: ${error.message ?: error.javaClass.simpleName}. Tap Resume setup to retry."
                terminal = SetupState(DownloadProgress("Offline model setup", 0, 0, message), error = message)
            } finally {
                // Serialize completion with onStartCommand: no second installer may
                // begin while this worker is still writing or removing staging files.
                main.post {
                    running = false
                    wakeLock?.let { if (it.isHeld) it.release() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(latestStartId)
                    terminal?.let(::publish)
                }
            }
        }, "OfflineModelSetup").apply { start() }
        return START_NOT_STICKY
    }

    private fun publish(state: SetupState) {
        latest = state
        sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName))
    }

    private fun notification(progress: DownloadProgress): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, ModelDownloadService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val percent = if (progress.total > 0) ((progress.completed * 100) / progress.total).coerceIn(0, 100).toInt() else 0
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(progress.title)
            .setContentText(progress.stage)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, progress.total == 0L)
            .addAction(Notification.Action.Builder(null, "Pause", pause).build())
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        paused = true
        store.cancel()
        worker?.interrupt()
        stopSelf()
    }

    override fun onDestroy() {
        store.cancel()
        worker?.interrupt()
        wakeLock?.let { if (it.isHeld) it.release() }
        // Keep installation locked until the interrupted worker has cleaned up.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_UPDATE = "com.gemmatranslator.offline.MODEL_SETUP_UPDATE"
        const val ACTION_PAUSE = "com.gemmatranslator.offline.MODEL_SETUP_PAUSE"
        private const val CHANNEL = "offline-model-setup"
        private const val NOTIFICATION_ID = 42
        @Volatile var running = false
            private set
        @Volatile var latest: SetupState? = null
            private set
    }
}

data class SetupState(val progress: DownloadProgress, val finished: Boolean = false, val error: String? = null)
