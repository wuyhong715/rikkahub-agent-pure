package me.rerere.rikkahub.service.mission

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.agentrun.AgentRunBootRecovery
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.concurrent.TimeUnit

private const val TAG = "MissionWatchdog"

/**
 * 批 H — 任务看门狗。
 *
 * 补的是最尴尬的一种失败：任务在跑，进程被杀，手机安静下来，用户永远收不到通知。
 * 之前的恢复只在**进程重新起来时**发生（[AgentRunBootRecovery] + `MissionSupervisor.onBoot`），
 * 而「进程起来」恰恰是用户要主动打开 App——对一个号称无人值守的功能，这等于没有恢复。
 *
 * WorkManager 的周期性任务是唯一能不依赖用户、由系统按约束唤醒进程的入口。这个 worker
 * 不自己实现恢复逻辑，只做两件已存在的事：
 * 1. [AgentRunBootRecovery.runRecovery]：把超过阈值没动静的 in-flight run 判成 process_lost
 *    （顺带发一条聚合通知——这就是用户看到的「任务中断了」）；
 * 2. [MissionSupervisor.onBoot]：按台账状态决定续跑还是 park。
 *
 * 刻意不设网络约束：恢复判断是纯本地读库，没网也该跑；真正的续跑会被 token/时间预算再拦一次。
 * 15 分钟的间隔是 WorkManager 周期任务的下限，也是「任务中断后最多 15 分钟被发现」的承诺。
 */
class MissionWatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    override suspend fun doWork(): Result {
        return runCatching {
            get<AgentRunBootRecovery>().runRecovery()
            get<MissionSupervisor>().onBoot()
            Result.success()
        }.getOrElse { error ->
            // 不把扫描失败抛给 WorkManager 的重试机制：这里全是幂等扫描，下一周期再来即可。
            Log.w(TAG, "watchdog sweep failed", error)
            Result.success()
        }
    }

    companion object {
        /** 唯一周期任务的名称（KEEP 策略：重复 enqueue 不会叠加）。 */
        const val UNIQUE_NAME: String = "moxw_mission_watchdog"

        /** 扫描间隔：WorkManager 允许的最小周期。 */
        private const val INTERVAL_MINUTES = 15L

        /**
         * 排上看门狗。幂等——用 [ExistingPeriodicWorkPolicy.KEEP]，已有的调度不会被重置，
         * 所以每次冷启动都调用也不会把计时器一直往后推。
         */
        fun enqueue(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<MissionWatchdogWorker>(
                    INTERVAL_MINUTES,
                    TimeUnit.MINUTES,
                )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }.onFailure { Log.w(TAG, "unable to enqueue mission watchdog", it) }
        }
    }
}
