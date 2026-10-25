package me.rerere.rikkahub.data.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 五个后端的统一失败包络。
 *
 * 在此之前只有 Shizuku 做对了形状（`{error, recovery}`），另外四个各写各的：SSH 有
 * `tcp_unreachable` / `auth_failed` / `host_key_changed` 三种，Termux 是 `termux_not_installed`
 * 之类，工作区直接抛异常。模型每接一个新后端就要学一套。
 *
 * 这里定下的契约只有三条：
 * 1. 失败一定带 `error`，且一定带 `recovery`（一句可执行的下一步）。缺了 recovery 的错误
 *    对模型等于没有信息——它只能靠猜。
 * 2. **发生失败的实例**写在 `instance`（`kind:key` 形式），加 `state` 说明四态里的哪一种。
 * 3. 既有键名一律不改。`decorate` 只追加，所以依赖 `error` / `recovery` 的老提示词、
 *    老测试、`ToolExecutionRetryPolicy` 都不受影响。
 *
 * 纯 Kotlin（只依赖 kotlinx.serialization），可在裸 JVM 上测。
 */
object InstanceEnvelopes {

    /** 实例没准备好时的错误码。区别于后端自己的具体错误码（`auth_failed` 等）。 */
    const val ERROR_NOT_READY = "instance_not_ready"

    /** 后端失败但工具层没给出更具体错误码时的兜底。 */
    const val ERROR_GENERIC = "instance_error"

    /**
     * 缺 recovery 时补的兜底句。它必须指向用户真正能操作的界面，否则等于让模型自言自语。
     */
    const val GENERIC_RECOVERY =
        "打开 设置 → 运行实例，检查这个实例的状态；修好后重试。"

    fun stateName(state: InstanceState): String = when (state) {
        InstanceState.Ready -> "ready"
        is InstanceState.NeedsSetup -> "needs_setup"
        is InstanceState.Busy -> "busy"
        is InstanceState.Unavailable -> "unavailable"
    }

    /** 实例此刻不可用：工具还没执行就失败，原因是状态而不是命令。 */
    fun notReady(instance: RuntimeInstance): JsonObject = notReady(instance.id, instance.state)

    fun notReady(id: InstanceId, state: InstanceState): JsonObject = buildJsonObject {
        put("error", ERROR_NOT_READY)
        put("instance", id.serial)
        put("state", stateName(state))
        when (state) {
            is InstanceState.NeedsSetup -> {
                put("step", state.step.name.lowercase())
                put("why", state.why)
                put("recovery", recoveryFor(id.kind, state.step))
            }

            is InstanceState.Unavailable -> {
                put("why", state.why)
                put("recovery", state.recovery ?: GENERIC_RECOVERY)
            }

            is InstanceState.Busy -> {
                put("why", state.label)
                put("recovery", "等它完成后重试；安装进度在 设置 → 运行实例 的这个实例详情里。")
            }

            InstanceState.Ready -> {
                put("why", "实例报告就绪，但这次调用仍然失败。")
                put("recovery", GENERIC_RECOVERY)
            }
        }
    }

    /** 后端自己失败的统一写法（没有状态可报，只有原因）。 */
    fun failure(
        id: InstanceId,
        error: String,
        recovery: String? = null,
        extras: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("error", error)
        put("instance", id.serial)
        put("recovery", recovery ?: GENERIC_RECOVERY)
        extras?.forEach { (key, value) -> if (key !in RESERVED) put(key, value) }
    }

    /**
     * 给一个**现有的**错误信封追加 `instance` / `state`（缺 recovery 时补上），不改任何既有键。
     *
     * 非失败信封（没有 `error`）原样返回：成功结果的结构由各工具自己决定，这里不碰。
     * 已经有 `instance` 的也原样返回，避免同一层被包两次。
     */
    fun decorate(
        json: JsonObject,
        id: InstanceId,
        state: InstanceState? = null,
    ): JsonObject {
        if (json[ERROR_KEY] == null) return json
        if (json[INSTANCE_KEY] != null) return json

        return buildJsonObject {
            put(INSTANCE_KEY, id.serial)
            if (state != null) put(STATE_KEY, stateName(state))
            json.forEach { (key, value) ->
                if (key != INSTANCE_KEY && key != STATE_KEY) put(key, value)
            }
            if (json[RECOVERY_KEY] == null) put(RECOVERY_KEY, GENERIC_RECOVERY)
        }
    }

    /** 只判断一个信封是不是失败信封——测试与门控都用它，避免各自写 `containsKey`。 */
    fun isFailure(json: JsonObject): Boolean = json[ERROR_KEY]?.let {
        runCatching { it.jsonPrimitive.content }.getOrNull()?.isNotBlank() == true
    } ?: false

    private fun recoveryFor(kind: InstanceKind, step: SetupStep): String = when (step) {
        SetupStep.INSTALL_APP -> when (kind) {
            InstanceKind.TERMUX -> "安装 Termux（官方 GitHub releases，不要用 Play 商店版本），然后重试。"
            InstanceKind.SHIZUKU -> "安装 Shizuku 或 Sui，启动它的服务后重试。"
            else -> "先安装这个实例依赖的应用，然后重试。"
        }

        SetupStep.START_SERVICE -> "启动对应服务后重试；必要时把那个应用切到前台。"
        SetupStep.GRANT_PERMISSION -> "在 设置 → 运行实例 的这个实例里完成授权，然后重试。"
        SetupStep.INSTALL_ROOTFS -> "在工作区详情里安装 Linux 环境（文件区不受影响），完成后重试。"
        SetupStep.ADD_CREDENTIAL -> "补一组密码或私钥后重试；也可以让用户先说好凭据。"
        SetupStep.ENABLE_FEATURE -> "按提示开启对应开关（例如 Termux 的 allow-external-apps）后重试。"
    }

    private const val ERROR_KEY = "error"
    private const val RECOVERY_KEY = "recovery"
    private const val INSTANCE_KEY = "instance"
    private const val STATE_KEY = "state"

    private val RESERVED = setOf(ERROR_KEY, RECOVERY_KEY, INSTANCE_KEY, STATE_KEY)
}
