package me.rerere.rikkahub.ui.pages.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.CancelCircle
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.mission.Artifact
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionExecutionState
import me.rerere.rikkahub.data.mission.MissionSettlement
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.data.mission.acceptanceChecked
import me.rerere.rikkahub.data.mission.acceptanceCriteria
import me.rerere.rikkahub.data.mission.executionStateEnum
import me.rerere.rikkahub.data.mission.statusEnum
import me.rerere.rikkahub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkahub.ui.components.richtext.DiffRemovedColor
import me.rerere.rikkahub.ui.theme.GroupCard
import me.rerere.rikkahub.ui.theme.Hairline
import me.rerere.rikkahub.ui.theme.Spacing

@Composable
fun missionStatusLabel(status: MissionStatus): String = stringResource(
    when (status) {
        MissionStatus.ACTIVE -> R.string.mission_status_active
        MissionStatus.BLOCKED -> R.string.mission_status_blocked
        MissionStatus.REVIEW -> R.string.mission_status_review
        MissionStatus.DONE -> R.string.mission_status_done
        MissionStatus.ARCHIVED -> R.string.mission_status_archived
    }
)

/**
 * 语气 → 颜色对（容器色 / 其上文字色）。药丸用这一对。
 *
 * 注意这里**不再按 MissionStatus 直接分支**：分支已经在 [statusTone] 里做过一次，
 * 这里只负责“语气长什么样”。状态增删时只改一处，不会出现两个状态共用一个颜色。
 */
@Composable
internal fun missionStatusColors(status: MissionStatus): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (statusTone(status)) {
        MissionStatusTone.PROGRESS -> scheme.primaryContainer to scheme.onPrimaryContainer
        MissionStatusTone.ATTENTION -> scheme.errorContainer to scheme.onErrorContainer
        MissionStatusTone.REVIEW -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        MissionStatusTone.COMPLETE -> scheme.secondaryContainer to scheme.onSecondaryContainer
        MissionStatusTone.MUTED -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
}

/**
 * 实心强调色：给左侧色条和进度条。
 *
 * 容器色画细线太淡（尤其浅色主题），而实心色是这套主题里本来就有的“饱和档”：
 * primary / error / tertiary / secondary / outline，跟着动态取色一起变，不硬编码。
 */
@Composable
internal fun missionStatusAccent(status: MissionStatus): Color {
    val scheme = MaterialTheme.colorScheme
    return when (statusTone(status)) {
        MissionStatusTone.PROGRESS -> scheme.primary
        MissionStatusTone.ATTENTION -> scheme.error
        MissionStatusTone.REVIEW -> scheme.tertiary
        MissionStatusTone.COMPLETE -> scheme.secondary
        MissionStatusTone.MUTED -> scheme.outline
    }
}

@Composable
fun StatusPill(status: MissionStatus, modifier: Modifier = Modifier) {
    val (container, content) = missionStatusColors(status)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
    ) {
        Text(
            text = missionStatusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun MissionCard(
    mission: MissionEntity,
    subAgentCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = missionStatusAccent(mission.statusEnum)
    val criteria = mission.acceptanceCriteria
    val checked = mission.acceptanceChecked
    val progress = MissionProgress.fraction(checked.size, criteria.size)
    GroupCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Column(
                modifier = Modifier.padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = mission.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    when (mission.executionStateEnum) {
                        MissionExecutionState.RUNNING -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                                Text(
                                    text = stringResource(R.string.mission_exec_running),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        MissionExecutionState.WAITING_APPROVAL -> {
                            Text(
                                text = stringResource(R.string.mission_exec_waiting_approval),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        else -> {}
                    }
                    StatusPill(status = mission.statusEnum)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (criteria.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.mission_acceptance_progress,
                                checked.size,
                                criteria.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (mission.artifactCount > 0) {
                        Text(
                            text = stringResource(R.string.mission_artifacts_count, mission.artifactCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = relativeTime(mission.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (progress != null) {
                    // 长任务最想知道“走到哪了”。0/6 只是数字，配一条细进度线扫一眼就能读懂。
                    // 颜色用实心 accent 而不是容器色：浅色主题下后者几乎和轨道同亮度，等于没画。
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = accent,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }
            }
        }
    }
}

@Composable
fun StatBlock(
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 工作台顶部的状态摘要 —— 三个数字，一条。
 *
 * 之前是三块 `headlineMedium`（28sp）大数字撑满一整张 Hero 卡（约 92dp 高），而它回答的
 * 只是“几条在跑、几条等验收、今天完成几条”。28sp 的体量让这块摘要抢走了任务列表的注意力，
 * 也把“真正要做的事”挤到了首屏之外；同时三个数字分别染成 primary/secondary/tertiary，
 * 像一排装饰性霓虹，和任务卡片上的状态色语义打架。
 *
 * 现在：16sp 的数字 + 11sp 的说明，三格横排，用极淡的竖线分隔，整条约 60dp。
 * 数字仍带语气色（和任务卡片的 accent 同源），但体量退到“信息”而不是“海报”。
 *
 * @param onOpenReview 待验收条数 > 0 时传跳转；为 null 时这一格不可点，说明文字也回到中性灰，
 *   不会出现“看起来能点、点下去没反应”的假可点。
 */
@Composable
fun StatStrip(
    active: Int,
    review: Int,
    doneToday: Int,
    onOpenReview: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    GroupCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell(
                value = active.toString(),
                label = stringResource(R.string.workbench_stat_active),
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            StatCellDivider()
            StatCell(
                value = review.toString(),
                label = stringResource(R.string.workbench_stat_review),
                accent = MaterialTheme.colorScheme.tertiary,
                onClick = onOpenReview,
                modifier = Modifier.weight(1f),
            )
            StatCellDivider()
            StatCell(
                value = doneToday.toString(),
                label = stringResource(R.string.workbench_stat_done_today),
                accent = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatCellDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = Hairline.DIVIDER_ALPHA)
            )
    )
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = accent,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            // 只有真能点的时候才把说明文字染成强调色，否则一律中性灰。
            color = if (onClick != null) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun relativeTime(epochMs: Long): String {
    val diffMinutes = (System.currentTimeMillis() - epochMs).coerceAtLeast(0) / 60_000
    return when {
        diffMinutes < 1 -> stringResource(R.string.mission_time_just_now)
        diffMinutes < 60 -> stringResource(R.string.mission_time_minutes_ago, diffMinutes)
        diffMinutes < 60 * 24 -> stringResource(R.string.mission_time_hours_ago, diffMinutes / 60)
        else -> stringResource(R.string.mission_time_days_ago, diffMinutes / (60 * 24))
    }
}

// ---------------------------------------------------------------------------
// Batch 1 — run settlement / artifact library / change review
// ---------------------------------------------------------------------------

/** Line delta colours: reuse the shared diff palette so artifact rows match the chat diff view. */
private val ArtifactCreatedColor = DiffAddedColor
private val ArtifactRemovedColor = DiffRemovedColor
private val ArtifactModifiedColor = Color(0xFF2196F3)

/** Human-readable active duration: "<1m", "12m", "2h 5m". */
fun formatDuration(durationMs: Long): String {
    val totalMinutes = durationMs.coerceAtLeast(0) / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        totalMinutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}

/**
 * Run settlement card — the aggregate of a mission run: tool calls, artifacts, spend,
 * tokens, active duration and the acceptance gate. Pure projection of
 * [MissionSettlement]; the card renders nothing but numbers, so it stays cheap to
 * recompose on every event.
 */
@Composable
fun SettlementCard(settlement: MissionSettlement, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.mission_settlement_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(status = settlement.mission.statusEnum)
            }

            // Stats grid — 3 columns × 2 rows.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatBlock(
                    value = settlement.toolCallCount.toString(),
                    label = stringResource(R.string.mission_settlement_tool_calls),
                    color = scheme.primary,
                    modifier = Modifier.weight(1f),
                )
                StatBlock(
                    value = settlement.artifactCount.toString(),
                    label = stringResource(R.string.mission_settlement_artifacts),
                    color = scheme.secondary,
                    modifier = Modifier.weight(1f),
                )
                StatBlock(
                    value = String.format("$%.2f", settlement.totalCostUsd),
                    label = stringResource(R.string.mission_settlement_cost),
                    color = scheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatBlock(
                    value = String.format("%,d", settlement.totalTokens),
                    label = stringResource(R.string.mission_settlement_tokens),
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatBlock(
                    value = formatDuration(settlement.activeDurationMs),
                    label = stringResource(R.string.mission_settlement_duration),
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatBlock(
                    value = "${settlement.acceptanceChecked}/${settlement.acceptanceTotal}",
                    label = stringResource(R.string.mission_settlement_acceptance_progress),
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            LinearProgressIndicator(
                progress = { settlement.acceptanceProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (settlement.lastActivityAt == null) {
                    stringResource(R.string.mission_settlement_no_data)
                } else {
                    stringResource(
                        R.string.mission_updated_at,
                        relativeTime(settlement.lastActivityAt!!),
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ArtifactChangeBadge(changeType: String?) {
    val isCreated = changeType == "created"
    val isModified = changeType == "modified"
    if (isCreated || isModified) {
        val accent = if (isCreated) ArtifactCreatedColor else ArtifactModifiedColor
        Surface(
            shape = RoundedCornerShape(50),
            color = accent.copy(alpha = 0.15f),
            contentColor = accent,
        ) {
            Text(
                text = stringResource(
                    if (isCreated) R.string.mission_artifact_created else R.string.mission_artifact_modified
                ),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * One row in the artifact library: file name, change badge, line delta, and an
 * expandable excerpt of the new content. Tapping the row toggles the excerpt; the
 * trailing buttons open / share the file through callbacks supplied by the page so
 * this component stays free of storage concerns.
 */
@Composable
fun ArtifactRow(
    artifact: Artifact,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val fileName = artifact.path.substringAfterLast('/').ifBlank { artifact.path }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.File01,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ArtifactChangeBadge(changeType = artifact.changeType)
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (expanded) 180f else 0f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (artifact.linesAdded > 0 || artifact.linesRemoved > 0) {
                    val lines = stringResource(
                        R.string.mission_artifact_lines,
                        artifact.linesAdded,
                        artifact.linesRemoved,
                    )
                    val splitAt = lines.indexOf('-')
                    Text(
                        text = if (splitAt > 0) {
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = ArtifactCreatedColor)) {
                                    append(lines.substring(0, splitAt))
                                }
                                withStyle(SpanStyle(color = ArtifactRemovedColor)) {
                                    append(lines.substring(splitAt))
                                }
                            }
                        } else {
                            buildAnnotatedString { append(lines) }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
                Text(
                    text = listOfNotNull(artifact.sourceTool, relativeTime(artifact.createdAt))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onOpen(artifact.path) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Eye,
                        contentDescription = stringResource(R.string.mission_artifact_open),
                        tint = scheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = { onShare(artifact.path) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Share08,
                        contentDescription = stringResource(R.string.mission_artifact_share),
                        tint = scheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (expanded) {
                Text(
                    text = stringResource(R.string.mission_artifact_diff),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                val excerpt = artifact.diffExcerpt
                if (excerpt.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.mission_settlement_no_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = excerpt,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .background(scheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Review gate. Approve is only enabled once every acceptance criterion is checked —
 * the same condition as [MissionSettlement.canComplete] — otherwise the panel explains
 * why it is disabled.
 */
@Composable
fun ReviewPanel(
    settlement: MissionSettlement,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.CheckmarkCircle01,
                    contentDescription = null,
                    tint = scheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.mission_review_panel_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${settlement.acceptanceChecked}/${settlement.acceptanceTotal}",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }

            LinearProgressIndicator(
                progress = { settlement.acceptanceProgress },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onApprove,
                    enabled = settlement.canComplete,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = HugeIcons.CheckmarkCircle01,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.mission_review_approve),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = onReject,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = HugeIcons.CancelCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.mission_review_reject),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = onArchive,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = HugeIcons.Folder01,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.mission_review_archive),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!settlement.canComplete) {
                Text(
                    text = stringResource(R.string.mission_review_approve_disabled_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.error,
                )
            }
        }
    }
}
