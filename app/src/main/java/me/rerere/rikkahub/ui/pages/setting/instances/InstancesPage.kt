package me.rerere.rikkahub.ui.pages.setting.instances

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Console
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.runtime.InstanceKind
import me.rerere.rikkahub.data.runtime.InstanceState
import me.rerere.rikkahub.data.runtime.RuntimeInstance
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 设置 → 运行实例。
 *
 * 这一页取代了三处旧入口（权限里的 Shizuku / Termux、连接里的 SSH、扩展里的工作区）：
 * 同一个问题"我的命令跑在哪"以前要记三条路径，现在只有一条。每个后端一行，状态是
 * [InstanceState] 的四态之一，点进去是详情与修复动作。
 *
 * 刻意不在这里做任何探测：列表只读 [me.rerere.rikkahub.data.runtime.InstanceRegistry] 的
 * 缓存，联网/起进程的检查只发生在详情页的「重新检测」或本页顶部的一次性「全部检测」。
 */
@Composable
fun InstancesPage(vm: InstancesVM = koinViewModel()) {
    val navController = LocalNavController.current
    val instances by vm.instances.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.instances_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                actions = {
                    TextButton(onClick = { vm.refreshAll() }) {
                        Text(stringResource(R.string.instances_action_check_all))
                    }
                },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InstanceKind.entries.forEach { kind ->
                val rows = instances.filter { it.id.kind == kind }
                if (rows.isEmpty()) return@forEach

                item(key = "header-${kind.name}") {
                    SectionHeader(text = kind.label)
                }
                items(rows, key = { it.id.serial }) { instance ->
                    InstanceRow(
                        instance = instance,
                        checking = instance.id.serial in busy,
                        onClick = {
                            navController.navigate(
                                Screen.InstanceDetail(instance.id.kind.name, instance.id.key),
                            )
                        },
                    )
                }
            }

            item(key = "header-manage") {
                SectionHeader(text = stringResource(R.string.instances_section_manage))
            }
            item(key = "manage-ssh") {
                ListItem(
                    modifier = Modifier.clickable { navController.navigate(Screen.SettingSsh) },
                    leadingContent = { Icon(HugeIcons.ServerStack01, null) },
                    headlineContent = { Text(stringResource(R.string.instances_manage_ssh)) },
                    supportingContent = { Text(stringResource(R.string.instances_manage_ssh_desc)) },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                )
            }
            item(key = "manage-workspaces") {
                ListItem(
                    modifier = Modifier.clickable { navController.navigate(Screen.Workspaces) },
                    leadingContent = { Icon(HugeIcons.Folder01, null) },
                    headlineContent = { Text(stringResource(R.string.instances_manage_workspaces)) },
                    supportingContent = { Text(stringResource(R.string.instances_manage_workspaces_desc)) },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                )
            }
        }
    }
}

@Composable
private fun InstanceRow(
    instance: RuntimeInstance,
    checking: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(iconFor(instance.id.kind), contentDescription = null) },
        headlineContent = { Text(instance.displayName) },
        supportingContent = { Text(instance.subtitle) },
        trailingContent = {
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
            } else {
                StatusLabel(instance.state)
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    )
}

/** 四态 → 四个词。NeedsSetup 与 Unavailable 用不同颜色：一个"做一步就好"，一个"要查原因"。 */
@Composable
internal fun StatusLabel(state: InstanceState) {
    val (text, color) = when (state) {
        is InstanceState.Ready -> stringResource(R.string.instances_state_ready) to MaterialTheme.colorScheme.primary
        is InstanceState.NeedsSetup -> stringResource(R.string.instances_state_needs_setup) to MaterialTheme.colorScheme.secondary
        is InstanceState.Busy -> state.label to MaterialTheme.colorScheme.secondary
        is InstanceState.Unavailable -> stringResource(R.string.instances_state_unavailable) to MaterialTheme.colorScheme.outline
    }
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
    )
}

private fun iconFor(kind: InstanceKind) = when (kind) {
    InstanceKind.APP_HOST -> HugeIcons.SmartPhone01
    InstanceKind.SHIZUKU -> HugeIcons.Console
    InstanceKind.WORKSPACE -> HugeIcons.Folder01
    InstanceKind.SSH -> HugeIcons.ServerStack01
    InstanceKind.TERMUX -> HugeIcons.ComputerTerminal01
}
