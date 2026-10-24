package me.rerere.rikkahub.service.mission

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.MISSION_ACTIVE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R

private const val TAG = "MissionFgs"

/**
 * 批 H — 任务轮的常驻前台服务。
 *
 * 为什么需要它：`ChatGenerationForegroundService` 只覆盖「一次生成正在进行」的窗口。
 * 任务真正的长跑是「轮与轮之间」——督导等事件、跑检查命令、发下一轮，这中间可能几分钟
 * 没有任何生成在跑。那段时间进程没有前台服务保护，被厂商省电策略回收后只能等重启扫描，
 * 表现就是「任务莫名其妙停在某一轮」。
 *
 * 这个服务不持有任何任务状态（状态的权威是 run 台账与任务表），只做两件事：
 * 1. 有任务在跑时让进程保持前台优先级；
 * 2. 通知用户「现在有任务在跑、跑到第几轮」，点一下能回任务详情。
 *
 * 刻意不加 wake lock：任务轮次的生成本身有 ChatGenerationForegroundService 的锁，
 * 轮间的等待不需要 CPU 常亮——这里要的是「不被杀」，不是「不许睡」。
 */
class MissionForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!startForegroundCompat()) {
            // 前台服务被系统拒绝（通知权限被关、后台启动限制）：直接停，别留一个空壳服务。
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // 不用 START_STICKY：进程被杀后重调度一个没有任务在跑的服务毫无意义，
        // 真正的恢复走 MissionWatchdogWorker。
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(): Boolean = try {
        val text = summaryText(active.values.firstOrNull().orEmpty(), active.size)
            .ifBlank { getString(R.string.notification_channel_mission_active) }
        val notification = NotificationCompat.Builder(this, MISSION_ACTIVE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_channel_mission_active))
            .setContentText(text)
            .setContentIntent(buildLaunchPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        true
    } catch (error: Throwable) {
        // Android 12+ 后台启动限制 / 用户关掉通知权限都会走到这里。任务本身继续跑，
        // 只是失去前台保护——记一条日志，由 watchdog 兜底。
        Log.w(TAG, "Unable to start mission foreground service", error)
        false
    }

    private fun buildLaunchPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        packageManager.getLaunchIntentForPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val ACTION_RECONCILE = "me.rerere.rikkahub.action.RECONCILE_MISSION_FGS"
        private const val NOTIFICATION_ID = 2003
        private const val BATTERY_NOTIFICATION_ID = 2004

        /**
         * 正在跑的任务：missionId → 通知文案。
         *
         * 用一个集合而不是布尔，是因为手机上可能并行跑两个任务（一个定时、一个手动）。
         * 收尾时必须按 id 摘掉自己那一项，否则先结束的任务会把另一个任务的前台保护一起关掉。
         */
        private val active = java.util.concurrent.ConcurrentHashMap<String, String>()

        /**
         * 有任务开始跑：拉起前台服务并登记。
         *
         * 幂等——重复调用只会刷新通知文案。启动失败不影响任务本身。
         */
        fun start(context: Context, missionId: String, label: String) {
            active[missionId] = label
            requestReconcile(context)
            maybeWarnBatteryOptimization(context)
        }

        /** 轮次 / 阶段变化时刷新通知（不改变运行状态）。 */
        fun update(context: Context, missionId: String, label: String) {
            if (!active.containsKey(missionId)) return
            active[missionId] = label
            requestReconcile(context)
        }

        /** 某个任务收尾：摘掉它的登记；没有别的任务就收掉前台服务。 */
        fun stop(context: Context, missionId: String) {
            active.remove(missionId)
            requestReconcile(context)
        }

        /**
         * 批 H — 省电白名单引导。
         *
         * 前台服务不是免死金牌：国产 ROM 的「省电策略 / 后台清理」会照样把进程干掉。真正
         * 稳妥的做法是把 App 加进电池优化白名单，但这一步只能用户自己点，所以这里做的是
         * **一次性的、可忽略的提醒**（每个进程最多一次），而不是每次启动都弹。
         *
         * 用系统列表页 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` 而不是
         * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：后者要额外申请敏感权限、应用商店
         * 也有限制，而且把「要不要加白」的决定权直接交给一个弹窗，反而更容易被误点。
         */
        private var batteryWarned = false

        private fun maybeWarnBatteryOptimization(context: Context) {
            if (batteryWarned) return
            batteryWarned = true
            runCatching {
                val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                if (power.isIgnoringBatteryOptimizations(context.packageName)) return
                val notifications = NotificationManagerCompat.from(context)
                if (!notifications.areNotificationsEnabled()) return
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pending = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val notification = NotificationCompat.Builder(context, MISSION_ACTIVE_NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.small_icon)
                    .setContentTitle(context.getString(R.string.notification_channel_mission_active))
                    .setContentText("长任务可能被系统省电策略中断，建议把 Moxw 加入电池优化白名单")
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build()
                runCatching { notifications.notify(BATTERY_NOTIFICATION_ID, notification) }
            }.onFailure { Log.w(TAG, "battery optimization hint failed", it) }
        }

        /** 通知正文：单任务显示自己的文案，多任务显示计数。 */
        internal fun summaryText(single: String, count: Int): String =
            if (count <= 1) single else "$count 个任务在运行"

        private fun requestReconcile(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, MissionForegroundService::class.java).apply {
                action = ACTION_RECONCILE
            }
            runCatching {
                if (active.isNotEmpty()) {
                    ContextCompat.startForegroundService(app, intent)
                } else {
                    // 已在运行就用普通 startService 投递「停止」这条状态；没在运行则会抛，
                    // 落到下面的 stopService 兜底。
                    app.startService(intent)
                }
            }.recoverCatching {
                app.stopService(intent)
            }.onFailure {
                Log.w(TAG, "Unable to reconcile mission foreground service", it)
            }
        }
    }
}
