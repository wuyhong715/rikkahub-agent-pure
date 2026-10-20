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
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.mission.Artifact
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionExecutionState
import me.rerere.rikkahub.data.mission.MissionLimits
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.data.mission.acceptanceChecked
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

    val resumeValue = resume.toIntOrNull()?.takeIf { it >= 0 }
    val stallValue = stall.toIntOrNull()?.takeIf { it >= 0 }
    val tokensValue = tokens.toLongOrNull()?.takeIf { it >= 0 }
    val valid = resumeValue != null && stallValue != null && tokensValue != null

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
