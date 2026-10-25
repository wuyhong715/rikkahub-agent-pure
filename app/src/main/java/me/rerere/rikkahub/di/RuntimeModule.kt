package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.runtime.AppHostInstanceAdapter
import me.rerere.rikkahub.data.runtime.InstanceRegistry
import me.rerere.rikkahub.data.runtime.ShizukuInstanceAdapter
import me.rerere.rikkahub.data.runtime.SshInstanceAdapter
import me.rerere.rikkahub.data.runtime.TermuxInstanceAdapter
import me.rerere.rikkahub.data.runtime.WorkspaceInstanceAdapter
import org.koin.dsl.module

/**
 * 运行实例的装配。
 *
 * 注册表用 `AppScope`（进程级）而不是页面作用域：实例状态属于整个应用，headless 路径
 * （Telegram / cron / workflow / 子代理）读到的必须是同一份，否则会出现"UI 绿、定时任务红"。
 */
val runtimeModule = module {
    single { AppHostInstanceAdapter(get<Context>()) }
    single { ShizukuInstanceAdapter(get<Context>()) }
    single { WorkspaceInstanceAdapter(get(), get()) }
    single { SshInstanceAdapter(get<Context>(), get()) }
    single { TermuxInstanceAdapter(get<Context>()) }
    // 实例自己的偏好（目前只有目录裁剪档位）。构造即同步播种运行时值。
    single { me.rerere.rikkahub.data.preferences.InstancePreferences(get<Context>()) }

    single {
        InstanceRegistry(
            adapters = listOf(
                get<AppHostInstanceAdapter>(),
                get<ShizukuInstanceAdapter>(),
                get<WorkspaceInstanceAdapter>(),
                get<SshInstanceAdapter>(),
                get<TermuxInstanceAdapter>(),
            ),
            scope = get<AppScope>(),
        )
    }
}
