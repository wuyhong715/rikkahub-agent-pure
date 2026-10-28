package me.rerere.rikkahub.ui.pages.workbench

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * The workbench, as a full-screen destination.
 *
 * It used to be a right-side drawer opened by an edge swipe. That swipe was dispatched on top of
 * the conversation drawer's own drag detector, which consumes every horizontal drag in the main
 * pass, so the opener kept losing the finger to a drawer that was already closed and could not
 * move. Rather than keep fighting the gesture layer, the workbench now owns the whole screen and
 * the rocket button in the chat's top bar is the single way in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchPage(currentConversationId: String? = null) {
    val vm: WorkbenchVM = koinViewModel()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val settingsStore: SettingsStore = koinInject()
    val workspaceRepository: WorkspaceRepository = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val workspaces by workspaceRepository.listFlow().collectAsStateWithLifecycle(emptyList())
    val launchError by vm.launchError.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    val launchFailedLabel = stringResource(R.string.mission_launch_failed)

    LaunchedEffect(launchError) {
        val err = launchError
        if (err != null) {
            toaster.show("$launchFailedLabel: $err")
            vm.clearLaunchError()
        }
    }

    // Seed the default workflow library the first time the create sheet opens; the sheet's
    // picker reads from the same workspace, so this must happen before it composes its list.
    LaunchedEffect(showSheet) {
        if (showSheet) vm.prepareWorkflowLibrary()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.workbench_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.MissionWorkflows) }) {
                        Icon(
                            imageVector = HugeIcons.BookOpen01,
                            contentDescription = stringResource(R.string.mission_workflows_title),
                        )
                    }
                    IconButton(onClick = { showSheet = true }) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = stringResource(R.string.workbench_new_mission),
                        )
                    }
                },
            )
        },
    ) { padding ->
        WorkbenchContent(
            vm = vm,
            onNavigateDetail = { missionId ->
                navController.navigate(Screen.MissionDetail(missionId))
            },
            onCreateMission = { showSheet = true },
            modifier = Modifier.padding(padding),
        )
    }

    if (showSheet) {
        CreateMissionSheet(
            settings = settings,
            workspaces = workspaces,
            onDismiss = { showSheet = false },
            // 从会话里打开的：「开始任务」的含义就是「以这个对话为来源」。从抽屉打开时为 null。
            sourceConversationId = currentConversationId,
            onCreate = { draft -> vm.createMission(draft, currentConversationId) },
            loadWorkflows = { id -> vm.listWorkflowDocs(id) },
        )
    }
}