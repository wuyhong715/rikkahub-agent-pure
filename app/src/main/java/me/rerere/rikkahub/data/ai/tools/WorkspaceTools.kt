package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.BackgroundStatus
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceTreeResult
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
import me.rerere.rikkahub.data.runtime.InstanceEnvelopes
import me.rerere.rikkahub.data.runtime.InstanceId
import me.rerere.rikkahub.data.runtime.InstanceKind

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_create_folder" to false,
    "workspace_read_folder" to false,
    "workspace_shell" to true,
    "workspace_run_background" to true,
    "workspace_background_status" to false,
    "workspace_background_kill" to false,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

/**
 * 工作区工具的目标解析。
 *
 * 「所有工作区」不是"换一个绑定"，而是"同一个工具能指向任意一个"：因此工具多一个可选的
 * `workspace` 参数（名字或 id），省略时落回主工作区 [primaryId]。多工作区时才会出现这个参数，
 * 单工作区助手的 schema 保持原样。
 */
class WorkspaceTargets(
    val primaryId: String,
    val options: List<Option>,
) {
    data class Option(val id: String, val name: String, val root: String)

    /** 只有真正可选时才给模型加参数——单工作区时 schema 与以前逐字节一致。 */
    val multi: Boolean get() = options.size > 1

    fun resolveOrNull(requested: String?): String? {
        // 单工作区助手不该因为模型多写了一个 workspace 参数就失败：直接忽略。
        if (!multi) return primaryId
        if (requested.isNullOrBlank()) return primaryId
        val q = requested.trim()
        return options.firstOrNull { it.id == q || it.name.equals(q, ignoreCase = true) || it.root.equals(q, ignoreCase = true) }?.id
    }

    fun describe(): String = options.joinToString("; ") { "${it.name} (${it.id.take(8)})" }
}

private fun JsonObjectBuilder.putWorkspaceProperty(targets: WorkspaceTargets) {
    if (!targets.multi) return
    // 说明里直接列出可用项：模型不该为了选一个工作区先调一次工具。
    put("workspace", buildJsonObject {
        put("type", "string")
        put(
            "description",
            "Which workspace to act on, by name. Omit to use the assistant's primary workspace. " +
                "Available: ${targets.describe()}",
        )
    })
}

private fun unknownWorkspaceEnvelope(targets: WorkspaceTargets, requested: String?): kotlinx.serialization.json.JsonObject =
    buildJsonObject {
        put("error", "unknown_workspace")
        put("requested", requested.orEmpty())
        put("available", targets.describe())
        put(
            "recovery",
            "用 workspace_list 查看有哪些工作区；或者省略 workspace 参数，用助手的主工作区。",
        )
    }

/**
 * 本轮的工作区工具。三个入口（对话、rerun、旧 factory）都走这里，避免再次出现"三条路径三种行为"。
 *
 * - 未开「所有工作区」：沿用旧的严格判定——主工作区存在且 shell 就绪才给工具。
 * - 开了：至少在有一个就绪工作区时给工具；主工作区没绑（或指向的工作区没就绪）时，
 *   取最近访问的那个当主工作区，其余通过工具参数指定。
 *
 * 冷记忆 / 文件库这类需要"一个确定目录"的功能不受影响：它们仍然只认 [assistant.workspaceId]。
 */
suspend fun createWorkspaceToolsForTurn(
    primaryWorkspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String?,
    allWorkspaces: Boolean,
): List<Tool> {
    if (!allWorkspaces) {
        if (primaryWorkspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(primaryWorkspaceId) ?: return emptyList()
        if (workspace.shellStatus != me.rerere.workspace.WorkspaceShellStatus.READY.name) return emptyList()
        return createWorkspaceTools(primaryWorkspaceId, workspaceRepository, cwd)
    }

    val ready = workspaceRepository.getAll()
        .filter { it.shellStatus == me.rerere.workspace.WorkspaceShellStatus.READY.name }
    if (ready.isEmpty()) return emptyList()
    val primary = ready.firstOrNull { it.id == primaryWorkspaceId }
        ?: ready.maxByOrNull { it.lastAccessAt ?: it.updatedAt }
        ?: return emptyList()
    return createWorkspaceTools(primary.id, workspaceRepository, cwd, allWorkspaces = true)
}

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    allWorkspaces: Boolean = false,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    // 审批覆盖来自主工作区：用户对"工作区 shell 要不要问"的答案是一份，不按工作区分叉。
    val targets = if (allWorkspaces) {
        val ready = workspaceRepository.getAll()
            .filter { it.shellStatus == me.rerere.workspace.WorkspaceShellStatus.READY.name }
            .map { WorkspaceTargets.Option(it.id, it.name, it.root) }
        WorkspaceTargets(
            primaryId = workspaceId,
            options = ready.ifEmpty { listOf(WorkspaceTargets.Option(workspaceId, workspaceId, "")) },
        )
    } else {
        WorkspaceTargets(primaryId = workspaceId, options = emptyList())
    }

    return buildList {
        add(createReadFileTool(targets, ::needsApproval, workspaceRepository))
        add(createWriteFileTool(targets, ::needsApproval, workspaceRepository))
        add(createEditFileTool(targets, ::needsApproval, workspaceRepository))
        add(createCreateFolderTool(targets, ::needsApproval, workspaceRepository))
        add(createReadFolderTool(targets, ::needsApproval, workspaceRepository))
        add(createShellTool(targets, ::needsApproval, workspaceRepository, shellCwd))
        add(createRunBackgroundTool(targets, ::needsApproval, workspaceRepository, shellCwd))
        add(createBackgroundStatusTool(targets, ::needsApproval, workspaceRepository))
        add(createBackgroundKillTool(targets, ::needsApproval, workspaceRepository))
        if (allWorkspaces) add(createListWorkspacesTool(workspaceRepository))
    }
}

/**
 * 只有在"所有工作区"模式下才注册：让模型先看得见有哪些，而不是靠猜名字。
 * 纯读，不需要审批。
 */
private fun createListWorkspacesTool(workspaceRepository: WorkspaceRepository) = Tool(
    name = "workspace_list",
    description = "List every workspace on this device with its id, name, root and shell status. " +
        "Use it to pick the `workspace` argument for the other workspace_* tools.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {})
    },
    needsApproval = { false },
    execute = {
        val workspaces = workspaceRepository.getAll()
        listOf(
            UIMessagePart.Text(
                buildJsonArray {
                    workspaces.forEach { workspace ->
                        add(
                            buildJsonObject {
                                put("id", workspace.id)
                                put("name", workspace.name)
                                put("root", workspace.root)
                                put("shell_status", workspace.shellStatus)
                            }
                        )
                    }
                }.toString()
            )
        )
    },
)

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
private fun createReadFileTool(
    targets: WorkspaceTargets,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
            putWorkspaceProperty(targets)
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val targetId = targets.resolveOrNull(it.jsonObject.string("workspace"))
        if (targetId == null) {
            return@Tool listOf(
                UIMessagePart.Text(unknownWorkspaceEnvelope(targets, it.jsonObject.string("workspace")).toString())
            )
        }
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(targetId, path)
        } else {
            val text = workspaceRepository.readTextInRootfs(targetId, path)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    targets: WorkspaceTargets,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
            putWorkspaceProperty(targets)
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val targetId = targets.resolveOrNull(it.jsonObject.string("workspace"))
        if (targetId == null) {
            return@Tool listOf(
                UIMessagePart.Text(unknownWorkspaceEnvelope(targets, it.jsonObject.string("workspace")).toString())
            )
        }
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        // 批 H — 覆盖写留 diff（新建文件没有旧版本，diff 为 null）。读旧内容失败不阻断写入：
        // 一个读不出来的旧文件不该让「保存」这件事失败。
        val previous = if (overwrite) {
            runCatching { workspaceRepository.readTextInRootfs(targetId, path) }.getOrNull()
        } else {
            null
        }
        val entry = workspaceRepository.writeTextInRootfs(targetId, path, text, overwrite)
        val diff = previous?.let { old ->
            runCatching { generateUnifiedDiff(old, text, entry.path) }.getOrNull()
        }
        listOf(
            UIMessagePart.Text(
                text = entry.toJson().toString(),
                metadata = diff?.let { DiffMetadata(diff = it).toMetadata() },
            )
        )
    },
)

private fun createEditFileTool(
    targets: WorkspaceTargets,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
            putWorkspaceProperty(targets)
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val targetId = targets.resolveOrNull(it.jsonObject.string("workspace"))
        if (targetId == null) {
            return@Tool listOf(
                UIMessagePart.Text(unknownWorkspaceEnvelope(targets, it.jsonObject.string("workspace")).toString())
            )
        }
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(targetId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(targetId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createCreateFolderTool(
    targets: WorkspaceTargets,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_create_folder",
    description = """
        Create a directory (and any missing parent directories) using the assistant's bound workspace Rootfs.
        Paths must be absolute inside Rootfs. Use /workspace for the workspace files area.
        Does nothing (succeeds) if the directory already exists.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
            putWorkspaceProperty(targets)
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_create_folder") || it.pathOutsideWritableRoots("path") },
    execute = {
        val targetId = targets.resolveOrNull(it.jsonObject.string("workspace"))
        if (targetId == null) {
            return@Tool listOf(
                UIMessagePart.Text(unknownWorkspaceEnvelope(targets, it.jsonObject.string("workspace")).toString())
            )
        }
        val path = it.jsonObject.absolutePath("path")
        val entry = workspaceRepository.createFolderInRootfs(targetId, path)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createReadFolderTool(
    targets: WorkspaceTargets,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_folder",
    description = """
        Recursively list a directory using the assistant's bound workspace Rootfs, as an indented tree.
        Paths must be absolute inside Rootfs. Use /workspace for the workspace files area.
        The listing is capped in entry count and depth; a truncation notice is included when a cap is hit.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
            putWorkspaceProperty(targets)
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_folder") },
    execute = {
        val targetId = targets.resolveOrNull(it.jsonObject.string("workspace"))
        if (targetId == null) {
            return@Tool listOf(
                UIMessagePart.Text(unknownWorkspaceEnvelope(targets, it.jsonObject.string("workspace")).toString())
            )
        }
        val path = it.jsonObject.absolutePath("path")
        val result = workspaceRepository.readFolderTree(targetId, path)
        listOf(UIMessagePart.Text(formatWorkspaceTree(path, result)))
    },
)

private fun createShellTool(
    targets: WorkspaceTargets,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
            putWorkspaceProperty(targets)
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val targetId = targets.resolveOrNull(it.jsonObject.string("workspace"))
        if (targetId == null) {
            return@Tool listOf(
                UIMessagePart.Text(unknownWorkspaceEnvelope(targets, it.jsonObject.string("workspace")).toString())
            )
        }
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        // 工作区以前把异常直接抛给工具层，模型看到的是没有上下文的报错文本。失败与成功
        // 现在都在同一处成形：失败走统一包络（带 instance 与可执行的 recovery），成功保持
        // 原来的字段名不变。
        val payload = runCatching {
            workspaceRepository.executeCommand(targetId, command, cwd, timeoutMillis)
        }.fold(
            onSuccess = { result ->
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }
            },
            onFailure = { t ->
                InstanceEnvelopes.failure(
                    id = InstanceId(InstanceKind.WORKSPACE, targetId),
                    error = "workspace_shell_failed",
                    recovery = t.message
                        ?: "在工作区详情里点重新检测；仍失败就重装 rootfs（文件区不受影响）。",
                )
            },
        )
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private fun createRunBackgroundTool(
    targets: WorkspaceTargets,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_run_background",
    description = buildString {
        append("Run a shell command persistently in the background in the assistant's bound workspace Rootfs. ")
        append("The process survives across tool calls, so use it for dev servers, long-running installs, or watchers ")
        append("(e.g. 'python -m http.server 8000'). The command runs in the foreground of its own process, so do NOT append '&'. ")
        append("Returns a task id: poll it with workspace_background_status and stop it with workspace_background_kill. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
            putWorkspaceProperty(targets)
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run persistently in the background")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_run_background") },
    execute = {
        val targetId = targets.resolveOrNull(it.jsonObject.string("workspace"))
        if (targetId == null) {
            return@Tool listOf(
                UIMessagePart.Text(unknownWorkspaceEnvelope(targets, it.jsonObject.string("workspace")).toString())
            )
        }
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val status = workspaceRepository.startBackground(targetId, command, cwd)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("id", status.id)
                    put("status", "running")
                    put("command", status.command)
                }.toString()
            )
        )
    },
)

private fun createBackgroundStatusTool(
    targets: WorkspaceTargets,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_background_status",
    description = """
        Check the status and captured output of background processes started with workspace_run_background.
        Omit id to list every background process for this workspace.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
            putWorkspaceProperty(targets)
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by workspace_run_background. Omit to list all.")
                })
            },
            required = emptyList(),
        )
    },
    needsApproval = { needsApproval("workspace_background_status") },
    execute = {
        val targetId = targets.resolveOrNull(it.jsonObject.string("workspace"))
        if (targetId == null) {
            return@Tool listOf(
                UIMessagePart.Text(unknownWorkspaceEnvelope(targets, it.jsonObject.string("workspace")).toString())
            )
        }
        val taskId = it.jsonObject.string("id")
        val statuses = if (taskId != null) {
            listOfNotNull(workspaceRepository.backgroundStatus(targetId, taskId))
        } else {
            workspaceRepository.listBackground(targetId)
        }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("processes", buildJsonArray {
                        statuses.forEach { status -> add(status.toJson()) }
                    })
                }.toString()
            )
        )
    },
)

private fun createBackgroundKillTool(
    targets: WorkspaceTargets,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_background_kill",
    description = "Stop a background process started with workspace_run_background.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
            putWorkspaceProperty(targets)
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by workspace_run_background")
                })
            },
            required = listOf("id"),
        )
    },
    needsApproval = { needsApproval("workspace_background_kill") },
    execute = {
        val targetId = targets.resolveOrNull(it.jsonObject.string("workspace"))
        if (targetId == null) {
            return@Tool listOf(
                UIMessagePart.Text(unknownWorkspaceEnvelope(targets, it.jsonObject.string("workspace")).toString())
            )
        }
        val taskId = it.jsonObject.string("id") ?: error("id is required")
        val killed = workspaceRepository.killBackground(targetId, taskId)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("id", taskId)
                    put("killed", killed)
                }.toString()
            )
        )
    },
)

private fun BackgroundStatus.toJson() = buildJsonObject {
    put("id", id)
    put("command", command)
    put("status", if (running) "running" else "exited")
    if (!running) put("exitCode", exitCode)
    put("startedAt", startedAtMillis)
    put("stdout", stdout)
    put("stderr", stderr)
    if (droppedStdout > 0) put("droppedStdout", droppedStdout)
    if (droppedStderr > 0) put("droppedStderr", droppedStderr)
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.createFolderInRootfs(
    workspaceId: String,
    path: String,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Create folder",
        command = """
            if [ -e $pathArg ] && [ ! -d $pathArg ]; then
              printf '%s\n' ${"Path already exists and is not a directory: $path".shellQuote()} >&2
              exit 1
            fi
            mkdir -p -- $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

// 免强制审批的可写安全区: 工作区文件目录、临时目录和技能目录
private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp", "/skills")

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(name: String): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isOutsideWritableRoots()
    }.getOrDefault(true)

internal fun String.isOutsideWritableRoots(): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    // 拒绝任何包含 ".." 段的路径, 防止 /workspace/../etc/x 这类字面前缀匹配绕过审批
    if (normalized.split('/').any { it == ".." }) return true
    return WRITABLE_ROOT_PREFIXES.none { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

/**
 * 纯函数: 把递归目录树格式化为紧凑的缩进文本 (面向模型), 命中截断上限时显式提示。
 */
internal fun formatWorkspaceTree(rootPath: String, result: WorkspaceTreeResult): String = buildString {
    append(rootPath.trimEnd('/').ifEmpty { "/" }).append('/')
    if (result.entries.isEmpty()) {
        append(if (result.truncated) " (empty, truncated)" else " (empty)")
        return@buildString
    }
    result.entries.forEach { entry ->
        append('\n')
        append("  ".repeat(entry.depth))
        append(entry.name)
        if (entry.isDirectory) {
            append('/')
        } else {
            append(" (").append(entry.sizeBytes).append(" bytes)")
        }
    }
    if (result.truncated) {
        append('\n')
        append("... (truncated: showing ${result.entries.size} entries; narrow the path for a complete listing)")
    }
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
