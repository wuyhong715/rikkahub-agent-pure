package me.rerere.rikkahub.runtime
import me.rerere.rikkahub.runtime.AgentTurnTracker

/**
 * Host-side record of **what the agent actually did to which app** during a turn.
 *
 * Why the host records this instead of asking the model to: screen automation mostly runs on a
 * model with deep thinking off, and a model that never "remembers" to write a note still needs
 * the next run to be cheaper. So the facts that are cheap to observe — the way the app was
 * entered, which selector was used, whether it resolved — are captured here by the tools
 * themselves and flushed to the app's cold-memory playbook at the end of the turn
 * (`ChatService.flushAutomationPlaybook`). The model only ever has to *read* the result.
 *
 * Bounded and per-turn: [reset] at the start of a generation, [drain] at the end. When the model
 * *does* want to add prose to the playbook it still can, with `memory_write`; the two live in
 * separate parts of the file (see `AppPlaybookFile`).
 *
 * Privacy: only structural selectors (`view_id`), the short text of the control that was acted
 * on (≤ [MAX_VALUE_CHARS]), and the activity class of the screen are recorded — never typed
 * input, never whole-screen text, never message contents.
 */
object AutomationRecorder {

    /** Observation lines kept per package before the oldest are dropped. */
    const val MAX_LINES_PER_PACKAGE = 40

    /** Selector / label values are clipped to this many characters. */
    const val MAX_VALUE_CHARS = 40

    /** One app's observations, ready to merge into its playbook. */
    data class Snapshot(val packageName: String, val lines: List<String>)

    private class Log {
        val lines = LinkedHashSet<String>()
    }

    private val logs = java.util.concurrent.ConcurrentHashMap<String, Log>()

    /**
     * 键是「会话 + 应用」：观测必须跟着产生它的那个会话走。
     *
     * 只按包名存时，两个并行会话（用户聊天 + 子代理/任务轮次）会写进同一个桶：先结束的那一方
     * `drain()` 会把另一方还没 flush 的观测一起拿走，再写进自己的工作区。
     * `scope == null` 保留给没有会话上下文的调用方（测试、旧路径），落进全局桶。
     */
    private fun keyOf(scope: String?, packageName: String): String =
        scope.orEmpty() + '\u0000' + packageName

    private fun packageOf(key: String): String = key.substringAfter('\u0000')

    /** 丢掉某个会话的观测（null = 全部）。生成开始时调，与 AgentTurnTracker.reset() 同步。 */
    fun reset(scope: String? = null) {
        if (scope == null) {
            logs.clear()
            return
        }
        val prefix = scope + '\u0000'
        logs.keys.filter { it.startsWith(prefix) }.forEach { logs.remove(it) }
    }

    private fun add(scope: String?, packageName: String, line: String) {
        if (packageName.isBlank()) return
        val log = logs.computeIfAbsent(keyOf(scope, packageName)) { Log() }
        synchronized(log) {
            log.lines.add(line)
            while (log.lines.size > MAX_LINES_PER_PACKAGE) {
                val oldest = log.lines.first()
                log.lines.remove(oldest)
            }
        }
    }

    /** Records how an app was entered: `launch_app`, `launch_activity`, `open_url`. */
    fun recordEntry(packageName: String, via: String, scope: String? = null) {
        add(scope, packageName, "- 入口 $via")
    }

    /**
     * Records one atomic action against [screen] (the activity class it happened on).
     * [selector] is a label from [selectorLabel] / [nodeLabel]; null when the agent targeted a
     * node_id, in which case only the action and outcome carry information.
     */
    fun recordAction(
        packageName: String,
        screen: String?,
        action: String,
        selector: String?,
        ok: Boolean,
        scope: String? = null,
    ) {
        val where = screen?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: "?"
        val what = if (selector.isNullOrBlank()) action else "$action $selector"
        add(scope, packageName, "- $where｜$what｜${if (ok) "ok" else "miss"}")
    }

    /** A compact label for a `by` + `value` selector, or null when there is nothing to record. */
    fun selectorLabel(by: String?, value: String?): String? = when (by) {
        "text" -> value?.takeIf { it.isNotBlank() }?.let { "text=\"${clip(it)}\"" }
        "content_description" -> value?.takeIf { it.isNotBlank() }?.let { "cd=\"${clip(it)}\"" }
        "view_id_resource_name" -> value?.takeIf { it.isNotBlank() }?.let { "vid=${clip(it)}" }
        else -> null
    }

    /**
     * A compact label for a node the agent reached by node_id: the structural `view_id` first,
     * then its short text, then its content description.
     */
    fun nodeLabel(viewId: String?, text: String?, contentDescription: String?): String? = when {
        !viewId.isNullOrBlank() -> "vid=${clip(viewId)}"
        !text.isNullOrBlank() -> "text=\"${clip(text)}\""
        !contentDescription.isNullOrBlank() -> "cd=\"${clip(contentDescription)}\""
        else -> null
    }

    private fun clip(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_VALUE_CHARS)

    /**
     * 取走某个会话（null = 全部）观测到的应用并清除，保证一次 flush 不会重复写。
     *
     * 只取自己的那一份：以前任何一方 flush 都把整个进程的观测取走，别人的轮次就再也拿不到。
     */
    fun drain(scope: String? = null): List<Snapshot> {
        val prefix = scope?.let { it + '\u0000' }
        val selected = logs.entries.filter { prefix == null || it.key.startsWith(prefix) }
        val snapshot = selected.mapNotNull { (key, log) ->
            val lines = synchronized(log) { log.lines.toList() }
            if (lines.isEmpty()) null else Snapshot(packageOf(key), lines)
        }
        selected.forEach { logs.remove(it.key) }
        return snapshot
    }
}
