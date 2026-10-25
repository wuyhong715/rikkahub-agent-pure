package me.rerere.rikkahub.data.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 探测结果的保鲜期。
 *
 * 分级不是拍脑袋：`observe()` 已经是本地状态了，`probe()` 才会联网或起进程。SSH 握手最贵
 * （1–3 s，超时更久），所以 5 分钟；工作区要拉起 proot，2 分钟；Shizuku 只是 binder 调用，
 * 1 分钟；App 宿主纯本地，视为总是新鲜。
 */
object ProbeTtl {
    fun forKind(kind: InstanceKind): Long = when (kind) {
        InstanceKind.APP_HOST -> 0L
        InstanceKind.SHIZUKU -> 60_000L
        InstanceKind.WORKSPACE -> 120_000L
        InstanceKind.SSH -> 300_000L
        InstanceKind.TERMUX -> 120_000L
    }

    fun isFresh(kind: InstanceKind, probedAtMs: Long?, nowMs: Long): Boolean {
        if (probedAtMs == null) return false
        val ttl = forKind(kind)
        if (ttl <= 0L) return true
        return nowMs - probedAtMs < ttl
    }
}

/**
 * 五个后端的唯一状态真值。
 *
 * 页面、体检、门控、提示词都从这里读，**不要各自去问后端**：否则会出现"设置页绿、定时任务红"。
 *
 * 两条并发规则：
 * - 同一实例的并发 [refresh] 只真正探一次（单飞），后来的等第一个的结果。
 * - 探测结果按 [ProbeTtl] 保鲜；[force] 才无视 TTL（用户点「重新检测」）。
 *
 * [scope] 应是进程级作用域（Koin 里的 `AppScope`），不是页面作用域：实例状态属于整个应用，
 * headless 路径（Telegram / cron / workflow / 子代理）读到的必须是同一份。
 */
class InstanceRegistry(
    private val adapters: List<InstanceAdapter>,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Probed(val instance: RuntimeInstance, val atMs: Long)

    private class Flight {
        val result = CompletableDeferred<List<RuntimeInstance>>()
        var started = false
    }

    private val probed = MutableStateFlow<Map<String, Probed>>(emptyMap())
    private val flights = HashMap<String, Flight>()
    private val lock = Mutex()

    private val observed: Flow<List<RuntimeInstance>> =
        if (adapters.isEmpty()) {
            flowOf(emptyList<RuntimeInstance>())
        } else {
            combine(adapters.map { it.observe() }) { lists -> lists.flatMap { it } }
        }

    /**
     * 合并后的实例列表，按 kind 顺序、同名再按名字排。稳定顺序很重要：设置页用它渲染，
     * 每次刷新都换顺序会让用户点错行。
     */
    val instances: StateFlow<List<RuntimeInstance>> =
        observed
            .combine(probed) { base, overrides -> overlay(base, overrides) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList<RuntimeInstance>())

    fun snapshot(): List<RuntimeInstance> = instances.value

    fun find(id: InstanceId): RuntimeInstance? = instances.value.firstOrNull { it.id == id }

    /** 此刻**真的能用**的实例（[RuntimeInstance.supports] 已含就绪判断）。 */
    fun byCapability(capability: Capability): List<RuntimeInstance> =
        instances.value.filter { it.supports(capability) }

    fun ofKind(kind: InstanceKind): List<RuntimeInstance> =
        instances.value.filter { it.id.kind == kind }

    /**
     * 重新探测一个实例。TTL 内且非 [force] 时直接返回缓存，不打扰后端。
     */
    suspend fun refresh(id: InstanceId, force: Boolean = false): RuntimeInstance? {
        val adapter = adapters.firstOrNull { it.kind == id.kind } ?: return null
        val cached = probed.value[id.serial]
        if (!force && ProbeTtl.isFresh(id.kind, cached?.atMs, clock())) {
            return cached?.instance ?: find(id)
        }
        val results = runSingleFlight(id.serial) { adapter.probe(id) }
        remember(results)
        return results.firstOrNull { it.id == id } ?: results.firstOrNull() ?: find(id)
    }

    /** 重新探测某后端的全部实例（SSH 有多台、工作区有多个）。 */
    suspend fun refreshKind(kind: InstanceKind, force: Boolean = false): List<RuntimeInstance> {
        val adapter = adapters.firstOrNull { it.kind == kind } ?: return emptyList()
        val current = ofKind(kind)
        val now = clock()
        val fresh = current.isNotEmpty() && current.all {
            ProbeTtl.isFresh(kind, probed.value[it.id.serial]?.atMs, now)
        }
        if (!force && fresh) return current
        val results = runSingleFlight(adapter.kind.name) { adapter.probe(null) }
        remember(results)
        return results.ifEmpty { ofKind(kind) }
    }

    suspend fun invoke(id: InstanceId, actionId: String): ActionResult {
        val adapter = adapters.firstOrNull { it.kind == id.kind } ?: return ActionResult.Unsupported
        return adapter.invoke(id, actionId)
    }

    fun actions(id: InstanceId): List<InstanceAction> =
        adapters.firstOrNull { it.kind == id.kind }?.actions(id).orEmpty()

    fun backedToolNames(kind: InstanceKind): Set<String> =
        adapters.firstOrNull { it.kind == kind }?.backedToolNames().orEmpty()

    /**
     * 丢掉探测缓存。传 null 表示全部。
     *
     * 同时通知适配器重算 `observe()`——binder 死亡、装/卸了 Termux、Room 里状态改了，
     * 都属于"便宜的观察结果本身过期了"。
     */
    fun invalidate(id: InstanceId? = null) {
        probed.update { current ->
            if (id == null) emptyMap() else current - id.serial
        }
        if (id == null) {
            adapters.forEach { it.invalidate() }
        } else {
            adapters.firstOrNull { it.kind == id.kind }?.invalidate()
        }
    }

    private fun remember(results: List<RuntimeInstance>) {
        if (results.isEmpty()) return
        val at = clock()
        probed.update { current ->
            current + results.associate { it.id.serial to Probed(it, at) }
        }
    }

    /**
     * 缓存覆盖观察值，但只在缓存**更新**时覆盖：后端自己的观察流是权威，探测只是补充
     * （比如 SSH 的 lastVerifiedAt 只有探测才知道）。
     */
    private fun overlay(
        base: List<RuntimeInstance>,
        overrides: Map<String, Probed>,
    ): List<RuntimeInstance> {
        val merged = LinkedHashMap<String, RuntimeInstance>(base.size + overrides.size)
        base.forEach { merged[it.id.serial] = it }
        overrides.forEach { (serial, probed) ->
            val current = merged[serial]
            val currentAt = current?.lastProbeAt ?: 0L
            if (current == null || probed.atMs >= currentAt) {
                merged[serial] = probed.instance
            }
        }
        return merged.values.sortedWith(
            compareBy({ it.id.kind.ordinal }, { it.displayName }, { it.id.key }),
        )
    }

    private suspend fun runSingleFlight(
        key: String,
        block: suspend () -> List<RuntimeInstance>,
    ): List<RuntimeInstance> {
        val flight = lock.withLock { flights.getOrPut(key) { Flight() } }
        val owner = lock.withLock {
            if (flight.started) {
                false
            } else {
                flight.started = true
                true
            }
        }
        if (!owner) return awaitFlight(flight)

        return try {
            val value = block()
            flight.result.complete(value)
            value
        } catch (t: Throwable) {
            flight.result.completeExceptionally(t)
            throw t
        } finally {
            lock.withLock { if (flights[key] === flight) flights.remove(key) }
        }
    }

    /**
     * 搭车者等第一个人的结果。第一个失败时给空列表——调用方会回落到 `observe()` 的观察值，
     * 这比把异常冒到设置页更好（一次探测失败不该让整页崩）。
     */
    private suspend fun awaitFlight(flight: Flight): List<RuntimeInstance> = try {
        flight.result.await()
    } catch (_: Throwable) {
        emptyList()
    }
}
