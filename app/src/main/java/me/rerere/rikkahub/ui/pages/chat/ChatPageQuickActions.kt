package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Play
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

private data class ChatQuickAction(
    val labelRes: Int,
    val promptRes: Int,
)

private val chatQuickActions = listOf(
    ChatQuickAction(R.string.chat_quick_summarize, R.string.chat_quick_summarize_prompt),
    ChatQuickAction(R.string.chat_quick_plan, R.string.chat_quick_plan_prompt),
    ChatQuickAction(R.string.chat_quick_analyze, R.string.chat_quick_analyze_prompt),
    ChatQuickAction(R.string.chat_quick_continue, R.string.chat_quick_continue_prompt),
)

@Composable
internal fun ChatPageQuickActions(
    loading: Boolean,
    processingStatus: List<String>,
    subAgentCount: Int,
    onStop: () -> Unit,
    showQuickStart: Boolean,
    onPromptSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!loading && !showQuickStart) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Clock02,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = processingStatus.joinToString(" · ").ifBlank {
                                stringResource(R.string.chat_quick_working)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                        if (subAgentCount > 0) {
                            Text(
                                text = stringResource(R.string.chat_quick_subagents_running, subAgentCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    LinearProgressIndicator(modifier = Modifier.width(42.dp))
                    IconButton(onClick = onStop) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = "Stop",
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = HugeIcons.Play,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.chat_quick_start_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chatQuickActions.forEach { action ->
                        val label = stringResource(action.labelRes)
                        val prompt = stringResource(action.promptRes)
                        AssistChip(
                            onClick = { onPromptSelected(prompt) },
                            label = { Text(label, maxLines = 1) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.CheckmarkCircle01,
                                    contentDescription = null,
                                    modifier = Modifier.width(18.dp),
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        )
                    }
                }
            }
        }
    }
}
