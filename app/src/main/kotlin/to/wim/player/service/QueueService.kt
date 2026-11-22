package to.wim.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import to.wim.player.MainActivity
import to.wim.player.PlayerApp
import to.wim.player.R
import to.wim.player.engine.QueueState

/**
 * Keeps the app alive while the queue is being filled, with a notification that shows the
 * progress and has a Stop button. It ends itself when the queue is done or stopped.
 */
class QueueService : LifecycleService() {
    private val graph get() = (application as PlayerApp).graph
    private var watching = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            graph.queue.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(graph.queue.state.value),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (watching) return START_NOT_STICKY
        watching = true
        lifecycleScope.launch {
            graph.queue.state.collect { state ->
                if (!state.active) {
                    graph.flushCaches()
                    ServiceCompat.stopForeground(this@QueueService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(state: QueueState): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_QUEUE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, QueueService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val matching = state.playlistId == null
        val title =
            if (matching) {
                getString(R.string.queue_progress_matching, state.processed, state.total)
            } else {
                getString(R.string.queue_progress_adding, state.queued, state.found)
            }
        val max = (if (matching) state.total else state.found).coerceAtLeast(1)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setProgress(max, if (matching) state.processed else state.queued, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "queue"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "to.wim.player.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, QueueService::class.java))
        }
    }
}
