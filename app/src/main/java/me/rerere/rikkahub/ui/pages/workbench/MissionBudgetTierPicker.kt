package me.rerere.rikkahub.ui.pages.workbench

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.mission.MissionBudgetTiers
import me.rerere.rikkahub.data.mission.MissionLimits

/**
 * 批 4 — 预算档位选择行（新建任务与运行限额对话框共用）。
 *
 * `selected == null` 显示为「自定义」：只要四个预算字段不恰好等于某个档位，就是自定义，
 * 不替用户猜。
 */
@Composable
fun MissionBudgetTierPicker(
    selected: MissionBudgetTiers.Tier?,
    onSelect: (MissionBudgetTiers.Tier?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.mission_budget_tier_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MissionBudgetTiers.Tier.entries.forEach { tier ->
                FilterChip(
                    selected = selected == tier,
                    onClick = { onSelect(tier) },
                    label = { Text(text = stringResource(tier.labelRes())) },
                )
            }
            FilterChip(
                selected = selected == null,
                // 自定义不是「动作」，只是让用户知道现在处于自定义态；点它不动输入。
                onClick = { if (selected != null) onSelect(null) },
                label = { Text(text = stringResource(R.string.mission_budget_tier_custom)) },
            )
        }
    }
}

/** 档位选择下方的派生结论，如「单轮 60 分钟 · 最多续跑 12 轮 · 预算 3,000,000 token · 收尾 10 分钟」。 */
@Composable
fun MissionBudgetSummary(
    limits: MissionLimits,
    modifier: Modifier = Modifier,
) {
    val summary = MissionBudgetTiers.summary(limits)
    Text(
        text = stringResource(
            R.string.mission_budget_summary,
            summary.roundTimeoutMinutes,
            summary.maxResumeRounds,
            MissionBudgetTiers.formatTokenBudget(summary.tokenBudget),
            summary.wrapUpMinutes,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** 档位名（chip 文案）。 */
fun MissionBudgetTiers.Tier.labelRes(): Int = when (this) {
    MissionBudgetTiers.Tier.FAST -> R.string.mission_budget_tier_fast
    MissionBudgetTiers.Tier.STANDARD -> R.string.mission_budget_tier_standard
    MissionBudgetTiers.Tier.LONG -> R.string.mission_budget_tier_long
}
