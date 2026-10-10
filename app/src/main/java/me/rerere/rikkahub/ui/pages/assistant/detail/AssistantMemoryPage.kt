package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.vector.CloudEmbeddingModels
import me.rerere.rikkahub.data.vector.CloudEmbeddingRules
import me.rerere.rikkahub.data.vector.EmbeddingReadiness
import me.rerere.rikkahub.ui.components.ai.WorkspaceCwdPickerSheet
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.workspace.WorkspaceStorageArea
import androidx.compose.foundation.clickable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.vector.ConversationIndexCoordinator
import me.rerere.rikkahub.data.vector.LibraryIndexCoordinator
import me.rerere.rikkahub.data.vector.MemoryIndexCoordinator
import me.rerere.rikkahub.data.vector.ToolVectorIndex
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlinx.coroutines.launch

@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_memory))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantMemoryContent(
            innerPadding = innerPadding,
            assistant = assistant,
            memories = memories,
            workspaces = workspaces,
            onUpdateAssistant = { vm.update(it) },
            onDeleteMemory = { vm.deleteMemory(it) },
            onAddMemory = { vm.addMemory(it) },
            onUpdateMemory = { vm.updateMemory(it) }
        )
    }
}

@Composable
private fun AssistantMemoryContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    memories: List<AssistantMemory>,
    workspaces: List<WorkspaceEntity>,
    onUpdateAssistant: (Assistant) -> Unit,
    onAddMemory: (AssistantMemory) -> Unit,
    onUpdateMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
) {
    val memoryDialogState = useEditState<AssistantMemory> {
        if (it.id == 0) {
            onAddMemory(it)
        } else {
            onUpdateMemory(it)
        }
    }
    var pendingDeleteMemory by remember { mutableStateOf<AssistantMemory?>(null) }

    var showTimeReminderIntervalDialog by remember(assistant.id) { mutableStateOf(false) }
    var timeReminderIntervalInput by remember(assistant.id) { mutableStateOf("") }

    // T-06: the depth-of-memory picker. Only meaningful once the assistant is bound to a
    // workspace AND cold memory is on — the sheet browses that workspace's files area.
    var showColdMemoryDirPicker by remember(assistant.id) { mutableStateOf(false) }
    val coldMemoryWorkspace = workspaces.find { it.id == assistant.workspaceId?.toString() }
    var showLibraryDirPicker by remember(assistant.id) { mutableStateOf(false) }
    val libraryWorkspace = workspaces.find { it.id == assistant.workspaceId?.toString() }
    val workspaceRepository: WorkspaceRepository = koinInject()
    val scope = rememberCoroutineScope()

    // Moxw - the knowledge-base index. The model is a global setting (one embedding model is
    // resident at a time, so it cannot be a per-assistant choice), but it is shown here, next to
    // the knowledge base it serves.
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val memoryIndex: MemoryIndexCoordinator = koinInject()
    val indexStatus by memoryIndex.status.collectAsStateWithLifecycle()
    // P4 — the file library's index, shown the same way: a state a person can read and a rebuild
    // they can ask for, rather than an index that silently does or does not exist.
    val libraryIndex: LibraryIndexCoordinator = koinInject()
    val libraryIndexStatus by libraryIndex.status.collectAsStateWithLifecycle()
    // P5 — history search's index. App-wide rather than per-assistant, and shown here because this
    // is the page where the embedding model it depends on is chosen.
    val conversationIndex: ConversationIndexCoordinator = koinInject()
    val conversationIndexStatus by conversationIndex.status.collectAsStateWithLifecycle()
    // P3-03 — the tool catalogue's vectors. Same treatment as the knowledge-base index above: a
    // row the user can read the state of and ask for again, rather than an index that silently
    // does or does not exist.
    val toolVectors: ToolVectorIndex = koinInject()
    val toolIndexStatus by toolVectors.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showEmbeddingModelPicker by remember { mutableStateOf(false) }
    val embeddingVm: EmbeddingModelViewModel = koinViewModel()
    val embeddingInstalled by embeddingVm.installed.collectAsStateWithLifecycle()
    val embeddingDownload by embeddingVm.download.collectAsStateWithLifecycle()
    val embeddingError by embeddingVm.error.collectAsStateWithLifecycle()
    val indexStatusText = when {
        indexStatus.running -> stringResource(R.string.assistant_page_index_running)
        indexStatus.lastError != null ->
            stringResource(R.string.assistant_page_index_error, indexStatus.lastError.orEmpty())
        indexStatus.lastReport != null -> stringResource(
            R.string.assistant_page_index_report,
            indexStatus.lastReport!!.documents,
            indexStatus.lastReport!!.chunks,
        )
        else -> stringResource(R.string.assistant_page_index_never)
    }

    if (showTimeReminderIntervalDialog) {
        val interval = timeReminderIntervalInput.toIntOrNull()?.takeIf { it > 0 }
        AlertDialog(
            onDismissRequest = { showTimeReminderIntervalDialog = false },
            title = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
            text = {
                TextField(
                    value = timeReminderIntervalInput,
                    onValueChange = { timeReminderIntervalInput = it },
                    label = { Text(stringResource(R.string.assistant_page_time_reminder_interval_label)) },
                    supportingText = { Text(stringResource(R.string.assistant_page_time_reminder_interval_hint)) },
                    isError = interval == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = interval != null,
                    onClick = {
                        interval?.let {
                            onUpdateAssistant(assistant.copy(timeReminderIntervalMinutes = it))
                        }
                        showTimeReminderIntervalDialog = false
                    },
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimeReminderIntervalDialog = false }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            },
        )
    }

    // 记忆对话框
    memoryDialogState.EditStateContent { memory, update ->
        AlertDialog(
            onDismissRequest = {
                memoryDialogState.dismiss()
            },
            title = {
                Text(stringResource(R.string.assistant_page_manage_memory_title))
            },
            text = {
                TextField(
                    value = memory.content,
                    onValueChange = {
                        update(memory.copy(content = it))
                    },
                    label = {
                        Text(stringResource(R.string.assistant_page_manage_memory_title))
                    },
                    minLines = 2,
                    maxLines = 8
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_memory)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_memory_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableMemory = it
                                )
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_global_memory_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.useGlobalMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    useGlobalMemory = it
                                )
                            )
                        },
                        enabled = assistant.enableMemory
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_recent_chats_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableRecentChatsReference,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableRecentChatsReference = it
                                )
                            )
                        }
                    )
                }
            )
        }

        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_cold_memory)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_cold_memory_desc)) },
                trailingContent = {
                    Switch(
                        checked = assistant.coldMemoryEnabled,
                        onCheckedChange = { enabled ->
                            // The app used to leave the directory entirely to the user, but the
                            // picker could only select a folder that already existed and offered
                            // no way to make one — so cold memory could not actually be set up.
                            // Seed a default directory and create it, so flipping the switch just
                            // works; the picker is still there to change it.
                            val workspace = coldMemoryWorkspace
                            val updated = if (enabled && assistant.coldMemoryDir == null && workspace != null) {
                                assistant.copy(
                                    coldMemoryEnabled = true,
                                    coldMemoryDir = DEFAULT_COLD_MEMORY_DIR_ABSOLUTE,
                                )
                            } else {
                                assistant.copy(coldMemoryEnabled = enabled)
                            }
                            onUpdateAssistant(updated)
                            // Create the seeded directory only on the way in, matching where it is
                            // seeded: flipping the switch off should not create anything.
                            if (enabled && workspace != null && assistant.coldMemoryDir == null) {
                                scope.launch {
                                    runCatching {
                                        workspaceRepository.createDirectory(
                                            workspace.id,
                                            WorkspaceStorageArea.FILES,
                                            DEFAULT_COLD_MEMORY_DIR_RELATIVE,
                                        )
                                    }
                                }
                            }
                            // Turning it on is the moment to build the index, not some later
                            // conversation: waiting for one leaves the user looking at a
                            // "not indexed yet" row, rebuilding by hand and wondering whether the
                            // feature works at all.
                            memoryIndex.requestSync(updated, minIntervalMs = 0L)
                        }
                    )
                }
            )
            if (assistant.coldMemoryEnabled) {
                if (coldMemoryWorkspace == null) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.assistant_page_cold_memory_no_workspace))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.assistant_page_cold_memory_no_workspace_desc))
                        },
                    )
                } else {
                    item(
                        headlineContent = { Text(stringResource(R.string.assistant_page_cold_memory_dir)) },
                        supportingContent = {
                            Text(
                                text = assistant.coldMemoryDir
                                    ?: stringResource(R.string.assistant_page_cold_memory_dir_unset)
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = HugeIcons.ArrowRight01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = { showColdMemoryDirPicker = true },
                    )
                }
            }
        }

        // Moxw P4 — the file library. Sits next to cold memory because it is the same kind of
        // thing: a directory the user points at, indexed in the background, searched by meaning.
        // What differs is what it will read, and that is why the directory is picked explicitly
        // rather than assumed - a workspace can hold anything.
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_library)) },
                supportingContent = { Text(stringResource(R.string.assistant_page_library_desc)) },
                trailingContent = {
                    Switch(
                        checked = assistant.libraryEnabled,
                        onCheckedChange = { enabled ->
                            // Seeded and created exactly like cold memory, for the same reason: a
                            // picker that can only select an existing folder would leave the
                            // feature impossible to switch on.
                            val workspace = libraryWorkspace
                            val seed = enabled && assistant.libraryDir.isBlank() && workspace != null
                            val updated = if (seed) {
                                assistant.copy(
                                    libraryEnabled = true,
                                    libraryDir = DEFAULT_LIBRARY_DIR_ABSOLUTE,
                                )
                            } else {
                                assistant.copy(libraryEnabled = enabled)
                            }
                            onUpdateAssistant(updated)
                            if (seed && workspace != null) {
                                scope.launch {
                                    runCatching {
                                        workspaceRepository.createDirectory(
                                            workspace.id,
                                            WorkspaceStorageArea.FILES,
                                            DEFAULT_LIBRARY_DIR_RELATIVE,
                                        )
                                    }
                                }
                            }
                            // Same as cold memory: switching it on is the moment to build, not some
                            // later conversation.
                            libraryIndex.requestSync(updated, minIntervalMs = 0L)
                        }
                    )
                }
            )
            if (assistant.libraryEnabled) {
                if (libraryWorkspace == null) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.assistant_page_library_no_workspace))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.assistant_page_library_no_workspace_desc))
                        },
                    )
                } else {
                    item(
                        headlineContent = { Text(stringResource(R.string.assistant_page_library_dir)) },
                        supportingContent = {
                            Text(
                                text = assistant.libraryDir.ifBlank {
                                    DEFAULT_LIBRARY_DIR_ABSOLUTE
                                }
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = HugeIcons.ArrowRight01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = { showLibraryDirPicker = true },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.assistant_page_library_index)) },
                        supportingContent = {
                            val report = libraryIndexStatus.lastReport
                            Text(
                                when {
                                    libraryIndexStatus.running -> stringResource(
                                        R.string.assistant_page_index_running
                                    )

                                    libraryIndexStatus.lastError != null -> stringResource(
                                        R.string.assistant_page_index_error,
                                        libraryIndexStatus.lastError.orEmpty(),
                                    )

                                    report != null -> stringResource(
                                        R.string.assistant_page_library_index_report,
                                        report.indexedTotal,
                                        report.candidates,
                                        report.deferred,
                                    )

                                    else -> stringResource(R.string.assistant_page_library_index_idle)
                                }
                            )
                        },
                        trailingContent = {
                            TextButton(
                                onClick = { libraryIndex.requestSync(assistant, minIntervalMs = 0L) },
                                enabled = !libraryIndexStatus.running &&
                                    assistant.libraryEnabled && libraryWorkspace != null,
                            ) {
                                Text(stringResource(R.string.assistant_page_index_rebuild_action))
                            }
                        },
                    )
                }
            }
        }

        // P5 — history search. A row of its own rather than a line under the model, because it is
        // the one index whose absence is not a missing convenience: the keyword search it replaced
        // needed no model, so with none installed this feature does not exist.
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_history_index)) },
                supportingContent = {
                    val report = conversationIndexStatus.lastReport
                    Text(
                        when {
                            conversationIndexStatus.running -> stringResource(
                                R.string.assistant_page_index_running
                            )

                            conversationIndexStatus.lastError != null -> stringResource(
                                R.string.assistant_page_index_error,
                                conversationIndexStatus.lastError.orEmpty(),
                            )

                            embeddingInstalled.isEmpty() -> stringResource(
                                R.string.assistant_page_history_index_no_model
                            )

                            report != null -> stringResource(
                                R.string.assistant_page_history_index_report,
                                report.indexedTotal,
                                report.read,
                                report.deferred,
                            )

                            else -> stringResource(R.string.assistant_page_history_index_idle)
                        }
                    )
                },
                trailingContent = {
                    TextButton(
                        onClick = { conversationIndex.requestSync(minIntervalMs = 0L) },
                        enabled = !conversationIndexStatus.running,
                    ) {
                        Text(stringResource(R.string.assistant_page_index_rebuild_action))
                    }
                },
            )
        }

        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_embedding_model)) },
                supportingContent = {
                    Text(
                        text = EmbeddingReadiness.label(settings).ifEmpty {
                            stringResource(R.string.assistant_page_embedding_model_auto)
                        }
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    embeddingVm.refresh()
                    showEmbeddingModelPicker = true
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_index_rebuild)) },
                supportingContent = { Text(indexStatusText) },
                trailingContent = {
                    TextButton(
                        onClick = {
                            // Zero interval: an explicit rebuild means now, not "unless one ran
                            // in the last five minutes".
                            memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                        },
                        enabled = !indexStatus.running && assistant.coldMemoryEnabled &&
                            assistant.workspaceId != null,
                    ) {
                        Text(stringResource(R.string.assistant_page_index_rebuild_action))
                    }
                },
            )
            // The catalogue's own index. There is no longer a mode where it would be beside the
            // point — every turn is built from it now.
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_tool_index_title)) },
                supportingContent = {
                    Text(
                        text = when {
                            toolIndexStatus.total == 0 ->
                                stringResource(R.string.assistant_page_tool_index_never)
                            !toolIndexStatus.hasVector ->
                                stringResource(R.string.assistant_page_tool_index_off)
                            else -> stringResource(
                                R.string.assistant_page_tool_index_ready,
                                toolIndexStatus.cached,
                                toolIndexStatus.total,
                            )
                        },
                    )
                },
                trailingContent = {
                    TextButton(
                        onClick = { scope.launch { toolVectors.rebuild() } },
                        enabled = toolIndexStatus.total > 0,
                    ) {
                        Text(stringResource(R.string.assistant_page_index_rebuild_action))
                    }
                },
            )
        }

        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_time_reminder_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableTimeReminder,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableTimeReminder = it
                                )
                            )
                        }
                    )
                }
            )
            if (assistant.enableTimeReminder) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval_desc)) },
                    trailingContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval_value, assistant.timeReminderIntervalMinutes)) },
                    onClick = {
                        timeReminderIntervalInput = assistant.timeReminderIntervalMinutes.toString()
                        showTimeReminderIntervalDialog = true
                    },
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.assistant_page_manage_memory_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .align(Alignment.CenterStart)
            )

            IconButton(
                onClick = {
                    memoryDialogState.open(AssistantMemory(0, ""))
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = null
                )
            }
        }

        memories.fastForEach { memory ->
            key(memory.id) {
                MemoryItem(
                    memory = memory,
                    onEditMemory = {
                        memoryDialogState.open(it)
                    },
                    onDeleteMemory = {
                        pendingDeleteMemory = it
                    }
                )
            }
        }
    }

    if (showColdMemoryDirPicker && coldMemoryWorkspace != null) {
        WorkspaceCwdPickerSheet(
            workspaceId = coldMemoryWorkspace.id,
            currentCwd = assistant.coldMemoryDir,
            onSelectCwd = { selected ->
                val updated = assistant.copy(coldMemoryDir = selected)
                onUpdateAssistant(updated)
                // Where the documents are is part of what the index is of: a new directory has
                // never been indexed, so this is a first build rather than a refresh.
                memoryIndex.requestSync(updated, minIntervalMs = 0L)
            },
            onDismiss = { showColdMemoryDirPicker = false },
        )
    }

    if (showLibraryDirPicker && libraryWorkspace != null) {
        WorkspaceCwdPickerSheet(
            workspaceId = libraryWorkspace.id,
            currentCwd = assistant.libraryDir.ifBlank { DEFAULT_LIBRARY_DIR_ABSOLUTE },
            onSelectCwd = { selected ->
                // The picker's reset button hands back null, and for this field that means the
                // default directory - not "the workspace root", which arrives as "/workspace".
                val updated = assistant.copy(libraryDir = selected.orEmpty())
                onUpdateAssistant(updated)
                // Where the files are is part of what the index is of: a different directory has
                // never been indexed, so this is a first build rather than a refresh.
                libraryIndex.requestSync(updated, minIntervalMs = 0L)
            },
            onDismiss = { showLibraryDirPicker = false },
        )
    }

    val cloudSelected = CloudEmbeddingRules.isCloud(settings.embeddingBackend)

    if (showEmbeddingModelPicker) {
        AlertDialog(
            onDismissRequest = { showEmbeddingModelPicker = false },
            title = { Text(stringResource(R.string.assistant_page_embedding_model)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    EmbeddingChoiceRow(
                        label = stringResource(R.string.assistant_page_embedding_source_local),
                        selected = !cloudSelected,
                        onClick = {
                            scope.launch {
                                settingsStore.update {
                                    it.copy(embeddingBackend = CloudEmbeddingRules.BACKEND_LOCAL)
                                }
                            }
                            memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                        },
                    )
                    EmbeddingChoiceRow(
                        label = stringResource(R.string.assistant_page_embedding_source_cloud),
                        selected = cloudSelected,
                        onClick = {
                            scope.launch {
                                settingsStore.update {
                                    it.copy(embeddingBackend = CloudEmbeddingRules.BACKEND_CLOUD)
                                }
                            }
                            memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                        },
                    )
                    if (cloudSelected) {
                        Text(
                            text = stringResource(R.string.assistant_page_embedding_cloud_notice),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        val cloudCandidates = remember(settings.providers) {
                            CloudEmbeddingModels.candidates(settings.providers)
                        }
                        if (cloudCandidates.isEmpty()) {
                            Text(
                                text = stringResource(R.string.assistant_page_embedding_cloud_none),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        cloudCandidates.forEach { candidate ->
                            EmbeddingChoiceRow(
                                label = if (candidate.supported) {
                                    candidate.displayName
                                } else {
                                    stringResource(
                                        R.string.assistant_page_embedding_cloud_unsupported,
                                        candidate.providerName,
                                    )
                                },
                                selected = settings.embeddingCloudModel == candidate.modelUuid,
                                onClick = {
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(embeddingCloudModel = candidate.modelUuid)
                                        }
                                    }
                                    memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                                    showEmbeddingModelPicker = false
                                },
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.assistant_page_embedding_model_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        EmbeddingChoiceRow(
                            label = stringResource(R.string.assistant_page_embedding_model_auto),
                            selected = settings.embeddingModelFile.isEmpty(),
                            onClick = {
                                scope.launch { settingsStore.update { it.copy(embeddingModelFile = "") } }
                                memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                                showEmbeddingModelPicker = false
                            },
                        )
                        embeddingInstalled.forEach { fileName ->
                            EmbeddingChoiceRow(
                                label = fileName,
                                selected = settings.embeddingModelFile == fileName,
                                onClick = {
                                    scope.launch { settingsStore.update { it.copy(embeddingModelFile = fileName) } }
                                    memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                                    showEmbeddingModelPicker = false
                                },
                            )
                        }

                        // The curated models, for a fresh install: pasting a HuggingFace URL into the
                        // local-model page works, but it asks the user to know one.
                        val downloadable = embeddingVm.downloadable
                        if (downloadable.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.assistant_page_embedding_suggested),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            downloadable.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.assistant_page_embedding_size_mb,
                                                (entry.sizeBytes / 1_000_000L).toInt(),
                                                entry.dim,
                                                entry.contextLabel,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            embeddingVm.download(entry) {
                                                memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                                            }
                                        },
                                        enabled = embeddingDownload == null,
                                    ) {
                                        Text(stringResource(R.string.assistant_page_embedding_download))
                                    }
                                }
                            }
                        }

                        embeddingDownload?.let { inFlight ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.assistant_page_embedding_downloading,
                                        inFlight.percent,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                LinearProgressIndicator(
                                    progress = { inFlight.percent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                TextButton(onClick = { embeddingVm.cancel() }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        }

                        embeddingError?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEmbeddingModelPicker = false }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    RikkaConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let(onDeleteMemory)
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        text = {
            Text(
                text = pendingDeleteMemory?.content.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@Composable
private fun EmbeddingChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MemoryItem(
    memory: AssistantMemory,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = memory.content,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                onClick = { onEditMemory(memory) }
            ) {
                Icon(HugeIcons.PencilEdit01, null)
            }
            IconButton(
                onClick = { onDeleteMemory(memory) }
            ) {
                Icon(
                    HugeIcons.Delete01,
                    stringResource(R.string.assistant_page_delete)
                )
            }
        }
    }
}

/**
 * Where cold memory goes when the user turns the feature on without having picked a directory.
 * `<workspace>/memory` inside the files area — the same form the picker writes
 * ("/workspace/memory") and the relative form the repository methods take ("memory").
 */
// Must stay in step with WorkspaceLibraryRules.DEFAULT_DIR: this one seeds the field, that one
// resolves a blank field, and the two disagreeing would send the index somewhere the UI never
// showed.
private const val DEFAULT_LIBRARY_DIR_ABSOLUTE = "/workspace/library"
private const val DEFAULT_LIBRARY_DIR_RELATIVE = "library"
private const val DEFAULT_COLD_MEMORY_DIR_ABSOLUTE = "/workspace/memory"
private const val DEFAULT_COLD_MEMORY_DIR_RELATIVE = "memory"
