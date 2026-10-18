package me.rerere.rikkahub.ui.pages.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionRepository
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.mission.acceptanceChecked
import me.rerere.rikkahub.data.runevent.RunEventEntity
import me.rerere.rikkahub.data.runevent.RunEventType

class MissionDetailVM(
    private val missionId: String,
    private val missionRepository: MissionRepository,
) : ViewModel() {

    val mission: StateFlow<MissionEntity?> = missionRepository.observeMission(missionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val events: StateFlow<List<RunEventEntity>> = missionRepository.observeEvents(missionId)
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
}
