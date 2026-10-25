package me.rerere.rikkahub.ui.pages.workbench

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.mission.Artifact
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionExecutionState
import me.rerere.rikkahub.data.mission.MissionHook
import me.rerere.rikkahub.data.mission.MissionHookAction
import me.rerere.rikkahub.data.mission.MissionHookSpec
import me.rerere.rikkahub.data.mission.MissionHookWhen
import me.rerere.rikkahub.data.mission.MissionLimits
import me.rerere.rikkahub.data.mission.MissionSnapshotStore
import me.rerere.rikkahub.data.mission.MissionSnapshot
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.data.mission.acceptanceChecked
import me.rerere.rikkahub.data.mission.AcceptanceCheckSpec
import me.rerere.rikkahub.data.mission.acceptanceCriteria
import me.rerere.rikkahub.data.mission.executionStateEnum
import me.rerere.rikkahub.data.mission.limits
import me.rerere.rikkahub.data.mission.statusEnum
import me.rerere.rikkahub.data.mission.triggerSpec
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionDetailPage(missionId: String) {
    val vm: MissionDetailVM = koinViewModel(parameters = { parametersOf(missionId) })
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val mission by vm.mission.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val settlement by vm.settlement.collectAsStateWithLifecycle()
    val artifacts by vm.artifacts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var expandedArtifactId by remember { mutableStateOf<String?>(null) }
    var previewArtifact by remember { mutableStateOf<Pair<String, String>?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var showLimitsDialog by remember { mutableStateOf(false) }
    var showWorkflowDialog by remember { mutableStateOf(false) }
    var showReworkDialog by remember { mutableStateOf(false) }
    var showSnapshotsDialog by remember { mutableStateOf(false) }
    var showChecksDialog by remember { mutableStateOf(false) }
    // 批 I — 分叉
    var showForkDialog by remember { mutableStateOf(false) }

    // 批 F — 回退结果（成功/失败）用 toast 报一次，然后清掉。
    val rollbackMessage by vm.rollbackMessage.collectAsStateWithLifecycle()
    LaunchedEffect(rollbackMessage) {
        rollbackMessage?.let {
            toaster.show(it)
            vm.clearRollbackMessage()
        }
    }

    // 批 I — 分叉结果：成功/失败都用 toast 报一次。
    val forkOutcome by vm.forkOutcome.collectAsStateWithLifecycle()
    LaunchedEffect(forkOutcome) {
        forkOutcome?.let {
            toaster.show(it.message)
            vm.clearForkOutcome()
        }
    }

    // Batch A — surface launch failures (validation refusals from MissionLauncher) as toasts.
    val launchError by vm.launchError.collectAsStateWithLifecycle()
    LaunchedEffect(launchError) {
        launchError?.let {
            toaster.show(it)
            vm.clearLaunchError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = mission?.title ?: stringResource(R.string.mission_default_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(HugeIcons.DragDropVertical, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.mission_menu_archive)) },
                                leadingIcon = { Icon(HugeIcons.BookOpen01, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    vm.transition(MissionStatus.ARCHIVED)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.mission_menu_delete)) },
                                leadingIcon = { Icon(HugeIcons.Delete02, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.mission_menu_workflow)) },
                                leadingIcon = { Icon(HugeIcons.BookOpen01, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showWorkflowDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.mission_menu_limits)) },
                                leadingIcon = { Icon(HugeIcons.Wrench01, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showLimitsDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.mission_menu_fork)) },
                                leadingIcon = { Icon(HugeIcons.Copy01, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showForkDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.mission_menu_snapshots)) },
                                leadingIcon = { Icon(HugeIcons.Refresh03, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    vm.refreshSnapshots()
                                    showSnapshotsDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(text = stringResource(R.string.mission_menu_checks)) },
                                leadingIcon = { Icon(HugeIcons.Tick02, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showChecksDialog = true
                                },
                            )
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ① Hero card
            item {
                mission?.let { m ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 1.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = m.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                StatusPill(status = m.statusEnum)
                            }
                            if (m.description.isNotBlank()) {
                                Text(
                                    text = m.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // 批 C/D — which playbook this mission follows. Always shown (a null path is a
                            // real mode: the model picks a document on each launch).
                            Text(
                                text = stringResource(
                                    R.string.mission_workflow_label,
                                    m.workflowPath?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.mission_workflow_model_chooses),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.mission_created_at, relativeTime(m.createdAt)) +
                                    " · " +
                                    stringResource(R.string.mission_updated_at, relativeTime(m.updatedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (m.conversationId != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.MessageAdd01,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = stringResource(R.string.mission_bound_conversation),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ①b Settlement card (batch 1 — delivery loop)
            settlement?.let { s ->
                item {
                    SettlementCard(settlement = s)
                }
            }

            // ①c Run zone (batch A — runtime controls) + done summary
            mission?.let { m ->
                val status = m.statusEnum
                val exec = m.executionStateEnum
                if (status == MissionStatus.DONE) {
                    item {
                        DoneSummaryCard(events = events)
                    }
                } else if (status != MissionStatus.REVIEW && status != MissionStatus.ARCHIVED) {
                    when {
                        exec == MissionExecutionState.RUNNING -> {
                            item {
                                RunZoneCard(
                                    badgeRes = R.string.mission_run_running,
                                    badgeColor = MaterialTheme.colorScheme.primary,
                                    showDot = true,
                                    currentAction = events
                                        .filter { it.type == RunEventType.TOOL_RESULT.name }
                                        .maxByOrNull { it.createdAt }
                                        ?.title,
                                    primaryAction = false,
                                    actionLabel = stringResource(R.string.mission_run_stop),
                                    actionIcon = HugeIcons.StopCircle,
                                    onAction = { vm.stopRun() },
                                )
                            }
                        }

                        exec == MissionExecutionState.WAITING_APPROVAL -> {
                            item {
                                RunZoneCard(
                                    badgeRes = R.string.mission_run_waiting_approval,
                                    badgeColor = MaterialTheme.colorScheme.tertiary,
                                    showDot = true,
                                    currentAction = null,
                                    primaryAction = false,
                                    actionLabel = stringResource(R.string.mission_run_stop),
                                    actionIcon = HugeIcons.StopCircle,
                                    onAction = { vm.stopRun() },
                                )
                            }
                        }

                        status == MissionStatus.ACTIVE && exec == MissionExecutionState.IDLE -> {
                            item {
                                RunZoneCard(
                                    badgeRes = R.string.mission_run_ready,
                                    badgeColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    showDot = false,
                                    currentAction = null,
                                    primaryAction = true,
                                    actionLabel = stringResource(R.string.mission_run_start),
                                    actionIcon = null,
                                    onAction = { vm.launchMission() },
                                )
                            }
                        }

                        exec == MissionExecutionState.BLOCKED || status == MissionStatus.BLOCKED -> {
                            item {
                                RunZoneCard(
                                    badgeRes = R.string.mission_run_blocked,
                                    badgeColor = MaterialTheme.colorScheme.error,
                                    showDot = false,
                                    currentAction = null,
                                    primaryAction = false,
                                    actionLabel = stringResource(R.string.mission_run_relaunch),
                                    actionIcon = HugeIcons.Refresh03,
                                    onAction = { vm.relaunch() },
                                )
                            }
                        }
                    }
                }
            }

            // ①d Start mode card (批 B — trigger arming; run-zone three-state logic untouched)
            mission?.let { m ->
                val spec = m.triggerSpec
                val status = m.statusEnum
                if (spec != null && (status == MissionStatus.ACTIVE || status == MissionStatus.BLOCKED)) {
                    item {
                        TriggerCard(
                            mission = m,
                            onToggle = { enabled -> vm.setTriggerEnabled(m.id, enabled) },
                        )
                    }
                }
            }

            // ② Acceptance criteria
            mission?.let { m ->
                val criteria = m.acceptanceCriteria
                if (criteria.isNotEmpty()) {
                    item {
                        val checked = m.acceptanceChecked
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            tonalElevation = 1.dp,
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.mission_acceptance_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.mission_acceptance_progress,
                                            checked.size,
                                            criteria.size,
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = {
                                        checked.size.toFloat() / criteria.size.toFloat()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                criteria.forEachIndexed { index, criterion ->
                                    val isChecked = index in checked
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { vm.toggleAcceptance(index) },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { vm.toggleAcceptance(index) },
                                        )
                                        Text(
                                            text = criterion,
                                            style = MaterialTheme.typography.bodyMedium,
                                            textDecoration = if (isChecked) TextDecoration.LineThrough else null,
                                            color = if (isChecked) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ③ Status actions — REVIEW uses the acceptance gate, otherwise chips (batch 1)
                item {
                    val s = settlement
                    if (m.statusEnum == MissionStatus.REVIEW && s != null) {
                        ReviewPanel(
                            settlement = s,
                            onApprove = { vm.transition(MissionStatus.DONE) },
                            onReject = { showReworkDialog = true },
                            onArchive = { vm.transition(MissionStatus.ARCHIVED) },
                        )
                    } else {
                        val targets = MissionStatus.entries.filter { to ->
                            to != MissionStatus.ARCHIVED && m.statusEnum.canTransitionTo(to)
                        }
                        if (targets.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                targets.forEach { to ->
                                    AssistChip(
                                        onClick = { vm.transition(to) },
                                        label = { Text(text = missionActionLabel(to)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ④ Artifacts (batch 1 — artifact library: open / share / expand)
            if (artifacts.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.mission_artifacts_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                // Keys are namespaced: an Artifact is a projection of an ARTIFACT event, so its
                // id IS that event's id. Using the raw id here would collide with the same event
                // rendered in the timeline section below — one LazyColumn, two items with the
                // same key → IllegalArgumentException at measure time.
                items(artifacts, key = { "artifact:${it.id}" }) { artifact ->
                    ArtifactRow(
                        artifact = artifact,
                        expanded = expandedArtifactId == artifact.id,
                        onToggleExpand = {
                            expandedArtifactId =
                                if (expandedArtifactId == artifact.id) null else artifact.id
                        },
                        onOpen = { path ->
                            scope.launch {
                                val text = vm.loadArtifactText(path)
                                if (text != null) {
                                    previewArtifact = path to text
                                } else {
                                    toaster.show(context.getString(R.string.mission_artifact_open_failed))
                                }
                            }
                        },
                        onShare = { path ->
                            scope.launch {
                                val file = vm.exportArtifactToCache(path, context.cacheDir)
                                if (file == null) {
                                    toaster.show(context.getString(R.string.mission_artifact_open_failed))
                                    return@launch
                                }
                                runCatching {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/octet-stream"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, null))
                                }
                            }
                        },
                    )
                }
            }

            // ⑤ Timeline
            val timeline = events.sortedBy { it.createdAt }
            if (timeline.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.mission_timeline_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(timeline, key = { "event:${it.id}" }) { event ->
                    TimelineRow(event = event)
                }
            }

            // ⑥ Add note
            item {
                OutlinedButton(
                    onClick = { showNoteDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(HugeIcons.Edit02, contentDescription = null)
                    Text(
                        text = stringResource(R.string.mission_add_note),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }

    if (showLimitsDialog) {
        mission?.let { m ->
            LimitsDialog(
                limits = m.limits,
                onDismiss = { showLimitsDialog = false },
                onSave = { updated ->
                    vm.updateLimits(updated)
                    showLimitsDialog = false
                },
            )
        }
    }

    if (showSnapshotsDialog) {
        SnapshotDialog(vm = vm, onDismiss = { showSnapshotsDialog = false })
    }

    // 批 I — 分叉：复制当前产物到一个新工作区，另起一个任务走另一条路。
    if (showForkDialog) {
        ForkDialog(
            onDismiss = { showForkDialog = false },
            onConfirm = { instruction ->
                showForkDialog = false
                vm.fork(instruction)
            },
        )
    }

    if (showChecksDialog) {
        mission?.let { m ->
            ChecksDialog(
                limits = m.limits,
                onDismiss = { showChecksDialog = false },
                onSave = { updated ->
                    vm.updateLimits(updated)
                    showChecksDialog = false
                },
            )
        }
    }

    if (showWorkflowDialog) {
        mission?.let { m ->
            WorkflowDialog(
                mission = m,
                vm = vm,
                onDismiss = { showWorkflowDialog = false },
            )
        }
    }

    if (showReworkDialog) {
        ReworkDialog(
            onDismiss = { showReworkDialog = false },
            onSubmit = { feedback ->
                showReworkDialog = false
                vm.requestChanges(feedback)
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(text = stringResource(R.string.mission_delete_title)) },
            text = { Text(text = stringResource(R.string.mission_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        vm.delete { navController.popBackStack() }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.mission_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(text = stringResource(R.string.mission_cancel))
                }
            },
        )
    }

    if (showNoteDialog) {
        var noteText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text(text = stringResource(R.string.mission_note_title)) },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    placeholder = { Text(text = stringResource(R.string.mission_note_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.addNote(noteText.trim())
                        showNoteDialog = false
                    },
                    enabled = noteText.isNotBlank(),
                ) {
                    Text(text = stringResource(R.string.mission_note_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false }) {
                    Text(text = stringResource(R.string.mission_cancel))
                }
            },
        )
    }

    previewArtifact?.let { (path, text) ->
        AlertDialog(
            onDismissRequest = { previewArtifact = null },
            title = {
                Text(
                    text = path.substringAfterLast('/'),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Text(
                    text = text.take(20_000),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { previewArtifact = null }) {
                    Text(text = stringResource(R.string.mission_artifact_close))
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 批 D — workflow document picker / creator + review rework dialog
// ---------------------------------------------------------------------------

/**
 * Pick the playbook this mission follows, or create a new one from the built-in template.
 *
 * The list is read from the mission workspace's `workflows/` directory on open — no cache,
 * because the user may have edited files in the workspace page since the last visit. The
 * "model chooses" row is the default (`workflowPath == null`). Creating a document writes
 * the template, pins the mission to it and closes the dialog; a duplicate/invalid name
 * keeps the dialog open with an error so the text is not lost.
 */
@Composable
private fun WorkflowDialog(
    mission: MissionEntity,
    vm: MissionDetailVM,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf<List<String>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val selected = mission.workflowPath?.takeIf { it.isNotBlank() }

    LaunchedEffect(mission.id) {
        docs = vm.listWorkflowDocs()
        loaded = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.mission_workflow_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.mission_workflow_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    WorkflowChoiceRow(
                        label = stringResource(R.string.mission_workflow_model_chooses),
                        selected = selected == null,
                        onClick = {
                            vm.setWorkflowPath(null)
                            onDismiss()
                        },
                    )
                    docs.forEach { path ->
                        WorkflowChoiceRow(
                            label = path,
                            selected = selected == path,
                            onClick = {
                                vm.setWorkflowPath(path)
                                onDismiss()
                            },
                        )
                    }
                }
                if (loaded && docs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.mission_workflow_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        error = null
                    },
                    label = { Text(text = stringResource(R.string.mission_workflow_new_name)) },
                    placeholder = { Text(text = "weekly-report") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(
                    enabled = newName.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val path = vm.createWorkflowDoc(newName)
                            busy = false
                            if (path != null) {
                                onDismiss()
                            } else {
                                error = context.getString(R.string.mission_workflow_new_failed)
                            }
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.mission_workflow_new_create))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

/** One selectable workflow row: radio dot + relative path. */
@Composable
private fun WorkflowChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 批 D — 验收打回: collect the change request, then return the mission to ACTIVE and start a
 * rework round. An empty feedback is allowed (the round self-checks against the goals).
 */
@Composable
private fun ReworkDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var feedback by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.mission_rework_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.mission_rework_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = feedback,
                    onValueChange = { feedback = it },
                    label = { Text(text = stringResource(R.string.mission_rework_field)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(feedback) }) {
                Text(text = stringResource(R.string.mission_rework_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// 批 B.5 — runtime limits editor
// ---------------------------------------------------------------------------

/**
 * Edit the circuit-breaker limits of an existing mission (detail page menu → "Run limits").
 *
 * Before this, limits could only be set in the create sheet, so a mission that kept parking
 * itself had to be deleted and recreated to be tuned. Fields are numeric-only text; the save
 * button stays disabled until all three parse. Empty input is not treated as "use default" —
 * the user edits concrete numbers, and the defaults are simply what the dialog opens with.
 */
@Composable
private fun LimitsDialog(
    limits: MissionLimits,
    onDismiss: () -> Unit,
    onSave: (MissionLimits) -> Unit,
) {
    var resume by remember(limits) { mutableStateOf(limits.maxResumeRounds.toString()) }
    var stall by remember(limits) { mutableStateOf(limits.stallRounds.toString()) }
    var tokens by remember(limits) { mutableStateOf(limits.tokenBudget.toString()) }
    var requireConfirm by remember(limits) { mutableStateOf(limits.requireConfirmation) }
    // 批 H — 时间维度与结构化验收。截止时间用「从现在起多少小时」输入：绝对时间戳对用户
    // 没有意义，而这个对话框本来就只在「我现在想调整任务」时打开。
    var roundTimeout by remember(limits) { mutableStateOf(limits.roundTimeoutMinutes.toString()) }
    var deadlineHours by remember(limits) {
        mutableStateOf(
            limits.deadlineAt
                ?.let { ((it - System.currentTimeMillis()).coerceAtLeast(0L) / 3_600_000L).toString() }
                .orEmpty()
        )
    }
    var wrapUp by remember(limits) { mutableStateOf(limits.wrapUpMinutes.toString()) }
    var checksText by remember(limits) {
        mutableStateOf(AcceptanceCheckSpec.toEditorLines(limits.acceptanceChecks))
    }
    // 批 I — 工具级 hook（前/后）。与验收条件同样是「一行一条」，因为它们是同一类东西：
    // 声明式的一小段断言/策略，用表单反而更慢。
    var toolHooksText by remember(limits) {
        mutableStateOf(me.rerere.rikkahub.data.mission.MissionHookSpec.toToolHookLines(limits.hooks))
    }

    val resumeValue = resume.toIntOrNull()?.takeIf { it >= 0 }
    val stallValue = stall.toIntOrNull()?.takeIf { it >= 0 }
    val tokensValue = tokens.toLongOrNull()?.takeIf { it >= 0 }
    val roundTimeoutValue = roundTimeout.toIntOrNull()?.takeIf { it in 1..10_000 }
    val wrapUpValue = wrapUp.toIntOrNull()?.takeIf { it >= 0 }
    val deadlineHoursValue = deadlineHours.takeIf { it.isNotBlank() }?.toIntOrNull()?.takeIf { it >= 0 }
    val valid = resumeValue != null && stallValue != null && tokensValue != null &&
        roundTimeoutValue != null && wrapUpValue != null &&
        (deadlineHours.isBlank() || deadlineHoursValue != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.mission_limits_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.mission_limits_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = resume,
                    onValueChange = { resume = it.filter(Char::isDigit).take(3) },
                    label = { Text(text = stringResource(R.string.mission_limits_resume)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = stall,
                    onValueChange = { stall = it.filter(Char::isDigit).take(3) },
                    label = { Text(text = stringResource(R.string.mission_limits_stall)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tokens,
                    onValueChange = { tokens = it.filter(Char::isDigit).take(9) },
                    label = { Text(text = stringResource(R.string.mission_limits_tokens)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = roundTimeout,
                    onValueChange = { roundTimeout = it.filter(Char::isDigit).take(3) },
                    label = { Text(text = stringResource(R.string.mission_limits_round_time)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deadlineHours,
                    onValueChange = { deadlineHours = it.filter(Char::isDigit).take(4) },
                    label = { Text(text = stringResource(R.string.mission_limits_deadline)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = wrapUp,
                    onValueChange = { wrapUp = it.filter(Char::isDigit).take(2) },
                    label = { Text(text = stringResource(R.string.mission_limits_wrapup)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = toolHooksText,
                    onValueChange = { toolHooksText = it },
                    label = { Text(text = stringResource(R.string.mission_limits_tool_hooks)) },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = checksText,
                    onValueChange = { checksText = it },
                    label = { Text(text = stringResource(R.string.mission_limits_acceptance)) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 批 D — completion gate: a model-declared done parks in REVIEW instead of
                // closing the mission, so the delivery is confirmed by the user.
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
                        checked = requireConfirm,
                        onCheckedChange = { requireConfirm = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (valid) {
                        onSave(
                            MissionLimits(
                                maxResumeRounds = resumeValue!!,
                                stallRounds = stallValue!!,
                                tokenBudget = tokensValue!!,
                                requireConfirmation = requireConfirm,
                                // 批 F — 检查命令不在这个对话框里改，但保存时必须原样带过去，
                                // 否则「改个轮数」会把用户配的 hook 一并抹掉。
                                maxCheckFailures = limits.maxCheckFailures,
                                // 批 I — 时间类 hook 不在这个对话框里编辑，原样带过去；
                                // 工具级 hook 由文本框重建。
                                hooks = limits.hooks.filterNot {
                                    it.whenKind == me.rerere.rikkahub.data.mission.MissionHookWhen.TOOL_BEFORE ||
                                        it.whenKind == me.rerere.rikkahub.data.mission.MissionHookWhen.TOOL_AFTER
                                } + me.rerere.rikkahub.data.mission.MissionHookSpec.parseToolHookLines(
                                    toolHooksText,
                                    failAction = limits.hooks.firstOrNull()?.failAction
                                        ?: me.rerere.rikkahub.data.mission.MissionHook.ACTION_BLOCK,
                                ),
                                roundTimeoutMinutes = roundTimeoutValue!!,
                                wrapUpMinutes = wrapUpValue!!,
                                deadlineAt = deadlineHoursValue
                                    ?.let { System.currentTimeMillis() + it * 3_600_000L },
                                acceptanceChecks = AcceptanceCheckSpec.parseEditorLines(checksText),
                            )
                        )
                    }
                },
                enabled = valid,
            ) {
                Text(text = stringResource(R.string.mission_limits_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// 批 B — start mode (trigger) card
// ---------------------------------------------------------------------------

/** Day-of-week label (ISO 1..7, 1 = Monday); shared with the create sheet's day chips. */
private fun dayLabelRes(day: Int): Int = when (day) {
    1 -> R.string.mission_start_day_mon
    2 -> R.string.mission_start_day_tue
    3 -> R.string.mission_start_day_wed
    4 -> R.string.mission_start_day_thu
    5 -> R.string.mission_start_day_fri
    6 -> R.string.mission_start_day_sat
    else -> R.string.mission_start_day_sun
}

/**
 * Human-readable trigger summary: TimeCron renders "定时 HH:mm（周一/三）" style text,
 * the event types offered by the create sheet render their localized label, anything
 * else falls back to the spec's class name (a technical identifier).
 */
@Composable
private fun triggerSummary(spec: TriggerSpec): String = when (spec) {
    is TriggerSpec.TimeCron -> {
        val time = spec.timeOfDay ?: spec.cron ?: ""
        val dayText = if (spec.daysOfWeek.isEmpty()) {
            stringResource(R.string.mission_trigger_every_day)
        } else {
            // Build the labels with a plain loop: stringResource is @Composable and must not
            // be called from a non-composable lambda such as joinToString's transform.
            val parts = mutableListOf<String>()
            for (day in spec.daysOfWeek.sorted()) {
                parts += stringResource(dayLabelRes(day))
            }
            parts.joinToString("/")
        }
        stringResource(R.string.mission_trigger_time_cron_summary, time, dayText)
    }
    is TriggerSpec.WifiConnected -> stringResource(R.string.mission_trigger_wifi_connected)
    is TriggerSpec.WifiDisconnected -> stringResource(R.string.mission_trigger_wifi_disconnected)
    TriggerSpec.PowerConnected -> stringResource(R.string.mission_trigger_power_connected)
    TriggerSpec.PowerDisconnected -> stringResource(R.string.mission_trigger_power_disconnected)
    TriggerSpec.BootCompleted -> stringResource(R.string.mission_trigger_boot_completed)
    TriggerSpec.ScreenOn -> stringResource(R.string.mission_trigger_screen_on)
    TriggerSpec.ScreenOff -> stringResource(R.string.mission_trigger_screen_off)
    else -> spec::class.simpleName ?: "Trigger"
}

@Composable
private fun TriggerCard(
    mission: MissionEntity,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.mission_trigger_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = mission.triggerEnabled,
                    onCheckedChange = onToggle,
                )
            }
            mission.triggerSpec?.let { spec ->
                Text(
                    text = triggerSummary(spec),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            mission.nextRunAt?.let { next ->
                Text(
                    text = stringResource(R.string.mission_trigger_next_run, relativeTime(next)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!mission.triggerEnabled) {
                Text(
                    text = stringResource(R.string.mission_trigger_paused),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RunZoneCard(
    badgeRes: Int,
    badgeColor: Color,
    /** Show the pulsing-dot style badge (RUNNING / WAITING_APPROVAL). */
    showDot: Boolean,
    currentAction: String?,
    /** Filled Button (start) vs OutlinedButton (stop / relaunch). */
    primaryAction: Boolean,
    actionLabel: String,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector?,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showDot) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(badgeColor, CircleShape)
                    )
                }
                Text(
                    text = stringResource(badgeRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = badgeColor,
                )
            }
            if (currentAction != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Wrench01,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.mission_run_current_action, currentAction),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (primaryAction) {
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(text = actionLabel)
                }
            } else {
                OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    if (actionIcon != null) {
                        Icon(
                            imageVector = actionIcon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = actionLabel,
                        modifier = Modifier.padding(start = if (actionIcon != null) 8.dp else 0.dp),
                    )
                }
            }
        }
    }
}

/** Extract the human summary from a `mission_report: done` NOTE payload's `"note":"..."` field. */
private fun extractReportNote(payloadJson: String?): String? {
    if (payloadJson.isNullOrBlank()) return null
    val match = Regex(""""note"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(payloadJson) ?: return null
    return match.groupValues[1]
        .replace("\\n", "\n")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .takeIf { it.isNotBlank() }
}

@Composable
private fun DoneSummaryCard(events: List<RunEventEntity>) {
    val report = events
        .filter { it.type == RunEventType.NOTE.name && it.title == "mission_report: done" }
        .maxByOrNull { it.createdAt }
    val summary = extractReportNote(report?.payloadJson)
        ?: report?.detail?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.mission_done_fallback)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.mission_done_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.mission_done_summary_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun missionActionLabel(target: MissionStatus): String = stringResource(
    when (target) {
        MissionStatus.BLOCKED -> R.string.mission_action_block
        MissionStatus.ACTIVE -> R.string.mission_action_activate
        MissionStatus.REVIEW -> R.string.mission_action_send_review
        MissionStatus.DONE -> R.string.mission_action_complete
        MissionStatus.ARCHIVED -> R.string.mission_menu_archive
    }
)

@Composable
private fun timelineDotColor(type: RunEventType): Color {
    val scheme = MaterialTheme.colorScheme
    return when (type) {
        RunEventType.CREATED, RunEventType.STATUS_CHANGED -> scheme.primary
        RunEventType.ARTIFACT -> scheme.secondary
        RunEventType.SUBAGENT_DISPATCHED, RunEventType.SUBAGENT_FINISHED -> scheme.tertiary
        RunEventType.MILESTONE -> scheme.error
        RunEventType.NOTE, RunEventType.ACCEPTANCE_UPDATED -> scheme.outline
        RunEventType.TOOL_RESULT -> scheme.outline
        // P3 run lifecycle markers — neutral, they frame the work rather than describe it.
        RunEventType.RUN_STARTED, RunEventType.RUN_FINISHED -> scheme.outline
    }
}

@Composable
private fun TimelineRow(event: RunEventEntity) {
    val type = runCatching { RunEventType.valueOf(event.type) }.getOrNull()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (type == RunEventType.TOOL_RESULT) {
                Icon(
                    imageVector = HugeIcons.Wrench01,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = timelineDotColor(RunEventType.TOOL_RESULT),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = type?.let { timelineDotColor(it) } ?: MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        )
                )
            }
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
        Column(
            modifier = Modifier.padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
            )
            event.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = relativeTime(event.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 批 F — 轮次快照 / 回退
// ---------------------------------------------------------------------------

/**
 * 快照列表 + 回退。
 *
 * 展示的是「第 N 轮开始前」的那一份（以及回退时自动生成的安全副本）。回退要二次确认，因为它是
 * 覆盖写；文案里明确说了「当前状态会先存成备份」，用户知道这一步可撤销才敢点。
 */
@Composable
private fun SnapshotDialog(
    vm: MissionDetailVM,
    onDismiss: () -> Unit,
) {
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<MissionSnapshot?>(null) }
    val formatter = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.mission_snapshots_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.mission_snapshots_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (snapshots.isEmpty()) {
                    Text(
                        text = stringResource(R.string.mission_snapshots_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (snapshot in snapshots) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (snapshot.isSafetyCopy) {
                                    stringResource(R.string.mission_snapshots_safety)
                                } else {
                                    stringResource(R.string.mission_snapshots_round, snapshot.round)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.mission_snapshots_files,
                                    snapshot.fileCount,
                                    formatter.format(java.util.Date(snapshot.createdAt)),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { pending = snapshot }) {
                            Text(text = stringResource(R.string.mission_snapshots_rollback))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )

    pending?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(text = stringResource(R.string.mission_snapshots_confirm_title)) },
            text = {
                Text(text = stringResource(R.string.mission_snapshots_confirm_message, snapshot.round))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.rollbackTo(snapshot)
                        pending = null
                        onDismiss()
                    },
                ) {
                    Text(text = stringResource(R.string.mission_snapshots_rollback))
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 批 F — 检查命令（hooks）
// ---------------------------------------------------------------------------

/**
 * 三条检查命令的编辑器。
 *
 * 只有一个失败动作开关：三条 hook 共用一个策略，比逐条配置好理解。写回时走
 * [MissionHookSpec.of] —— 空白命令直接丢弃，所以「清空输入」= 关掉这条检查。
 */
@Composable
private fun ChecksDialog(
    limits: MissionLimits,
    onDismiss: () -> Unit,
    onSave: (MissionLimits) -> Unit,
) {
    var roundEnd by remember(limits) {
        mutableStateOf(MissionHookSpec.commandFor(limits, MissionHookWhen.ROUND_END))
    }
    var beforeDone by remember(limits) {
        mutableStateOf(MissionHookSpec.commandFor(limits, MissionHookWhen.BEFORE_DONE))
    }
    var afterFailure by remember(limits) {
        mutableStateOf(MissionHookSpec.commandFor(limits, MissionHookWhen.AFTER_FAILURE))
    }
    var blockOnFailure by remember(limits) {
        mutableStateOf(MissionHookSpec.actionOf(limits) == MissionHookAction.BLOCK)
    }
    var maxReworks by remember(limits) { mutableStateOf(limits.maxCheckFailures.toString()) }
    val maxReworksValue = maxReworks.toIntOrNull()?.takeIf { it >= 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.mission_checks_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.mission_checks_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = roundEnd,
                    onValueChange = { roundEnd = it },
                    label = { Text(text = stringResource(R.string.mission_checks_round_end)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = beforeDone,
                    onValueChange = { beforeDone = it },
                    label = { Text(text = stringResource(R.string.mission_checks_before_done)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = afterFailure,
                    onValueChange = { afterFailure = it },
                    label = { Text(text = stringResource(R.string.mission_checks_after_failure)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.mission_checks_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.mission_checks_max),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = maxReworks,
                        onValueChange = { maxReworks = it.filter(Char::isDigit).take(2) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(88.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.mission_checks_block),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(
                        checked = blockOnFailure,
                        onCheckedChange = { blockOnFailure = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = maxReworksValue ?: return@TextButton
                    onSave(
                        limits.copy(
                            maxCheckFailures = value,
                            hooks = MissionHookSpec.of(
                                roundEnd = roundEnd,
                                beforeDone = beforeDone,
                                afterFailure = afterFailure,
                                failAction = if (blockOnFailure) {
                                    MissionHook.ACTION_BLOCK
                                } else {
                                    MissionHook.ACTION_WARN
                                },
                            ),
                        )
                    )
                },
            ) {
                Text(text = stringResource(R.string.mission_limits_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// 批 I — 分叉对话框
// ---------------------------------------------------------------------------

/**
 * 分叉前的最后一步：问一句「另一条路是什么」。
 *
 * 允许留空——用户可能只是想复制一份去手动改。但填了就会进新任务的目标（每轮都注入），
 * 而不是只做一次性提示。
 */
@Composable
private fun ForkDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var instruction by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.mission_fork_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.mission_fork_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text(text = stringResource(R.string.mission_fork_instruction)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(instruction) }) {
                Text(text = stringResource(R.string.mission_fork_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}
