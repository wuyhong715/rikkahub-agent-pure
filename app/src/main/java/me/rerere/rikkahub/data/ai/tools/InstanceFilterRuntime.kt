package me.rerere.rikkahub.data.ai.tools

/**
 * 目录裁剪档位的进程级运行时值。
 *
 * 与 [me.rerere.rikkahub.data.preferences.TermuxRuntime] 同一套路：设置页写 DataStore，
 * `InstancePreferences` 在构造时同步播种到这里，之后跟随 DataStore 变化。这样
 * [InstanceToolFilter] 与 `ToolSurfaceResolver` 这些非挂起调用点不必持有设置流，
 * 也不必为了读一个枚举把签名撑开。
 */
object InstanceFilterRuntime {

    @Volatile
    var mode: InstanceFilterMode = InstanceToolFilter.DEFAULT_MODE
}
