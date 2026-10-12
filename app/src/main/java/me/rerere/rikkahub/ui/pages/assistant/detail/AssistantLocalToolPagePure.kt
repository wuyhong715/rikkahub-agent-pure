package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agentdef.LocalToolGroups
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.pages.setting.localToolTitleRes
import org.koin.compose.koinInject

/**
 * pure — the local-tool page as a two-level tree: **groups**, and the tools inside a group.
 *
 * The Moxw line cannot offer the first level (see [MoxwLocalToolContent]): every group feeds one
 * embedding-ranked catalogue there, so leaving a group out never hid anything from the model. The
 * pure line has no such catalogue — a turn receives exactly the schemas its groups produce — so the
 * group switch is the switch, and it has to be here.
 *
 * The two levels are therefore joined rather than listed apart, which is what the old page did
 * (fifty group switches, then a flat list of every tool name below them, with no indication of
 * which group a name came from):
 *
 *  - the **group switch** decides whether the group's schemas reach the model at all;
 *  - **opening** an enabled group reveals its tools, each with its own switch, so one tool inside a
 *    group you are keeping can be dropped without losing the rest.
 *
 * Tool names come from the live [LocalTools] factory, never from a typed-in list, so a row here
 * cannot drift from the surface the model actually receives.
 */
@Composable
internal fun PureLocalToolContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
) {
    val ctx = LocalContext.current
    val localToolFactory = koinInject<LocalTools>()

    val groupTitles: Map<LocalToolOption, String> = remember(ctx) {
        LocalToolGroups.all.associateWith { ctx.getString(localToolTitleRes(it)) }
    }

    // The live factory is the only authority on what a group actually produces — a name typed into
    // this page could drift from the surface the model receives. Computed once per page visit: the
    // inventory does not depend on anything the user changes here.
    val groupTools: Map<LocalToolOption, List<String>> = remember(localToolFactory) {
        LocalToolGroups.all.associateWith { option ->
            localToolFactory.getTools(listOf(option))
                .map { it.name }
                .filterNot { it in LocalTools.HIDDEN_TOOL_NAMES }
        }
    }

    val enabledGroups: Set<LocalToolOption> = remember(assistant.localTools) {
        assistant.localTools.toSet()
    }
    val disabledTools: Set<String> = assistant.disabledLocalTools

    var query by remember { mutableStateOf("") }
    var openedGroups by remember { mutableStateOf(emptySet<LocalToolOption>()) }

    fun setGroupEnabled(option: LocalToolOption, enabled: Boolean) {
        onUpdateAssistant { current ->
            current.copy(
                localTools = if (enabled) {
                    (current.localTools + option).distinct()
                } else {
                    current.localTools - option
                },
            )
        }
    }

    fun setToolEnabled(toolName: String, enabled: Boolean) {
        onUpdateAssistant { current ->
            current.copy(
                disabledLocalTools = if (enabled) {
                    current.disabledLocalTools - toolName
                } else {
                    current.disabledLocalTools + toolName
                },
            )
        }
    }

    fun toggleOpen(option: LocalToolOption) {
        openedGroups = if (option in openedGroups) openedGroups - option else openedGroups + option
    }

    val needle = query.trim()
    val searching = needle.isNotEmpty()
    val visibleGroups: List<LocalToolOption> = remember(needle, groupTitles, groupTools) {
        if (!searching) {
            LocalToolGroups.all
        } else {
            LocalToolGroups.all.filter { option ->
                groupTitles[option].orEmpty().contains(needle, ignoreCase = true) ||
                    groupTools[option].orEmpty().any { it.contains(needle, ignoreCase = true) }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.assistant_page_local_tools_pure_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.assistant_page_local_tools_search_hint)) },
            shape = RoundedCornerShape(50),
            singleLine = true,
        )

        if (visibleGroups.isEmpty()) {
            Text(
                text = stringResource(R.string.assistant_page_local_tools_no_match),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        } else CardGroup {
            visibleGroups.forEach { option ->
                val enabled = option in enabledGroups
                val tools = groupTools[option].orEmpty()
                val offCount = tools.count { it in disabledTools }
                // A search opens what it found; otherwise the user's own taps decide.
                val opened = enabled && (searching || option in openedGroups)

                item(
                    onClick = { if (enabled) toggleOpen(option) },
                    headlineContent = { Text(groupTitles[option].orEmpty()) },
                    supportingContent = {
                        Text(
                            if (offCount > 0) {
                                stringResource(
                                    R.string.assistant_page_local_tools_group_count_off,
                                    tools.size,
                                    offCount,
                                )
                            } else {
                                stringResource(R.string.assistant_page_local_tools_group_count, tools.size)
                            }
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (enabled) {
                                IconButton(onClick = { toggleOpen(option) }) {
                                    Icon(
                                        imageVector = if (opened) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                                        contentDescription = stringResource(
                                            if (opened) {
                                                R.string.assistant_page_local_tools_collapse
                                            } else {
                                                R.string.assistant_page_local_tools_expand
                                            }
                                        ),
                                    )
                                }
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = { setGroupEnabled(option, it) },
                            )
                        }
                    },
                )

                if (opened) {
                    tools.forEach { toolName ->
                        item(
                            modifier = Modifier.padding(start = 24.dp),
                            headlineContent = {
                                Text(
                                    text = toolName,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = stringResource(
                                        if (toolName in disabledTools) {
                                            R.string.assistant_page_local_tools_tool_off
                                        } else {
                                            R.string.assistant_page_local_tools_tool_on
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = toolName !in disabledTools,
                                    onCheckedChange = { setToolEnabled(toolName, it) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
