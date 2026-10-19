package app.xfeedguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Visible background support, including a control entry point if access disconnects. */
class ProtectionService : Service() {
    companion object {
        private const val CHANNEL = "protection"
        private const val NOTIFICATION = 7
        private const val TOGGLE = "app.xfeedguard.TOGGLE_PROTECTION"
        @Volatile var running = false
            private set

        fun sync(context: Context, store: GuardStore) {
            val intent = Intent(context, ProtectionService::class.java)
            try {
                if (!store.backgroundEnabled) context.stopService(intent)
                else if (!running) context.startForegroundService(intent)
            } catch (_: RuntimeException) {
                store.backgroundError = "Background support could not start. Reopen X Feed Guard and check background settings."
            }
        }
    }

    private lateinit var store: GuardStore
    private val handler = Handler(Looper.getMainLooper())
    private var lastStatus: ProtectionStatus? = null
    private var lastRequested: Boolean? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!store.backgroundEnabled) { stopSelf(); return }
            updateNotification()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = GuardStore(this)
        if (store.backgroundEnabled) store.backgroundEnabled = true // Persist the existing user's migrated choice.
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Protection status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Ongoing background protection and quick on/off controls"
            setShowBadge(false)
        })
        try {
            val notification = notification(status())
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION, notification)
            running = true
            store.backgroundError = ""
        } catch (_: RuntimeException) {
            store.backgroundError = "Android blocked background support. Open X Feed Guard to retry."
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running || !store.backgroundEnabled) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == TOGGLE) {
            val enabled = !(store.blocking && store.observing)
            store.setProtection(enabled)
            if (enabled) ObservationService.current?.resetGuard() else ObservationService.current?.cancelCapture()
        }
        updateNotification()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 10_000)
        return START_STICKY
    }

    private fun status(): ProtectionStatus {
        val service = ObservationService.current
        return protectionStatus(store.blocking, store.observing, service != null,
            service?.pendingLabel != null, service?.supportedBlockingBuild == true)
    }

    private fun notification(status: ProtectionStatus): Notification {
        val controls = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getService(this, 1, Intent(this, ProtectionService::class.java).setAction(TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_guard)
            .setContentTitle(status.title).setContentText(status.detail)
            .setContentIntent(controls).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, if (store.blocking && store.observing) "Pause" else "Resume", toggle).build())
            .build()
    }

    private fun updateNotification() {
        val current = status()
        val requested = store.blocking && store.observing
        if (current != lastStatus || requested != lastRequested) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(current))
            lastStatus = current; lastRequested = requested
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
