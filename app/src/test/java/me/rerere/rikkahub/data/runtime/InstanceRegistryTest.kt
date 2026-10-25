package me.rerere.rikkahub.data.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 注册表是整个抽象的汇合点：设置页、体检、门控都读它。这里钉住三条容易写错的语义——
 * 观察值是权威、探测只在 TTL 外跑、并发刷新只真正探一次。
 */
class InstanceRegistryTest {

    private class FakeAdapter(
        override val kind: InstanceKind,
        private val state: InstanceState = InstanceState.Ready,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : InstanceAdapter {

        val observed = MutableStateFlow(listOf(build(state)))

        val probeCount = AtomicInteger()

        var invalidated = 0
            private set

        override fun observe(): Flow<List<RuntimeInstance>> = observed

        override suspend fun probe(id: InstanceId?): List<RuntimeInstance> {
            probeCount.incrementAndGet()
            gate?.await()
            return listOf(build(InstanceState.Ready, lastProbeAt = 42_000L))
        }

        override fun invalidate() {
            invalidated += 1
        }

        fun build(state: InstanceState, lastProbeAt: Long? = null) = RuntimeInstance(
            id = InstanceId.local(kind),
            displayName = kind.label,
            subtitle = "",
            state = state,
            capabilities = setOf(Capability.SHELL_EXEC),
            lastProbeAt = lastProbeAt,
        )
    }

    private fun newScope() = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @Test
    fun `instances expose what the adapters observe`() = runBlocking {
        val adapter = FakeAdapter(InstanceKind.SHIZUKU)
        val registry = InstanceRegistry(listOf(adapter), newScope())

        val loaded = withTimeout(2_000) { registry.instances.first { it.isNotEmpty() } }
        assertEquals(1, loaded.size)
        assertEquals(InstanceKind.SHIZUKU, loaded.first().id.kind)
        assertNull(loaded.first().lastProbeAt)
    }

    @Test
    fun `refresh stamps lastProbeAt onto the merged view`() = runBlocking {
        val adapter = FakeAdapter(InstanceKind.TERMUX)
        val registry = InstanceRegistry(listOf(adapter), newScope())
        withTimeout(2_000) { registry.instances.first { it.isNotEmpty() } }

        val id = InstanceId.local(InstanceKind.TERMUX)
        val refreshed = registry.refresh(id, force = true)

        assertNotNull(refreshed)
        assertEquals(42_000L, registry.find(id)?.lastProbeAt ?: -1L)
    }

    @Test
    fun `a fresh probe is reused until its ttl expires`() = runBlocking {
        var now = 1_000L
        val adapter = FakeAdapter(InstanceKind.SHIZUKU)
        val registry = InstanceRegistry(listOf(adapter), newScope(), clock = { now })
        withTimeout(2_000) { registry.instances.first { it.isNotEmpty() } }
        val id = InstanceId.local(InstanceKind.SHIZUKU)

        registry.refresh(id, force = true)
        registry.refresh(id)
        assertEquals("TTL 内不该再打扰后端", 1, adapter.probeCount.get())

        now += ProbeTtl.forKind(InstanceKind.SHIZUKU) + 1
        registry.refresh(id)
        assertEquals(2, adapter.probeCount.get())
    }

    @Test
    fun `concurrent refreshes coalesce into a single probe`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val adapter = FakeAdapter(InstanceKind.SSH, gate = gate)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val registry = InstanceRegistry(listOf(adapter), scope)
        val id = InstanceId.local(InstanceKind.SSH)

        val first = scope.async { registry.refresh(id, force = true) }
        delay(100)
        val second = scope.async { registry.refresh(id, force = true) }
        delay(100)
        gate.complete(Unit)

        first.await()
        second.await()
        assertEquals("同一实例的并发刷新应单飞", 1, adapter.probeCount.get())
    }

    @Test
    fun `invalidate drops the cached probe and notifies the adapter`() = runBlocking {
        val adapter = FakeAdapter(InstanceKind.WORKSPACE)
        val registry = InstanceRegistry(listOf(adapter), newScope())
        withTimeout(2_000) { registry.instances.first { it.isNotEmpty() } }
        val id = InstanceId.local(InstanceKind.WORKSPACE)

        registry.refresh(id, force = true)
        registry.invalidate(id)
        registry.refresh(id, force = true)

        assertEquals(2, adapter.probeCount.get())
        assertEquals(1, adapter.invalidated)
    }

    @Test
    fun `byCapability only counts instances that are ready`() = runBlocking {
        val ready = FakeAdapter(InstanceKind.TERMUX)
        val notReady = FakeAdapter(
            InstanceKind.SHIZUKU,
            state = InstanceState.NeedsSetup(SetupStep.GRANT_PERMISSION, "未授权"),
        )
        val registry = InstanceRegistry(listOf(ready, notReady), newScope())
        withTimeout(2_000) { registry.instances.first { it.size == 2 } }

        val usable = registry.byCapability(Capability.SHELL_EXEC)
        assertEquals(1, usable.size)
        assertEquals(InstanceKind.TERMUX, usable.first().id.kind)
        assertNull(registry.refresh(InstanceId(InstanceKind.APP_HOST, "missing")))
    }

    @Test
    fun `instances are ordered by kind so the settings list is stable`() = runBlocking {
        val ssh = FakeAdapter(InstanceKind.SSH)
        val host = FakeAdapter(InstanceKind.APP_HOST)
        val registry = InstanceRegistry(listOf(ssh, host), newScope())

        val loaded = withTimeout(2_000) { registry.instances.first { it.size == 2 } }
        assertTrue(loaded.first().id.kind.ordinal < loaded.last().id.kind.ordinal)
    }
}
