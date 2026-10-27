package me.rerere.rikkahub.ui.pages.workbench

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.subagent.SubAgentRun
import me.rerere.rikkahub.subagent.SubAgentStatus
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.SectionHeader
import me.rerere.rikkahub.ui.theme.Spacing

@Composable
fun WorkbenchContent(
    vm: WorkbenchVM,
    onNavigateDetail: (String) -> Unit,
    onCreateMission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = LocalNavController.current
    val missions by vm.missions.collectAsStateWithLifecycle()
    val activeMissions by vm.activeMissions.collectAsStateWithLifecycle()
    val reviewMissions by vm.reviewMissions.collectAsStateWithLifecycle()
    val recentDone by vm.recentDoneMissions.collectAsStateWithLifecycle()
    val doneToday by vm.doneToday.collectAsStateWithLifecycle()
    val activeSubAgents by vm.activeSubAgents.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // ② 状态摘要：一行三格，不再用大号统计卡占掉首屏
        val reviewTarget = reviewMissions.firstOrNull()
        val openReview: (() -> Unit)? = if (reviewTarget != null) {
            { onNavigateDetail(reviewTarget.id) }
        } else {
            null
        }
        // 一条任务都没有时不要摆一排 0：空态本身就够说明问题了，三个 0 只是噪音。
        if (activeMissions.isNotEmpty() || reviewMissions.isNotEmpty() || doneToday > 0) {
            StatStrip(
                active = activeMissions.size,
                review = reviewMissions.size,
                doneToday = doneToday,
                onOpenReview = openReview,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }

        // ③ Active sub-agents
        if (activeSubAgents.isNotEmpty()) {
            SectionHeader(
                title = stringResource(R.string.workbench_active_subagents),
                modifier = Modifier.padding(horizontal = Spacing.lg),
                topGap = Spacing.sectionGap,
            )
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                activeSubAgents.forEach { run ->
                    SubAgentCard(run = run)
                }
            }
        }

        // ④ Mission groups / ⑤ empty state
        if (missions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    imageVector = HugeIcons.Rocket01,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = stringResource(R.string.workbench_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workbench_empty_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                FilledTonalButton(onClick = onCreateMission) {
                    Text(text = stringResource(R.string.workbench_new_mission))
                }
            }
        } else {
            if (activeMissions.isNotEmpty()) {
                SectionHeader(
                    title = stringResource(R.string.workbench_section_active),
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                    topGap = Spacing.sectionGap,
                )
                activeMissions.forEach { mission ->
                    MissionCard(
                        mission = mission,
                        onClick = { onNavigateDetail(mission.id) },
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
            }
            if (reviewMissions.isNotEmpty()) {
                SectionHeader(
                    title = stringResource(R.string.workbench_section_review),
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                    topGap = Spacing.sectionGap,
                )
                reviewMissions.forEach { mission ->
                    MissionCard(
                        mission = mission,
                        onClick = { onNavigateDetail(mission.id) },
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
            }
            if (recentDone.isNotEmpty()) {
                SectionHeader(
                    title = stringResource(R.string.workbench_section_recent_done),
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                    topGap = Spacing.sectionGap,
                )
                recentDone.forEach { mission ->
                    MissionCard(
                        mission = mission,
                        onClick = { onNavigateDetail(mission.id) },
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
            }
        }

        // ⑥ Quick entries
        CardGroup(
            modifier = Modifier.padding(horizontal = Spacing.lg),
            title = { Text(text = stringResource(R.string.workbench_quick_title)) },
        ) {
            item(
                onClick = { navController.navigate(Screen.Stats) },
                leadingContent = { Icon(HugeIcons.ChartColumn, contentDescription = null) },
                headlineContent = { Text(text = stringResource(R.string.workbench_quick_stats)) },
            )
        }
    }
}

@Composable
private fun SubAgentCard(run: SubAgentRun) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = run.label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    if (run.status == SubAgentStatus.RUNNING) {
                        R.string.workbench_subagent_running
                    } else {
                        R.string.workbench_subagent_pending
                    }
                ) + " · " + relativeTime(run.startedAtMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
