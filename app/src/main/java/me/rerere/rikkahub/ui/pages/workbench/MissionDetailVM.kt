package me.rerere.rikkahub.ui.pages.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.mission.Artifact
import me.rerere.rikkahub.data.mission.ArtifactsRepository
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.MissionSettlement
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.mission.acceptanceChecked
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.runevent.RunEventType
import java.io.ByteArrayOutputStream
import java.io.File

class MissionDetailVM(
    private val missionId: String,
    private val missionRepository: MissionRepository,
    private val artifactsRepository: ArtifactsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val mission: StateFlow<MissionEntity?> = missionRepository.observeMission(missionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val events: StateFlow<List<RunEventEntity>> = missionRepository.observeEvents(missionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Settlement card data — null until the mission row is first read. */
    val settlement: StateFlow<MissionSettlement?> = artifactsRepository.observeSettlement(missionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Artifacts produced by the mission, oldest first. */
    val artifacts: StateFlow<List<Artifact>> = artifactsRepository.observeArtifacts(missionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleAcceptance(index: Int) {
        viewModelScope.launch {
            val current = mission.value ?: return@launch
            val checked = current.acceptanceChecked.toMutableList()
            if (index in checked) {
                checked.remove(index)
            } else {
                checked.add(index)
            }
            missionRepository.setAcceptanceChecked(missionId, checked.sorted())
        }
    }

    fun transition(to: MissionStatus) {
        viewModelScope.launch {
            missionRepository.transition(missionId, to)
        }
    }

    fun addNote(text: String) {
        viewModelScope.launch {
            missionRepository.logEvent(
                missionId = missionId,
                type = RunEventType.NOTE,
                title = text,
            )
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            missionRepository.delete(missionId)
            onDeleted()
        }
    }

    // ------------------------------------------------------------------
    // Batch 1 — artifact open / share (delivery loop)
    // ------------------------------------------------------------------

    /**
     * Load an artifact file's text through the mission's bound workspace, for the
     * in-app preview dialog. Best-effort: null when the assistant / workspace / file
     * cannot be resolved (deleted workspace, oversized file, unreadable content).
     */
    suspend fun loadArtifactText(path: String): String? = try {
        val workspaceId = resolveWorkspaceId() ?: return null
        val size = workspaceRepository.rootfsFileSize(workspaceId, path)
        if (size > MAX_ARTIFACT_READ_BYTES) return null
        ByteArrayOutputStream().use { out ->
            workspaceRepository.exportRootfsFile(workspaceId, path, out)
            out.toString(Charsets.UTF_8.name())
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Export an artifact into [cacheDir] so the page can hand the file to a
     * FileProvider share intent. Best-effort; null when the file cannot be read.
     */
    suspend fun exportArtifactToCache(path: String, cacheDir: File): File? = try {
        val workspaceId = resolveWorkspaceId() ?: return null
        val dir = File(cacheDir, "artifact_share").apply { mkdirs() }
        val file = File(dir, path.substringAfterLast('/').ifBlank { "artifact" })
        file.outputStream().use { out ->
            workspaceRepository.exportRootfsFile(workspaceId, path, out)
        }
        file
    } catch (e: Exception) {
        null
    }

    /** Resolve the workspace bound to the mission's assistant, if any. */
    private suspend fun resolveWorkspaceId(): String? {
        val assistantId = mission.value?.assistantId ?: return null
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.assistants.firstOrNull { it.id.toString() == assistantId } ?: return null
        return assistant.workspaceId?.toString()
    }

    companion object {
        /** Preview / share cap — 2 MB, matching the workspace preview bound. */
        private const val MAX_ARTIFACT_READ_BYTES = 2L * 1024 * 1024
    }
}
