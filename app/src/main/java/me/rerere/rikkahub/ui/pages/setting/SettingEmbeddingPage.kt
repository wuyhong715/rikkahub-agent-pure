package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.llamacpp.EmbeddingFocus
import me.rerere.llamacpp.EmbeddingTier
import me.rerere.llamacpp.LlamaCppEmbeddingCatalog
import me.rerere.locallm.litert.LitertEmbeddingCatalog
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.vector.CloudEmbeddingModels
import me.rerere.rikkahub.data.vector.CloudEmbeddingRules
import me.rerere.rikkahub.data.vector.ConversationIndexCoordinator
import me.rerere.rikkahub.data.vector.EmbeddingAcceleratorRules
import me.rerere.rikkahub.data.vector.EmbeddingReadiness
import me.rerere.rikkahub.data.vector.LibraryIndexCoordinator
import me.rerere.rikkahub.data.vector.MemoryIndexCoordinator
import me.rerere.rikkahub.data.vector.ToolVectorIndex
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailVM
import me.rerere.rikkahub.ui.pages.assistant.detail.EmbeddingModelViewModel
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private const val LARGE_DOWNLOAD_BYTES = 100_000_000L

private data class PendingDownload(val sizeBytes: Long, val start: () -> Unit)

/**
 * The embedding model's own page.
 *
 * This used to be a dialog buried inside the assistant's memory page, which is why the settings
 * entry and the memory entry landed on the same screen: the model is global (one is resident at a
 * time), not a property of any assistant, so it gets a page of its own. The memory page keeps the
 * per-assistant knobs and links here; everything about the model - what it is, what it costs,
 * which indexes it feeds - lives below.
 */
@Composable
fun SettingEmbeddingPage() {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    // The indexes are built against an assistant's knowledge base, so the rebuild buttons need
    // one: the current assistant is the same context the memory page would have used.
    val assistantId = settings.getCurrentAssistant().id.toString()
    val detailVm: AssistantDetailVM = koinViewModel(
        key = "embedding-$assistantId",
        parameters = { parametersOf(assistantId) },
    )
    val assistant by detailVm.assistant.collectAsStateWithLifecycle()
    val workspaces by detailVm.workspaces.collectAsStateWithLifecycle()

    val embeddingVm: EmbeddingModelViewModel = koinViewModel()
    val installed by embeddingVm.installed.collectAsStateWithLifecycle()
    val installedBytes by embeddingVm.installedBytes.collectAsStateWithLifecycle()
    val download by embeddingVm.download.collectAsStateWithLifecycle()
    val downloadError by embeddingVm.error.collectAsStateWithLifecycle()

    val memoryIndex: MemoryIndexCoordinator = koinInject()
    val memoryIndexStatus by memoryIndex.status.collectAsStateWithLifecycle()
    val libraryIndex: LibraryIndexCoordinator = koinInject()
    val libraryIndexStatus by libraryIndex.status.collectAsStateWithLifecycle()
    val conversationIndex: ConversationIndexCoordinator = koinInject()
    val conversationIndexStatus by conversationIndex.status.collectAsStateWithLifecycle()
    val toolVectors: ToolVectorIndex = koinInject()
    val toolIndexStatus by toolVectors.state.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }
    var pendingDeleteFile by remember { mutableStateOf<String?>(null) }
    var showWindowPicker by remember { mutableStateOf(false) }

    // Re-read the directory on the way in: the user may have just installed or removed a file
    // through the local-model page, and the rows below should not be a session old.
    LaunchedEffect(Unit) { embeddingVm.refresh() }

    val cloudSelected = CloudEmbeddingRules.isCloud(settings.embeddingBackend)
    val cloudModel = EmbeddingReadiness.cloudModel(settings)
    // Computed up here rather than inside the CardGroup DSL: that lambda is not @Composable,
    // so remember() cannot live in it.
    val cloudCandidates = remember(settings.providers) {
        CloudEmbeddingModels.candidates(settings.providers)
    }
    val resolvedLocalFile = EmbeddingReadiness.localModelFile(settings, installed)
    val ready = cloudModel != null || resolvedLocalFile != null
    val readsImages = EmbeddingReadiness.localModelReadsImages(settings, installed)
    val gpuInEffect = EmbeddingAcceleratorRules.useGpu(settings.embeddingUseGpu)

    // The name a person would call the model in effect by: the curated display name when the file
    // is one of ours, the file name when it was installed by hand.
    val heroTitle: String = when {
        cloudModel != null -> cloudModel.displayName
        resolvedLocalFile != null ->
            LitertEmbeddingCatalog.entryFor(resolvedLocalFile)?.displayName
                ?: LlamaCppEmbeddingCatalog.entryFor(resolvedLocalFile)?.displayName
                ?: resolvedLocalFile
        else -> stringResource(R.string.setting_embedding_status_missing)
    }

    // The two facts a person cannot see anywhere else: how wide a vector is, and how much text it
    // reads at once. The window override is what will actually be loaded when there is one.
    val heroFacts: String? = run {
        if (cloudSelected) return@run null
        val file = resolvedLocalFile ?: return@run null
        LitertEmbeddingCatalog.entryFor(file)?.let { entry ->
            return@run stringResource(
                if (entry.vision) R.string.assistant_page_embedding_facts_vision
                else R.string.assistant_page_embedding_facts,
                entry.dim,
                settings.embeddingSignature.takeIf { it > 0 } ?: entry.signature,
            )
        }
        LlamaCppEmbeddingCatalog.entryFor(file)?.let { entry ->
            return@run stringResource(
                R.string.setting_embedding_facts_gguf,
                entry.dim,
                entry.contextLabel,
            )
        }
        null
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_embedding_model)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. The hero card answers, at a glance, the only question the settings row could not:
            // "what will actually run, and is anything missing?"
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (ready) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    shape = CircleShape,
                                ),
                        )
                        Text(
                            text = stringResource(
                                if (ready) R.string.setting_embedding_status_ready
                                else R.string.setting_embedding_status_missing
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = heroTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (heroFacts != null) {
                        Text(
                            text = heroFacts,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HeroBadge(
                            text = stringResource(
                                if (cloudSelected) R.string.assistant_page_embedding_source_cloud
                                else R.string.assistant_page_embedding_source_local
                            )
                        )
                        if (!cloudSelected) {
                            HeroBadge(text = if (gpuInEffect) "GPU" else "CPU")
                            if (settings.embeddingModelFile.isBlank() && resolvedLocalFile != null) {
                                HeroBadge(text = stringResource(R.string.setting_embedding_hero_auto))
                            }
                        }
                        HeroBadge(
                            text = stringResource(
                                R.string.setting_embedding_installed_summary,
                                installed.size,
                                formatModelBytes(installedBytes),
                            )
                        )
                    }
                }
            }

            // 2. An in-flight download sits directly under the hero card: while bytes are moving,
            // that is the most important thing on the page.
            download?.let { inFlight ->
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = inFlight.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.assistant_page_embedding_downloading,
                                inFlight.percent,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        LinearProgressIndicator(
                            progress = { inFlight.percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        TextButton(
                            onClick = { embeddingVm.cancel() },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
            downloadError?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // 3. The model itself: where vectors come from, and which file provides them.
            CardGroup(title = { Text(stringResource(R.string.setting_embedding_group_model)) }) {
                item(
                    onClick = {
                        scope.launch {
                            settingsStore.update {
                                it.copy(embeddingBackend = CloudEmbeddingRules.BACKEND_LOCAL)
                            }
                        }
                        memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                    },
                    leadingContent = {
                        RadioButton(selected = !cloudSelected, onClick = null)
                    },
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_embedding_source_local))
                    },
                )
                item(
                    onClick = {
                        scope.launch {
                            settingsStore.update {
                                it.copy(embeddingBackend = CloudEmbeddingRules.BACKEND_CLOUD)
                            }
                        }
                        memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                    },
                    leadingContent = {
                        RadioButton(selected = cloudSelected, onClick = null)
                    },
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_embedding_source_cloud))
                    },
                )
                if (cloudSelected) {
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.assistant_page_embedding_cloud_notice),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                    if (cloudCandidates.isEmpty()) {
                        item(
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.assistant_page_embedding_cloud_none),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                        )
                    }
                    cloudCandidates.forEach { candidate ->
                        item(
                            // An unsupported provider cannot be called this way, so the row says so
                            // and does not offer itself as a choice.
                            onClick = if (candidate.supported) {
                                {
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(embeddingCloudModel = candidate.modelUuid)
                                        }
                                    }
                                    memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                                }
                            } else {
                                null
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = settings.embeddingCloudModel == candidate.modelUuid,
                                    onClick = null,
                                    enabled = candidate.supported,
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = if (candidate.supported) {
                                        candidate.displayName
                                    } else {
                                        stringResource(
                                            R.string.assistant_page_embedding_cloud_unsupported,
                                            candidate.providerName,
                                        )
                                    }
                                )
                            },
                        )
                    }
                }
            }

            if (!cloudSelected) {
                // 4a. What is already on the phone. Automatic first: it is the recommended answer,
                // and the pinned files follow it.
                CardGroup(title = { Text(stringResource(R.string.setting_embedding_installed)) }) {
                    item(
                        onClick = {
                            scope.launch { settingsStore.update { it.copy(embeddingModelFile = "") } }
                            memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                        },
                        leadingContent = {
                            RadioButton(selected = settings.embeddingModelFile.isEmpty(), onClick = null)
                        },
                        headlineContent = {
                            Text(stringResource(R.string.assistant_page_embedding_model_auto))
                        },
                    )
                    installed.forEach { fileName ->
                        val litert = LitertEmbeddingCatalog.entryFor(fileName)
                        val gguf = LlamaCppEmbeddingCatalog.entryFor(fileName)
                        item(
                            onClick = {
                                scope.launch {
                                    settingsStore.update { it.copy(embeddingModelFile = fileName) }
                                }
                                memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = settings.embeddingModelFile == fileName,
                                    onClick = null,
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = litert?.displayName ?: gguf?.displayName ?: fileName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = when {
                                        litert != null -> stringResource(
                                            R.string.assistant_page_embedding_litert_size,
                                            (litert.sizeBytes / 1_000_000L).toInt(),
                                            litert.dim,
                                            litert.contextTokens,
                                        )
                                        gguf != null -> fileName
                                        else -> fileName
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { pendingDeleteFile = fileName }) {
                                    Icon(
                                        imageVector = HugeIcons.Delete01,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                }

                // 4b. The shelf: curated files not on the phone yet, in the grid's order, carrying
                // the two facts that decide each one - which rung it is, and whether the disk can
                // take it.
                val offers = embeddingVm.offers
                val litertOffers = embeddingVm.litertOffers
                if (offers.isNotEmpty() || litertOffers.isNotEmpty()) {
                    CardGroup(
                        title = { Text(stringResource(R.string.assistant_page_embedding_suggested)) }
                    ) {
                        offers.forEach { offer ->
                            val entry = offer.entry
                            item(
                                headlineContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = entry.displayName,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        offer.focus?.let { focus ->
                                            EmbeddingLaneChip(laneLabel(focus))
                                        }
                                        if (entry == embeddingVm.recommended) {
                                            EmbeddingLaneChip(
                                                text = stringResource(
                                                    R.string.assistant_page_embedding_recommended
                                                ),
                                                highlight = true,
                                            )
                                        }
                                    }
                                },
                                supportingContent = {
                                    Column {
                                        Text(
                                            text = stringResource(
                                                R.string.assistant_page_embedding_size_tier,
                                                tierLabel(offer.tier),
                                                (entry.sizeBytes / 1_000_000L).toInt(),
                                                entry.dim,
                                                entry.contextLabel,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        if (!offer.fits) {
                                            Text(
                                                text = stringResource(
                                                    R.string.assistant_page_embedding_no_room
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                },
                                trailingContent = {
                                    TextButton(
                                        onClick = {
                                            val start = {
                                                embeddingVm.download(entry) {
                                                    memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                                                }
                                            }
                                            if (embeddingVm.onMeteredNetwork &&
                                                entry.sizeBytes >= LARGE_DOWNLOAD_BYTES
                                            ) {
                                                pendingDownload = PendingDownload(entry.sizeBytes, start)
                                            } else {
                                                start()
                                            }
                                        },
                                        enabled = download == null && offer.fits,
                                    ) {
                                        Text(stringResource(R.string.assistant_page_embedding_download))
                                    }
                                },
                            )
                        }
                        litertOffers.forEach { offer ->
                            val entry = offer.entry
                            item(
                                headlineContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = entry.displayName,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        EmbeddingLaneChip(
                                            text = stringResource(
                                                R.string.assistant_page_embedding_lane_multimodal
                                            )
                                        )
                                    }
                                },
                                supportingContent = {
                                    Column {
                                        Text(
                                            text = stringResource(
                                                R.string.assistant_page_embedding_litert_size,
                                                (entry.sizeBytes / 1_000_000L).toInt(),
                                                entry.dim,
                                                entry.contextTokens,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        if (!offer.fits) {
                                            Text(
                                                text = stringResource(
                                                    R.string.assistant_page_embedding_no_room
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                },
                                trailingContent = {
                                    TextButton(
                                        onClick = {
                                            val start = {
                                                embeddingVm.download(entry) {
                                                    memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                                                }
                                            }
                                            if (embeddingVm.onMeteredNetwork &&
                                                entry.sizeBytes >= LARGE_DOWNLOAD_BYTES
                                            ) {
                                                pendingDownload = PendingDownload(entry.sizeBytes, start)
                                            } else {
                                                start()
                                            }
                                        },
                                        enabled = download == null && offer.fits,
                                    ) {
                                        Text(stringResource(R.string.assistant_page_embedding_download))
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // 5. The knobs that change how the model runs rather than which model runs.
            CardGroup(title = { Text(stringResource(R.string.setting_embedding_group_advanced)) }) {
                // The window, and only for a model whose window we choose: a GGUF carries its own,
                // so offering this for one would be a control with nothing behind it.
                if (!cloudSelected && LitertEmbeddingCatalog.entryFor(settings.embeddingModelFile) != null) {
                    item(
                        onClick = { showWindowPicker = true },
                        headlineContent = {
                            Text(stringResource(R.string.assistant_page_embedding_window))
                        },
                        supportingContent = {
                            Text(
                                text = if (settings.embeddingSignature <= 0) {
                                    stringResource(R.string.assistant_page_embedding_window_auto)
                                } else {
                                    stringResource(
                                        R.string.assistant_page_embedding_window_value,
                                        settings.embeddingSignature,
                                    )
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
                    )
                }
                item(
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_embedding_gpu))
                    },
                    supportingContent = {
                        Text(
                            text = if (gpuInEffect) {
                                stringResource(R.string.assistant_page_embedding_gpu_on)
                            } else {
                                stringResource(R.string.assistant_page_embedding_gpu_off)
                            }
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = gpuInEffect,
                            // Only a local model can use the device's GPU; a provider's endpoint
                            // has its own hardware, and there is nothing here to switch.
                            enabled = !cloudSelected,
                            onCheckedChange = { on ->
                                scope.launch {
                                    settingsStore.update { it.copy(embeddingUseGpu = on) }
                                }
                                // The two accelerators do not produce identical vectors (cosine
                                // 0.9997 on the same sentence), and a vector is only comparable to
                                // the ones stored beside it. The accelerator is part of the model
                                // identity, so every stored chunk goes stale and this sync rebuilds
                                // rather than mixing.
                                memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                            },
                        )
                    },
                )
                item(
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_embedding_native_images))
                    },
                    supportingContent = {
                        Text(
                            text = if (readsImages) {
                                stringResource(R.string.assistant_page_embedding_native_images_desc)
                            } else {
                                stringResource(R.string.assistant_page_embedding_native_images_not_vision)
                            }
                        )
                    },
                    trailingContent = {
                        Switch(
                            // Off when the model cannot see, whatever the setting says: the switch
                            // describes what will happen, not only what was chosen.
                            checked = settings.embeddingNativeImages && readsImages,
                            enabled = readsImages,
                            onCheckedChange = { on ->
                                scope.launch {
                                    settingsStore.update { it.copy(embeddingNativeImages = on) }
                                }
                                libraryIndex.requestSync(assistant, minIntervalMs = 0L)
                            },
                        )
                    },
                )
            }

            // 6. Everything this model feeds, in one place: the state of each index and the button
            // that asks for it again.
            CardGroup(title = { Text(stringResource(R.string.setting_embedding_group_indexes)) }) {
                item(
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_index_rebuild))
                    },
                    supportingContent = {
                        Text(
                            text = when {
                                memoryIndexStatus.running ->
                                    stringResource(R.string.assistant_page_index_running)
                                memoryIndexStatus.lastError != null -> stringResource(
                                    R.string.assistant_page_index_error,
                                    memoryIndexStatus.lastError.orEmpty(),
                                )
                                memoryIndexStatus.lastReport != null -> stringResource(
                                    R.string.assistant_page_index_report,
                                    memoryIndexStatus.lastReport!!.documents,
                                    memoryIndexStatus.lastReport!!.chunks,
                                )
                                else -> stringResource(R.string.assistant_page_index_never)
                            }
                        )
                    },
                    trailingContent = {
                        TextButton(
                            onClick = { memoryIndex.requestSync(assistant, minIntervalMs = 0L) },
                            enabled = !memoryIndexStatus.running && assistant.coldMemoryEnabled &&
                                assistant.workspaceId != null,
                        ) {
                            Text(stringResource(R.string.assistant_page_index_rebuild_action))
                        }
                    },
                )
                item(
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_library_index))
                    },
                    supportingContent = {
                        val report = libraryIndexStatus.lastReport
                        Text(
                            text = when {
                                libraryIndexStatus.running ->
                                    stringResource(R.string.assistant_page_index_running)
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
                        val libraryWorkspace =
                            workspaces.find { it.id == assistant.workspaceId?.toString() }
                        TextButton(
                            onClick = { libraryIndex.requestSync(assistant, minIntervalMs = 0L) },
                            enabled = !libraryIndexStatus.running && assistant.libraryEnabled &&
                                libraryWorkspace != null,
                        ) {
                            Text(stringResource(R.string.assistant_page_index_rebuild_action))
                        }
                    },
                )
                item(
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_history_index))
                    },
                    supportingContent = {
                        val report = conversationIndexStatus.lastReport
                        Text(
                            text = when {
                                conversationIndexStatus.running ->
                                    stringResource(R.string.assistant_page_index_running)
                                conversationIndexStatus.lastError != null -> stringResource(
                                    R.string.assistant_page_index_error,
                                    conversationIndexStatus.lastError.orEmpty(),
                                )
                                installed.isEmpty() && cloudModel == null ->
                                    stringResource(R.string.assistant_page_history_index_no_model)
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
                item(
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_tool_index_title))
                    },
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
                            }
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

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // The input window picker: a small closed set (automatic, plus the signatures the file
    // ships), so a dialog is the right size for it.
    if (showWindowPicker) {
        AlertDialog(
            onDismissRequest = { showWindowPicker = false },
            title = { Text(stringResource(R.string.assistant_page_embedding_window)) },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = settings.embeddingSignature <= 0,
                            onClick = {
                                scope.launch {
                                    settingsStore.update { it.copy(embeddingSignature = 0) }
                                }
                                memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                            },
                        )
                        Text(stringResource(R.string.assistant_page_embedding_window_auto))
                    }
                    LitertEmbeddingCatalog.SIGNATURES.forEach { signature ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.embeddingSignature == signature,
                                onClick = {
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(embeddingSignature = signature)
                                        }
                                    }
                                    memoryIndex.requestSync(assistant, minIntervalMs = 0L)
                                },
                            )
                            Text(
                                stringResource(
                                    R.string.assistant_page_embedding_window_value,
                                    signature,
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWindowPicker = false }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    // A model is hundreds of megabytes, and this is the one place in the app where a single tap
    // spends that much of somebody's data plan. Asked only on a metered connection and only above
    // [LARGE_DOWNLOAD_BYTES]: interrupting a 26 MB download over Wi-Fi is noise, and noise is how
    // a warning gets dismissed without being read.
    pendingDownload?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text(stringResource(R.string.assistant_page_embedding_metered_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.assistant_page_embedding_metered_desc,
                        (pending.sizeBytes / 1_000_000L).toInt(),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDownload = null
                        pending.start()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_embedding_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownload = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Deleting is recoverable - the file can be downloaded again - but it costs the download, so
    // it gets the one confirmation on this page.
    RikkaConfirmDialog(
        show = pendingDeleteFile != null,
        title = stringResource(R.string.setting_embedding_delete_title),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteFile?.let { file ->
                embeddingVm.delete(file)
                // The model identity changed, so the stored vectors no longer match what the
                // resolver will pick next.
                memoryIndex.requestSync(assistant, minIntervalMs = 0L)
            }
            pendingDeleteFile = null
        },
        onDismiss = { pendingDeleteFile = null },
        text = {
            val file = pendingDeleteFile.orEmpty()
            val displayName = LitertEmbeddingCatalog.entryFor(file)?.displayName
                ?: LlamaCppEmbeddingCatalog.entryFor(file)?.displayName
                ?: file
            val size = LitertEmbeddingCatalog.entryFor(file)?.sizeBytes
                ?: LlamaCppEmbeddingCatalog.entryFor(file)?.sizeBytes
            Text(
                text = stringResource(
                    R.string.setting_embedding_delete_desc,
                    displayName,
                    size?.let { formatModelBytes(it) }.orEmpty(),
                ),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** A small filled pill on the hero card: one fact, one glance. */
@Composable
private fun HeroBadge(text: String) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The small tag after a downloadable model's name: the lane it belongs to, or the pick. */
@Composable
private fun EmbeddingLaneChip(text: String, highlight: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (highlight) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondary
        },
        modifier = Modifier.padding(start = 6.dp),
    )
}

@Composable
private fun tierLabel(tier: EmbeddingTier?): String = stringResource(
    when (tier) {
        EmbeddingTier.LIGHT -> R.string.assistant_page_embedding_tier_light
        EmbeddingTier.STANDARD -> R.string.assistant_page_embedding_tier_standard
        EmbeddingTier.TOP -> R.string.assistant_page_embedding_tier_top
        null -> R.string.assistant_page_embedding_tier_extra
    }
)

@Composable
private fun laneLabel(focus: EmbeddingFocus): String = stringResource(
    if (focus == EmbeddingFocus.CHINESE) {
        R.string.assistant_page_embedding_lane_chinese
    } else {
        R.string.assistant_page_embedding_lane_multilingual
    }
)

private fun formatModelBytes(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 1000.0) {
        String.format(Locale.US, "%.1f GB", mb / 1000.0)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
    }
}
