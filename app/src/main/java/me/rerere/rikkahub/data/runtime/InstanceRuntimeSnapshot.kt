package me.rerere.rikkahub.data.runtime

/**
 * 进程内"此刻的实例状态"快照。
 *
 * 存在的理由：工具目录的装配（[me.rerere.rikkahub.data.ai.ToolSurfaceResolver]）和每轮的
 * 环境提示（`AppEnvironmentTransformer`）都是纯函数式的调用点，没有协程作用域，也不该为了
 * 读一次状态而在签名里穿一个注册表。注册表把自己合并后的视图发布到这里，两个读点直接读。
 *
 * 这跟 `TermuxRuntime` 的做法一致：进程级 `@Volatile` 快照，写入方唯一（注册表），读取方只读。
 * 默认空列表——**注册表没起来时什么都不滤、什么都不写进提示**，这是安全的缺省：宁可多给工具，
 * 也不要因为没数据而把能力藏起来。
 */
object InstanceRuntimeSnapshot {

    @Volatile
    private var snapshot: List<RuntimeInstance> = emptyList()

    val current: List<RuntimeInstance> get() = snapshot

    /** 只该由 [InstanceRegistry] 调用。 */
    fun publish(instances: List<RuntimeInstance>) {
        snapshot = instances
    }

    /** 测试与"重来一次"用：清空后所有读点回到缺省。 */
    fun clear() {
        snapshot = emptyList()
    }

    fun ofKind(kind: InstanceKind): List<RuntimeInstance> = snapshot.filter { it.id.kind == kind }
}
