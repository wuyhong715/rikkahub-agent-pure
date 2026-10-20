package me.rerere.rikkahub.ui.pages.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.service.mission.MissionWorkflowDocs
import me.rerere.rikkahub.ui.components.ai.WorkspaceSelectSheet
import me.rerere.rikkahub.ui.components.ui.AssistantAvatar
import me.rerere.rikkahub.workflow.model.TriggerSpec

/** 批 B — how a mission starts: immediately on create, on a daily schedule, or on an event trigger. */
enum class MissionStartMode { IMMEDIATE, SCHEDULED, TRIGGER }

/** Event-trigger options offered by the create sheet (label resolved via stringResource). */
private val triggerOptions = listOf(
    "wifi_connected" to R.string.mission_trigger_wifi_connected,
    "wifi_disconnected" to R.string.mission_trigger_wifi_disconnected,
    "power_connected" to R.string.mission_trigger_power_connected,
    "power_disconnected" to R.string.mission_trigger_power_disconnected,
    "boot_completed" to R.string.mission_trigger_boot_completed,
    "screen_on" to R.string.mission_trigger_screen_on,
    "screen_off" to R.string.mission_trigger_screen_off,
)

/** Map a create-sheet trigger option key to its [TriggerSpec]; null for unknown keys. */
private fun buildTriggerSpec(kind: String?): TriggerSpec? = when (kind) {
    "wifi_connected" -> TriggerSpec.WifiConnected()
    "wifi_disconnected" -> TriggerSpec.WifiDisconnected()
    "power_connected" -> TriggerSpec.PowerConnected
    "power_disconnected" -> TriggerSpec.PowerDisconnected
    "boot_completed" -> TriggerSpec.BootCompleted
    "screen_on" -> TriggerSpec.ScreenOn
    "screen_off" -> TriggerSpec.ScreenOff
    else -> null
}

/** Day-of-week chip label (ISO 1..7, 1 = Monday). */
private fun dayLabelRes(day: Int): Int = when (day) {
    1 -> R.string.mission_start_day_mon
    2 -> R.string.mission_start_day_tue
    3 -> R.string.mission_start_day_wed
    4 -> R.string.mission_start_day_thu
    5 -> R.string.mission_start_day_fri
    6 -> R.string.mission_start_day_sat
    else -> R.string.mission_start_day_sun
}

/** Everything the create sheet collects; nulls mean "fall back to defaults / not bound". */
data class MissionDraft(
    val title: String,
    val description: String,
    val goals: List<String>,
    val workspaceId: String?,      // null = 不绑定
    val assistantId: String?,      // null = 默认助手
    val approvalMode: String?,     // null = 默认（继承全局）；或 "AUTO"/"SEMI"/"MANUAL"
    val maxResumeRounds: Int?,     // 高级；null = 均衡默认
    val stallRounds: Int?,
    val tokenBudget: Long?,
    // 批 D — 完成后需用户确认（done → REVIEW 而非直接 DONE）
    val requireConfirmation: Boolean = false,
    // 批 B — 开始方式；trigger == null = 立即开始
    val startMode: MissionStartMode = MissionStartMode.IMMEDIATE,
    val trigger: TriggerSpec? = null,
    val cooldownSeconds: Int = 60,
    val maxRunsPerDay: Int? = 24,
    /** 批 C — workspace-relative .md path (`workflows/x.md`); null = let the model choose. */
    val workflowPath: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateMissionSheet(
    settings: Settings,
    workspaces: List<WorkspaceEntity>,
    onDismiss: () -> Unit,
    defaultConversationId: String? = null,
    onCreate: (MissionDraft) -> Unit,
    /** 批 C — lists `workflows/ 下的 .md` of a workspace (DI: WorkbenchVM → WorkspaceRepository). */
    loadWorkflows: suspend (String) -> List<String> = { emptyList() },
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val criteria = remember { mutableStateListOf<String>() }
    var assistantId by remember { mutableStateOf<String?>(null) }
    var workspaceId by remember { mutableStateOf<String?>(null) }
    var approvalMode by remember { mutableStateOf<String?>(null) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var maxResumeRounds by remember { mutableStateOf("") }
    var stallRounds by remember { mutableStateOf("") }
    var tokenBudget by remember { mutableStateOf("") }
    // 批 D — completion gate
    var requireConfirmation by remember { mutableStateOf(false) }
    var showAssistantSheet by remember { mutableStateOf(false) }
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    // 批 B — 开始方式
    var startMode by remember { mutableStateOf(MissionStartMode.IMMEDIATE) }
    var scheduleHour by remember { mutableStateOf("") }
    var scheduleMinute by remember { mutableStateOf("") }
    val scheduleDays = remember { mutableStateListOf<Int>() } // ISO 1..7；空 = 每天
    var triggerKind by remember { mutableStateOf<String?>(null) }
    var cooldownText by remember { mutableStateOf("60") }
    var maxRunsText by remember { mutableStateOf("24") }
    // 批 C — workflow document picked from the selected workspace (`workflows/ 下的 .md`).
    var workflowPath by remember { mutableStateOf<String?>(null) }
    var workflowNames by remember { mutableStateOf<List<String>>(emptyList()) }

    // Reload the list whenever the workspace changes; drop a selection that no longer exists.
    androidx.compose.runtime.LaunchedEffect(workspaceId) {
        workflowNames = workspaceId
            ?.let { id -> runCatching { loadWorkflows(id) }.getOrDefault(emptyList()) }
            ?: emptyList()
        val current = workflowPath
        if (current != null && current !in workflowNames.map { MissionWorkflowDocs.pathOf(it) }) {
            workflowPath = null
        }
    }

    val scheduleHourValid = scheduleHour.toIntOrNull()?.let { it in 0..23 } == true
    val scheduleMinuteValid = scheduleMinute.toIntOrNull()?.let { it in 0..59 } == true
    val scheduleTimeValid = scheduleHourValid && scheduleMinuteValid
    val createEnabled = title.isNotBlank() &&
        (startMode != MissionStartMode.SCHEDULED || scheduleTimeValid) &&
        (startMode != MissionStartMode.TRIGGER || triggerKind != null)

    val selectedAssistant = settings.assistants.firstOrNull { it.id.toString() == assistantId }
    val workspaceSheetAssistant = selectedAssistant ?: settings.assistants.firstOrNull()
    val selectedWorkspace = workspaces.firstOrNull { it.id == workspaceId }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.mission_create_title),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(text = stringResource(R.string.mission_create_field_title)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(text = stringResource(R.string.mission_create_field_description)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Text(
                text = stringResource(R.string.mission_create_acceptance),
                style = MaterialTheme.typography.titleSmall,
            )
            criteria.forEachIndexed { index, value ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { criteria[index] = it },
                        label = {
                            Text(text = stringResource(R.string.mission_create_acceptance_hint, index + 1))
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    IconButton(onClick = { criteria.removeAt(index) }) {
                        Icon(HugeIcons.Cancel01, contentDescription = null)
                    }
                }
            }
            TextButton(onClick = { criteria.add("") }) {
                Icon(HugeIcons.Add01, contentDescription = null)
                Text(
                    text = stringResource(R.string.mission_create_add_acceptance),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            // 执行助手
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAssistantSheet = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.mission_create_assistant),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = selectedAssistant?.name?.ifEmpty { null }
                        ?: stringResource(R.string.mission_create_assistant_default),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 工作区
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = workspaceSheetAssistant != null) {
                        showWorkspaceSheet = true
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.mission_create_workspace),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = selectedWorkspace?.name
                        ?: stringResource(R.string.mission_create_workspace_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 工作流程（批 C）— `workflows/ 下的 .md` in the selected workspace, or "auto"
            Text(
                text = stringResource(R.string.mission_create_workflow_title),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = workflowPath == null,
                    onClick = { workflowPath = null },
                    label = { Text(text = stringResource(R.string.mission_create_workflow_auto)) },
                )
                workflowNames.forEach { name ->
                    val path = MissionWorkflowDocs.pathOf(name)
                    FilterChip(
                        selected = workflowPath == path,
                        onClick = { workflowPath = path },
                        label = { Text(text = name.removeSuffix(".md")) },
                    )
                }
            }
            if (workspaceId != null && workflowNames.isEmpty()) {
                Text(
                    text = stringResource(R.string.mission_create_workflow_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 开始方式（批 B）
            Text(
                text = stringResource(R.string.mission_start_title),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val modes = listOf(
                    MissionStartMode.IMMEDIATE to R.string.mission_start_immediate,
                    MissionStartMode.SCHEDULED to R.string.mission_start_scheduled,
                    MissionStartMode.TRIGGER to R.string.mission_start_trigger,
                )
                modes.forEach { (mode, labelRes) ->
                    FilterChip(
                        selected = startMode == mode,
                        onClick = { startMode = mode },
                        label = { Text(text = stringResource(labelRes)) },
                    )
                }
            }

            // 定时：HH:mm + 周一..周日（空 = 每天）
            if (startMode == MissionStartMode.SCHEDULED) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = scheduleHour,
                        onValueChange = { scheduleHour = it.filter(Char::isDigit).take(2) },
                        label = { Text(text = stringResource(R.string.mission_start_hour)) },
                        isError = scheduleHour.isNotEmpty() && !scheduleHourValid,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text(
                        text = ":",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedTextField(
                        value = scheduleMinute,
                        onValueChange = { scheduleMinute = it.filter(Char::isDigit).take(2) },
                        label = { Text(text = stringResource(R.string.mission_start_minute)) },
                        isError = scheduleMinute.isNotEmpty() && !scheduleMinuteValid,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (1..7).forEach { day ->
                        FilterChip(
                            selected = day in scheduleDays,
                            onClick = {
                                if (day in scheduleDays) scheduleDays.remove(day) else scheduleDays.add(day)
                            },
                            label = { Text(text = stringResource(dayLabelRes(day))) },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.mission_start_days_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 触发：事件类型
            if (startMode == MissionStartMode.TRIGGER) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    triggerOptions.forEach { (key, labelRes) ->
                        FilterChip(
                            selected = triggerKind == key,
                            onClick = { triggerKind = key },
                            label = { Text(text = stringResource(labelRes)) },
                        )
                    }
                }
            }

            // 非立即模式：冷却秒数 + 每日上限
            if (startMode != MissionStartMode.IMMEDIATE) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = cooldownText,
                        onValueChange = { cooldownText = it.filter(Char::isDigit) },
                        label = { Text(text = stringResource(R.string.mission_start_cooldown)) },
                        placeholder = { Text(text = "60") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = maxRunsText,
                        onValueChange = { maxRunsText = it.filter(Char::isDigit) },
                        label = { Text(text = stringResource(R.string.mission_start_max_runs)) },
                        placeholder = { Text(text = "24") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }

            // 审批模式
            Text(
                text = stringResource(R.string.mission_create_approval_mode),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val options = listOf<Pair<String?, Int>>(
                    null to R.string.mission_approval_default,
                    "AUTO" to R.string.mission_approval_auto,
                    "SEMI" to R.string.mission_approval_semi,
                    "MANUAL" to R.string.mission_approval_manual,
                )
                options.forEach { (value, labelRes) ->
                    FilterChip(
                        selected = approvalMode == value,
                        onClick = { approvalMode = value },
                        label = { Text(text = stringResource(labelRes)) },
                    )
                }
            }

            // 高级选项
            TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                Text(text = stringResource(R.string.mission_create_advanced))
            }
            if (advancedExpanded) {
                Text(
                    text = stringResource(R.string.mission_create_advanced_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = maxResumeRounds,
                    onValueChange = { maxResumeRounds = it.filter(Char::isDigit) },
                    label = { Text(text = stringResource(R.string.mission_create_max_resume_rounds)) },
                    placeholder = { Text(text = "5") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = stallRounds,
                    onValueChange = { stallRounds = it.filter(Char::isDigit) },
                    label = { Text(text = stringResource(R.string.mission_create_stall_rounds)) },
                    placeholder = { Text(text = "2") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = tokenBudget,
                    onValueChange = { tokenBudget = it.filter(Char::isDigit) },
                    label = { Text(text = stringResource(R.string.mission_create_token_budget)) },
                    placeholder = { Text(text = "500000") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.mission_limits_confirm),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.mission_limits_confirm_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = requireConfirmation,
                        onCheckedChange = { requireConfirmation = it },
                    )
                }
            }

            Button(
                onClick = {
                    val trigger: TriggerSpec? = when (startMode) {
                        MissionStartMode.IMMEDIATE -> null
                        MissionStartMode.SCHEDULED -> TriggerSpec.TimeCron(
                            timeOfDay = "%02d:%02d".format(
                                scheduleHour.toIntOrNull() ?: 0,
                                scheduleMinute.toIntOrNull() ?: 0,
                            ),
                            daysOfWeek = scheduleDays.sorted(),
                        )
                        MissionStartMode.TRIGGER -> buildTriggerSpec(triggerKind)
                    }
                    onCreate(
                        MissionDraft(
                            title = title.trim(),
                            description = description.trim(),
                            goals = criteria.map { it.trim() }.filter { it.isNotEmpty() },
                            workspaceId = workspaceId,
                            assistantId = assistantId,
                            approvalMode = approvalMode,
                            maxResumeRounds = maxResumeRounds.toIntOrNull(),
                            stallRounds = stallRounds.toIntOrNull(),
                            tokenBudget = tokenBudget.toLongOrNull(),
                            requireConfirmation = requireConfirmation,
                            startMode = startMode,
                            trigger = trigger,
                            cooldownSeconds = cooldownText.toIntOrNull() ?: 60,
                            maxRunsPerDay = maxRunsText.toIntOrNull(),
                            workflowPath = workflowPath,
                        )
                    )
                    onDismiss()
                },
                enabled = createEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.mission_create_confirm))
            }
        }
    }

    // 执行助手选择
    if (showAssistantSheet) {
        ModalBottomSheet(onDismissRequest = { showAssistantSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.mission_select_assistant),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ListItem(
                    headlineContent = {
                        Text(text = stringResource(R.string.mission_create_assistant_default))
                    },
                    modifier = Modifier.clickable {
                        assistantId = null
                        showAssistantSheet = false
                    },
                )
                settings.assistants.forEach { assistant ->
                    ListItem(
                        headlineContent = { Text(text = assistant.name) },
                        leadingContent = { AssistantAvatar(assistant = assistant) },
                        modifier = Modifier.clickable {
                            assistantId = assistant.id.toString()
                            showAssistantSheet = false
                        },
                    )
                }
            }
        }
    }

    // 工作区选择
    if (showWorkspaceSheet && workspaceSheetAssistant != null) {
        WorkspaceSelectSheet(
            assistant = workspaceSheetAssistant,
            workspaces = workspaces,
            onSelect = { id ->
                workspaceId = id
                showWorkspaceSheet = false
            },
            onManage = {},
            onDismiss = { showWorkspaceSheet = false },
        )
    }
}
