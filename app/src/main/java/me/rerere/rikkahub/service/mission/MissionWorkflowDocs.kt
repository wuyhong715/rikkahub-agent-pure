package me.rerere.rikkahub.service.mission

/**
 * 批 C — workflow documents (「任务的工作流程」).
 *
 * A mission may follow a Markdown playbook that lives **inside its workspace**, under
 * `workflows/`. The mission row stores only the relative path (`missions.workflow_path`),
 * never the content: editing the .md changes the next round without touching the mission,
 * and a recurring mission picks up the edited version on its next fire.
 *
 * Two modes, both handled here:
 * - **pinned** — [MissionEntity.workflowPath] is set. The launcher reads the file and
 *   injects its body into the round's prompt (truncated to [WORKFLOW_MAX_CHARS]).
 * - **chooser** — no path. The launcher lists `workflows/ 下的 .md` and tells the model to read
 *   the most suitable one with its workspace file tools (or work without one).
 *
 * Everything here is a pure function so the wording, the filtering rules and the
 * truncation are JVM-testable; [MissionLauncher] does the IO through a
 * [MissionWorkflowSource].
 */

/** Directory (relative to the workspace FILES root) that holds workflow documents. */
const val WORKFLOW_DIR: String = "workflows"

/**
 * How many characters of a pinned document are injected into the prompt. Long enough for a
 * detailed playbook, short enough that a recurring mission does not pay for it twice a day.
 */
const val WORKFLOW_MAX_CHARS: Int = 6_000

/** Upper bound on how many documents the chooser block lists. */
const val WORKFLOW_MAX_LISTED: Int = 20

/** Upper bound on the file-name stem produced by [MissionWorkflowDocs.sanitizeDocName]. */
const val WORKFLOW_NAME_MAX_CHARS: Int = 60

/**
 * Where the launcher gets workflow documents from. The DI wires this to
 * [me.rerere.rikkahub.data.repository.WorkspaceRepository] (FILES area); tests use a fake.
 *
 * Implementations must never throw for a missing workspace/file — the launcher treats a
 * failure as "no document", because a broken playbook must not stop a mission from running.
 */
interface MissionWorkflowSource {

    /** File names (no directory) of the workspace's `workflows/ 下的 .md`, sorted; empty on error. */
    suspend fun list(workspaceId: String): List<String>

    /** Reads a workspace-relative path; null when missing / unreadable. */
    suspend fun read(workspaceId: String, path: String): String?
}

object MissionWorkflowDocs {

    /** True for `workflows/<name>.md` — the only shapes the picker and the chooser accept. */
    fun isWorkflowDoc(path: String): Boolean {
        val normalized = path.trim().replace('\\', '/').removePrefix("./")
        if (!normalized.startsWith("$WORKFLOW_DIR/")) return false
        val name = normalized.removePrefix("$WORKFLOW_DIR/")
        return name.isNotEmpty() && !name.contains('/') && name.endsWith(".md", ignoreCase = true)
    }

    /** Full relative path for a bare file name in the workflow directory. */
    fun pathOf(name: String): String = "$WORKFLOW_DIR/${name.trim()}"

    /**
     * 批 D — turn free-form user input into a safe workflow document path.
     *
     * Accepts "周报", "周报.md", " work/week report.md" … and returns `workflows/<name>.md`.
     * Rules: strip a trailing `.md` (any case), drop path separators and characters that
     * are illegal in workspace paths / awkward in prompts, collapse whitespace to `-`,
     * cap the stem at [WORKFLOW_NAME_MAX_CHARS]. Returns null when nothing usable remains
     * (`""`, `"."`, `".."`), so callers can keep the dialog open instead of writing a
     * garbage file.
     */
    fun sanitizeDocName(raw: String): String? {
        var name = raw.trim().trim('"', '\'', '\u201c', '\u201d')
        if (name.endsWith(".md", ignoreCase = true)) name = name.dropLast(3)
        // Path separators and the Windows-reserved set; also strip control chars.
        name = name
            .replace('\\', ' ')
            .replace('/', ' ')
            .replace(Regex("""[:*?"<>|]"""), " ")
            .replace(Regex("""\s+"""), "-")
            .trim('-', '.')
            .take(WORKFLOW_NAME_MAX_CHARS)
        if (name.isBlank() || name == "." || name == "..") return null
        return pathOf("$name.md")
    }

    /**
     * 批 D — starter document written by the detail page's "new workflow" action.
     *
     * Deliberately short and structural: headings the model can follow, no invented domain
     * content. The user (or the mission itself) fills the blanks; keeping the skeleton tiny
     * means the first round does not spend 6k characters of prompt on boilerplate.
     */
    fun template(title: String, goal: String = ""): String = buildString {
        appendLine("# ${title.trim().ifBlank { "工作流程" }}")
        appendLine()
        appendLine("> 本文件是任务的工作流程（playbook）。每轮启动时会被注入提示词；直接编辑本文件即可改变下一轮的执行方式。")
        appendLine()
        appendLine("## 目标")
        appendLine(goal.trim().ifBlank { "- （写清这一流程要产出什么）" })
        appendLine()
        appendLine("## 输入")
        appendLine("- 需要读取的文件 / 数据：")
        appendLine("- 前置条件：")
        appendLine()
        appendLine("## 步骤")
        appendLine("1. ")
        appendLine("2. ")
        appendLine("3. ")
        appendLine()
        appendLine("## 产出")
        appendLine("- 写到工作区的路径：")
        appendLine("- 交付要求（格式 / 篇幅）：")
        appendLine()
        appendLine("## 验收")
        appendLine("- [ ] ")
        appendLine()
        appendLine("## 注意事项")
        appendLine("- 流程与任务目标冲突时，以任务目标为准，并在时间线里说明。")
    }

    /** Truncates a document body to [WORKFLOW_MAX_CHARS], marking the cut. */
    fun trimContent(content: String): String =
        if (content.length <= WORKFLOW_MAX_CHARS) {
            content.trim()
        } else {
            content.take(WORKFLOW_MAX_CHARS).trimEnd() + "\n\n…（流程文档过长，已截断）"
        }

    /**
     * Prompt block for a pinned document. The closing note matters: a playbook that
     * conflicts with the mission goal could otherwise make the model argue with itself.
     */
    fun composePinned(path: String, content: String): String = buildString {
        appendLine("[工作流程] 本任务按工作区里的流程文档执行：$path")
        appendLine()
        appendLine("--- 流程开始 ---")
        appendLine(trimContent(content))
        appendLine("--- 流程结束 ---")
        appendLine()
        append("按上面的流程推进；流程与任务目标冲突时，以任务目标为准，并在时间线里说明。")
    }

    /**
     * Prompt block for the chooser mode. Empty when there is nothing to choose from — the
     * launcher then injects nothing at all rather than a "no workflows" apology.
     */
    fun composeChooser(names: List<String>): String {
        val listed = names
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .take(WORKFLOW_MAX_LISTED)
        if (listed.isEmpty()) return ""
        return buildString {
            appendLine("[工作流程] 本任务未指定流程。工作区 `$WORKFLOW_DIR/` 下有下列流程文档：")
            listed.forEach { appendLine("- $it") }
            appendLine()
            append(
                "请先用工作区文件工具读取其中最适合本任务的一份，并遵照执行；" +
                    "没有合适的就不用流程，直接按目标推进。",
            )
        }
    }

    /**
     * One-line reminder injected into continuation rounds. The full document was already
     * injected on the first/trigger round and its text is in the transcript, so repeating
     * 6k characters every round would only burn tokens.
     */
    fun composeContinueHint(path: String): String =
        "[工作流程] 继续沿用本任务的工作流程：$path（需要细节时读取该文件）。"
}