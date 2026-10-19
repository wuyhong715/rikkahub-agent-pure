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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.mission.Artifact
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.runevent.RunEventType
import me.rerere.rikkahub.data.mission.acceptanceChecked
import me.rerere.rikkahub.data.mission.acceptanceCriteria
import me.rerere.rikkahub.data.mission.statusEnum
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
                            onReject = { vm.transition(MissionStatus.ACTIVE) },
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
                items(artifacts, key = { it.id }) { artifact ->
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
                items(timeline, key = { it.id }) { event ->
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
