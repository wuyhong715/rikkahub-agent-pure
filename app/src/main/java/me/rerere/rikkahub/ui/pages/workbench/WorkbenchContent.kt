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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Rocket01
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        val reviewTarget = reviewMissions.firstOrNull()
        val openReview: (() -> Unit)? = reviewTarget?.let { { onNavigateDetail(it.id) } }

        if (activeMissions.isNotEmpty() || reviewMissions.isNotEmpty() || doneToday > 0) {
            item(key = "stats") {
                StatStrip(
                    active = activeMissions.size,
                    review = reviewMissions.size,
                    doneToday = doneToday,
                    onOpenReview = openReview,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }

        if (activeSubAgents.isNotEmpty()) {
            item(key = "subagents-header") {
                SectionHeader(
                    title = stringResource(R.string.workbench_active_subagents),
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                    topGap = Spacing.sectionGap,
                )
            }
            item(key = "active-subagents") {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    activeSubAgents.forEach { run -> SubAgentCard(run = run) }
                }
            }
        }

        if (missions.isEmpty()) {
            item(key = "empty") {
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
                    Text(text = stringResource(R.string.workbench_empty_title), style = MaterialTheme.typography.titleMedium)
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
            }
        } else {
            if (activeMissions.isNotEmpty()) {
                item(key = "active-header") {
                    SectionHeader(
                        title = stringResource(R.string.workbench_section_active),
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                        topGap = Spacing.sectionGap,
                    )
                }
                items(activeMissions, key = { "active:${it.id}" }) { mission ->
                    MissionCard(
                        mission = mission,
                        onClick = { onNavigateDetail(mission.id) },
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
            }
            if (reviewMissions.isNotEmpty()) {
                item(key = "review-header") {
                    SectionHeader(
                        title = stringResource(R.string.workbench_section_review),
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                        topGap = Spacing.sectionGap,
                    )
                }
                items(reviewMissions, key = { "review:${it.id}" }) { mission ->
                    MissionCard(
                        mission = mission,
                        onClick = { onNavigateDetail(mission.id) },
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
            }
            if (recentDone.isNotEmpty()) {
                item(key = "done-header") {
                    SectionHeader(
                        title = stringResource(R.string.workbench_section_recent_done),
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                        topGap = Spacing.sectionGap,
                    )
                }
                items(recentDone, key = { "done:${it.id}" }) { mission ->
                    MissionCard(
                        mission = mission,
                        onClick = { onNavigateDetail(mission.id) },
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
            }
        }

        item(key = "quick-entries") {
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
}

@Composable
private fun SubAgentCard(run: SubAgentRun) {
    val navController = LocalNavController.current
    val conversationId = run.conversationId
    val canOpen = !conversationId.isNullOrBlank()
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = run.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        if (run.status == SubAgentStatus.RUNNING) R.string.workbench_subagent_running
                        else R.string.workbench_subagent_pending,
                    ) + " · " + relativeTime(run.startedAtMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canOpen) {
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = stringResource(R.string.workbench_subagent_open),
                )
            }
        }
    }
    if (canOpen) {
        Surface(
            onClick = { navController.navigate(Screen.Chat(conversationId!!)) },
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
            content = content,
        )
    } else {
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
            content = content,
        )
    }
}
