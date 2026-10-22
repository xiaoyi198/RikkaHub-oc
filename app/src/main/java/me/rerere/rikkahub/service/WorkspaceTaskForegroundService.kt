package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import org.koin.android.ext.android.inject

/** Like TermuxService, owns user-visible command execution independently of chat generation. */
class WorkspaceTaskForegroundService : Service() {
    private val tasks: WorkspaceTaskManager by inject()
    private val appScope: AppScope by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "工作区任务", NotificationManager.IMPORTANCE_LOW),
        )
        updateNotification(0, null)
        scope.launch {
            tasks.awaitReady()
            tasks.tasks.map { state ->
                state.values.filter { it.active }.let {
                    it.size to
                        it.lastOrNull()?.conversationId
                }
            }
                .distinctUntilChanged().collect { (count, owner) ->
                    if (count == 0) {
                        if (latestStartId > 0) stopSelfResult(latestStartId)
                    } else {
                        updateNotification(count, owner)
                    }
                }
        }
    }

    private fun updateNotification(count: Int, owner: String?) {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            owner?.let { putExtra("conversationId", it) }
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle("工作区任务")
            .setContentText(if (count == 0) "正在启动任务…" else "$count 个任务正在运行，点击聊天可查看或停止")
            .setContentIntent(PendingIntent.getActivity(
                this,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .setOnlyAlertOnce(true).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Exception) {
            appScope.launch {
                tasks.tasks.value.values.filter { it.active }.forEach {
                    tasks.engine.stop(
                        it.id,
                        it.conversationId
                    )
                }
            }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        scope.launch {
            tasks.awaitReady()
            if (tasks.tasks.value.values.none { it.active }) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) {
        appScope.launch {
            tasks.tasks.value.values.filter { it.active }.forEach {
                tasks.engine.stop(
                    it.id,
                    it.conversationId
                )
            }
        }
        stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "workspace_tasks"
        private const val NOTIFICATION_ID = 2108
        fun ensureStarted(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, WorkspaceTaskForegroundService::class.java))
            true
        }.getOrDefault(false)
    }
}
