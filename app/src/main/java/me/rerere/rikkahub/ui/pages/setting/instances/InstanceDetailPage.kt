package me.rerere.rikkahub.ui.pages.setting.instances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.runtime.InstanceId
import me.rerere.rikkahub.data.runtime.InstanceKind
import me.rerere.rikkahub.data.runtime.InstanceRegistry
import me.rerere.rikkahub.data.runtime.InstanceState
import me.rerere.rikkahub.data.runtime.RuntimeInstance
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * 一个实例的详情：状态、修复动作、它支撑哪些工具、以及通往该后端详细配置的入口。
 *
 * 这里刻意**不**复制各后端的配置表单（Termux 的 6 个旋钮、SSH 的凭据、rootfs 安装面板）：
 * 那些页面已经写好且各有状态机，P5 才把它们作为 section 搬进来。本页先解决"看见 + 修一下"，
 * 避免为了统一外观把五套成熟逻辑重写一遍。
 */
@Composable
fun InstanceDetailPage(kindName: String, key: String) {
    val registry: InstanceRegistry = koinInject()
    val navController = LocalNavController.current
    val instances by registry.instances.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }

    val kind = remember(kindName) { runCatching { InstanceKind.valueOf(kindName) }.getOrNull() }
    val id = kind?.let { InstanceId(it, key) }
    val instance = id?.let { target -> instances.firstOrNull { it.id == target } }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(instance?.displayName ?: kind?.label ?: kindName) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (instance == null || id == null || kind == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.instances_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        val actions = registry.actions(id)
        val tools = registry.backedToolNames(kind)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") {
                StatusCard(
                    instance = instance,
                    checking = checking,
                    onRecheck = {
                        if (!checking) {
                            checking = true
                            scope.launch {
                                try {
                                    registry.refresh(id, force = true)
                                } finally {
                                    checking = false
                                }
                            }
                        }
                    },
                )
            }

            if (actions.isNotEmpty()) {
                item(key = "actions-title") { SectionTitle(stringResource(R.string.instances_section_actions)) }
                items(actions, key = { it.id }) { action ->
                    OutlinedButton(
                        onClick = { scope.launch { registry.invoke(id, action.id) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(action.label)
                    }
                }
            }

            item(key = "settings") {
                FilledTonalButton(
                    onClick = { destinationFor(kind, key)?.let { target -> navController.navigate(target) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.instances_open_settings))
                }
            }

            if (tools.isNotEmpty()) {
                item(key = "tools-title") { SectionTitle(stringResource(R.string.instances_section_tools)) }
                item(key = "tools") {
                    Text(
                        text = tools.sorted().joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "serial") {
                Text(
                    text = id.serial,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    instance: RuntimeInstance,
    checking: Boolean,
    onRecheck: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = instance.id.kind.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                StatusLabel(instance.state)
            }

            Text(text = stateSentence(instance.state), style = MaterialTheme.typography.bodyMedium)

            stateRecovery(instance.state)?.let { recovery ->
                Text(
                    text = recovery,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = instance.lastProbeAt
                    ?.let { stringResource(R.string.instances_last_checked, formatProbeTime(it)) }
                    ?: stringResource(R.string.instances_probe_never),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onRecheck, enabled = !checking) {
                    Text(stringResource(R.string.instances_action_recheck))
                }
                if (checking) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun stateSentence(state: InstanceState): String = when (state) {
    is InstanceState.Ready -> "就绪：现在可以直接使用。"
    is InstanceState.NeedsSetup -> state.why
    is InstanceState.Busy -> state.label
    is InstanceState.Unavailable -> state.why
}

private fun stateRecovery(state: InstanceState): String? = when (state) {
    is InstanceState.NeedsSetup -> null
    is InstanceState.Unavailable -> state.recovery
    else -> null
}

private fun destinationFor(kind: InstanceKind, key: String): Screen? = when (kind) {
    InstanceKind.APP_HOST -> Screen.SettingPermissions
    InstanceKind.SHIZUKU -> Screen.SettingShizuku
    InstanceKind.TERMUX -> Screen.SettingTermux
    InstanceKind.SSH -> Screen.SettingSsh
    InstanceKind.WORKSPACE -> Screen.WorkspaceDetail(key)
}

private fun formatProbeTime(epochMs: Long): String {
    val format = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return format.format(java.util.Date(epochMs))
}
