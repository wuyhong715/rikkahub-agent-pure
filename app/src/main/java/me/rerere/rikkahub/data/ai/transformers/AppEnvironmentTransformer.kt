package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.Brand
import me.rerere.rikkahub.data.ai.AppEnvironmentPrompt
import me.rerere.rikkahub.data.ai.tools.ColdMemoryRules
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.vector.EmbeddingModelFiles
import me.rerere.rikkahub.data.vector.EmbeddingReadiness
import me.rerere.rikkahub.data.vector.LibraryIndexCoordinator
import me.rerere.rikkahub.shizuku.PrivilegeLevel
import me.rerere.rikkahub.shizuku.ShizukuManager
import me.rerere.rikkahub.data.runtime.RuntimeInstance
import me.rerere.rikkahub.data.runtime.InstanceRuntimeSnapshot
import me.rerere.rikkahub.data.runtime.InstanceState

/**
 * App-environment system-prompt injection (Moxw).
 *
 * Appends an `<app_environment>` block to the system message of every turn so the model knows
 * the app's on-device layout instead of guessing: where the user-visible shared folders are
 * (deliverables belong there), where the app-private storage is (deliverables do NOT), which
 * workspace directories hold the knowledge base and the file library, whether semantic search
 * has an embedding model behind it, and what privilege the Shizuku shell currently has.
 *
 * The block deliberately carries only ACTION-RELEVANT, STABLE facts (拍板 2026-10-17): no app
 * version, no SDK level, no device model — the model never branches on those, and leaving them
 * out keeps the block byte-identical across releases and devices, which keeps prompt caching
 * intact and the prompt generic.
 *
 * The knowledge-base / file-library lines are emitted under exactly the same gates that register
 * their tools (`ChatService.createColdMemoryToolsIfConfigured` /
 * `createLibraryToolsIfConfigured`), so the prompt never advertises a capability the turn does
 * not actually have.
 *
 * All collection is best-effort and synchronous-cheap: one small directory listing (installed
 * embedding models), one binder call guarded by pingBinder (Shizuku UID), and two DAO reads.
 * A failure in any input degrades that line rather than failing the request.
 */
class AppEnvironmentTransformer(
    private val workspaceRepository: WorkspaceRepository,
    private val libraryIndex: LibraryIndexCoordinator,
) : InputMessageTransformer {

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val prompt = try {
            AppEnvironmentPrompt.render(collect(ctx))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "collecting app environment failed; skipping injection", e)
            return messages
        }
        if (prompt.isBlank()) return messages

        // Append to the first system message, creating one when the turn has none — the same
        // pattern WorkspaceReminderTransformer uses.
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$prompt")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + messages
        }
    }

    private suspend fun collect(ctx: TransformerContext): AppEnvironmentPrompt.Info {
        val context = ctx.context
        val assistant = ctx.assistant
        val settings = ctx.settings

        // Knowledge base — mirrors ChatService.createColdMemoryToolsIfConfigured's gate exactly:
        // enabled AND workspace bound AND directory parses AND the workspace row still exists.
        val coldMemoryLabel = run {
            if (!assistant.coldMemoryEnabled) return@run null
            val workspaceId = assistant.workspaceId?.toString() ?: return@run null
            val dir = ColdMemoryRules.normalizeDir(assistant.coldMemoryDir) ?: return@run null
            if (workspaceRepository.getById(workspaceId) == null) return@run null
            if (dir.isEmpty()) {
                ColdMemoryRules.WORKSPACE_PREFIX
            } else {
                "${ColdMemoryRules.WORKSPACE_PREFIX}/$dir"
            }
        }

        // File library — mirrors ChatService.createLibraryToolsIfConfigured's gate exactly:
        // dirLabelOf resolves enablement + workspace binding + directory, then the row check.
        val libraryLabel = libraryIndex.dirLabelOf(assistant)?.let { label ->
            val workspaceId = assistant.workspaceId?.toString() ?: return@let null
            if (workspaceRepository.getById(workspaceId) == null) null else label
        }

        // Semantic search — the same two inputs EmbeddingReadiness.isReady resolves from, kept
        // separate here so the line can say WHICH backend serves instead of just "ready".
        val installed = runCatching { EmbeddingModelFiles.installedFiles(context) }
            .getOrElse { emptyList() }
        val cloud = EmbeddingReadiness.cloudModel(settings)
        val local = EmbeddingReadiness.localModelFile(settings, installed)
        val (semanticBackend, semanticModelLabel) = when {
            cloud != null -> AppEnvironmentPrompt.SemanticBackend.CLOUD to cloud.displayName
            local != null -> AppEnvironmentPrompt.SemanticBackend.LOCAL to local
            else -> AppEnvironmentPrompt.SemanticBackend.NONE to null
        }

        val shellPrivilege = when (ShizukuManager.privilege()) {
            PrivilegeLevel.ROOT -> AppEnvironmentPrompt.ShellPrivilege.ROOT
            PrivilegeLevel.SHELL -> AppEnvironmentPrompt.ShellPrivilege.SHELL
            PrivilegeLevel.UNKNOWN -> AppEnvironmentPrompt.ShellPrivilege.UNKNOWN
        }

        // 运行后端：直接用注册表发布的快照，不再二次问后端。空快照 = 这一节不输出。
        val instances = InstanceRuntimeSnapshot.current.map { instance ->
            AppEnvironmentPrompt.InstanceLine(
                label = instance.id.serial,
                status = instanceStatusWord(instance.state),
                reason = instanceReason(instance),
            )
        }

        return AppEnvironmentPrompt.Info(
            appName = Brand.NAME,
            privateFilesDir = context.filesDir.absolutePath,
            coldMemoryLabel = coldMemoryLabel,
            libraryLabel = libraryLabel,
            semanticBackend = semanticBackend,
            semanticModelLabel = semanticModelLabel,
            shellPrivilege = shellPrivilege,
            instances = instances,
        )
    }

    /**
     * 状态词只取四态本身，不搬 `why` 的中文句子：这一块是英文提示词，混进整句中文会让模型
     * 在两套语域间跳。具体原因交给工具错误的 `recovery`（P2 已统一），提示里只要"能不能用 + 缺哪一步"。
     */
    private fun instanceStatusWord(state: InstanceState): String = when (state) {
        InstanceState.Ready -> "ready"
        is InstanceState.NeedsSetup -> "needs setup"
        is InstanceState.Busy -> "busy"
        is InstanceState.Unavailable -> "not ready"
    }

    private fun instanceReason(instance: RuntimeInstance): String? = when (val state = instance.state) {
        is InstanceState.NeedsSetup -> state.step.name.lowercase()
        is InstanceState.Unavailable -> instance.lastError?.take(60) ?: "untested"
        else -> null
    }

    private companion object {
        const val TAG = "AppEnvironment"
    }
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
