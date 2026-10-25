package me.rerere.rikkahub.data.runtime

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.local.SshAuth
import me.rerere.rikkahub.data.ai.tools.local.execOneShot
import me.rerere.rikkahub.data.ai.tools.local.isUsable
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import java.util.concurrent.atomic.AtomicReference

/**
 * SSH 主机：每行一个实例，key 是主机名（`ssh_hosts` 的主键）。
 *
 * 以前它**没有状态**——设置页看起来永远正常，只有调用时才失败。这里的价值就是把"没试过"
 * 和"连不上"分开：[InstanceState.Unavailable] 带 recovery 的那条只说"尚未验证"，
 * 真正的失败带具体原因。
 *
 * 探测是唯一会联网的地方（`probe`），由 [InstanceRegistry] 的 TTL 与单飞保护，
 * 列表滚动永远不会触发它。
 */
class SshInstanceAdapter(
    private val context: Context,
    private val repository: SshHostRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : InstanceAdapter {

    override val kind: InstanceKind = InstanceKind.SSH

    private val verifiedAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val lastErrors = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun observe(): Flow<List<RuntimeInstance>> =
        combine(repository.listFlow(), verifiedAt, lastErrors) { hosts, verified, errors ->
            hosts.map { host ->
                toInstance(
                    host = host,
                    verifiedAtMs = verified[host.name],
                    lastError = errors[host.name],
                    lastProbeAt = null,
                )
            }
        }

    override suspend fun probe(id: InstanceId?): List<RuntimeInstance> {
        val hosts = repository.listFlow().first().filter { id == null || it.name == id.key }
        val now = clock()
        return hosts.map { host -> probeHost(host, now) }
    }

    override fun backedToolNames(): Set<String> = setOf(
        "ssh_exec",
        "ssh_exec_saved",
        "ssh_upload",
        "ssh_download",
        "save_ssh_host",
        "list_ssh_hosts",
        "delete_ssh_host",
        "forget_ssh_host_key",
    )

    private suspend fun probeHost(host: SshHostEntity, now: Long): RuntimeInstance {
        val auth = SshAuth(
            password = host.password,
            privateKey = host.privateKey,
            passphrase = host.passphrase,
        )
        if (!auth.isUsable()) {
            return toInstance(host, verifiedAtMs = null, lastError = null, lastProbeAt = now)
        }

        val result = runCatching {
            execOneShot(
                context = context,
                host = host.host,
                port = host.port,
                user = host.user,
                auth = auth,
                command = "true",
                timeoutMs = PROBE_TIMEOUT_MS,
                sessionRef = AtomicReference(),
            )
        }.getOrElse { error -> return failed(host, describe(error.message.orEmpty()), now) }

        val error = result["error"]?.jsonPrimitive?.contentOrNull
        if (error != null) {
            return failed(host, describe(error), now)
        }

        verifiedAt.update { it + (host.name to now) }
        lastErrors.update { it - host.name }
        return toInstance(host, verifiedAtMs = now, lastError = null, lastProbeAt = now)
    }

    private fun failed(host: SshHostEntity, message: String, now: Long): RuntimeInstance {
        lastErrors.update { it + (host.name to message) }
        return toInstance(host, verifiedAtMs = null, lastError = message, lastProbeAt = now)
    }

    private fun toInstance(
        host: SshHostEntity,
        verifiedAtMs: Long?,
        lastError: String?,
        lastProbeAt: Long?,
    ): RuntimeInstance {
        val hasCredential = !host.password.isNullOrBlank() || !host.privateKey.isNullOrBlank()
        val state = InstanceStateMappers.ssh(hasCredential, verifiedAtMs, lastError)
        return RuntimeInstance(
            id = InstanceId(InstanceKind.SSH, host.name),
            displayName = host.name,
            subtitle = "${host.user}@${host.host}:${host.port}",
            state = state,
            capabilities = if (state is InstanceState.Ready) {
                setOf(Capability.SHELL_EXEC, Capability.REMOTE_NET)
            } else {
                emptySet()
            },
            lastProbeAt = lastProbeAt,
            lastError = lastError,
        )
    }

    /** 把工具层的结构化错误翻成一句话；真正的 recovery 文案在 [InstanceStateMappers.ssh] 里。 */
    private fun describe(error: String): String = when {
        error == "timeout" -> "连接超时"
        error.contains("auth", ignoreCase = true) -> "认证失败：密码或私钥被拒绝"
        error.contains("host_key", ignoreCase = true) -> "主机指纹变化，需要先确认这是不是同一台机器"
        error.contains("unreachable", ignoreCase = true) -> "网络不可达"
        error.isBlank() -> "连接失败"
        else -> "连接失败：$error"
    }

    private companion object {
        /** 探测只要握手 + `true`，超过 6 s 基本是网络问题，没必要让用户等 30 s。 */
        const val PROBE_TIMEOUT_MS = 6_000
    }
}
