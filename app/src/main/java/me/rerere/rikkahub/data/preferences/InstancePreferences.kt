package me.rerere.rikkahub.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.tools.InstanceFilterMode
import me.rerere.rikkahub.data.ai.tools.InstanceToolFilter
import me.rerere.rikkahub.data.ai.tools.InstanceFilterRuntime

private val Context.instanceDataStore by preferencesDataStore(name = "instance_prefs")

/**
 * 运行实例自己的偏好，只有一个旋钮：工具目录按实例状态裁到什么程度。
 *
 * 单独一个 DataStore 而不是塞进 `Settings`：这张表只服务这个功能，塞进 Settings 会牵动
 * 备份格式、迁移版本和 1200 行的映射段落，换不来任何好处。
 *
 * 构造时**同步播种** [InstanceFilterRuntime]（与 TermuxPreferences 的做法一致）：
 * 工具目录是在非挂起的调用点装配的，等异步流发射就晚了——那一轮就会按编译期默认值裁。
 */
class InstancePreferences(private val context: Context) {

    private val store = context.instanceDataStore
    private val filterModeKey = stringPreferencesKey("filter_mode")

    private val _filterMode = MutableStateFlow(loadInitial())
    val filterMode: StateFlow<InstanceFilterMode> = _filterMode.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            store.data
                .map { prefs -> parse(prefs[filterModeKey]) }
                .distinctUntilChanged()
                .collect { mode ->
                    InstanceFilterRuntime.mode = mode
                    _filterMode.value = mode
                }
        }
    }

    suspend fun setFilterMode(mode: InstanceFilterMode) {
        store.edit { it[filterModeKey] = mode.name }
        InstanceFilterRuntime.mode = mode
        _filterMode.value = mode
    }

    private fun loadInitial(): InstanceFilterMode {
        val stored = runBlocking { runCatching { store.data.first()[filterModeKey] }.getOrNull() }
        val mode = parse(stored)
        InstanceFilterRuntime.mode = mode
        return mode
    }

    /** 存的是枚举名；读到不认识的（降级安装、手改文件）就回到默认档，而不是崩。 */
    private fun parse(raw: String?): InstanceFilterMode =
        raw?.let { name -> runCatching { InstanceFilterMode.valueOf(name) }.getOrNull() }
            ?: InstanceToolFilter.DEFAULT_MODE
}
