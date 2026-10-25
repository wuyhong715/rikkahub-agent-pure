package me.rerere.rikkahub.ui.pages.setting.instances

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.runtime.InstanceId
import me.rerere.rikkahub.data.runtime.InstanceKind
import me.rerere.rikkahub.data.runtime.InstanceRegistry
import me.rerere.rikkahub.data.runtime.RuntimeInstance

/**
 * 运行实例页的状态。
 *
 * 只做两件事：把 [InstanceRegistry] 的合并视图交给 UI，并在用户点「重新检测」时触发一次
 * 强制探测。[busy] 是"正在探测的实例键"，用来给行内放进度而不是整页 spinner——SSH 可能要几秒。
 */
class InstancesVM(
    private val registry: InstanceRegistry,
) : ViewModel() {

    val instances: StateFlow<List<RuntimeInstance>> = registry.instances

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    fun refresh(id: InstanceId) {
        if (id.serial in _busy.value) return
        _busy.update { it + id.serial }
        viewModelScope.launch {
            try {
                registry.refresh(id, force = true)
            } finally {
                _busy.update { it - id.serial }
            }
        }
    }

    /**
     * 全部重探。App 宿主是本地检查（TTL 为 0，永远"新鲜"），跳过一次没有意义；其余四个都值得。
     */
    fun refreshAll() {
        viewModelScope.launch {
            InstanceKind.entries
                .filter { it != InstanceKind.APP_HOST }
                .forEach { kind ->
                    val keys = registry.ofKind(kind).map { it.id.serial }
                    _busy.update { it + keys }
                    try {
                        registry.refreshKind(kind, force = true)
                    } finally {
                        _busy.update { it - keys.toSet() }
                    }
                }
        }
    }

    fun runAction(id: InstanceId, actionId: String) {
        viewModelScope.launch { registry.invoke(id, actionId) }
    }
}
