package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.AssistantAvatar
import me.rerere.rikkahub.ui.components.ui.MoxwSheet
import me.rerere.rikkahub.ui.components.ui.MoxwSheetItemSpacing
import me.rerere.rikkahub.ui.components.ui.MoxwSheetPadding
import me.rerere.rikkahub.ui.components.ui.MoxwSheetRow
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberAssistantState
import kotlin.uuid.Uuid

@Composable
fun AssistantPicker(
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
    modifier: Modifier = Modifier,
    onClickSetting: () -> Unit,
) {
    val state = rememberAssistantState(settings, onUpdateSettings)
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    var showPicker by remember { mutableStateOf(false) }

    NavigationDrawerItem(
        icon = {
            Icon(HugeIcons.LookTop, contentDescription = null)
        },
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.currentAssistant.name.ifEmpty { defaultAssistantName },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.weight(1f))

                AssistantAvatar(
                    assistant = state.currentAssistant,
                    name = state.currentAssistant.name.ifEmpty { defaultAssistantName },
                    onClick = onClickSetting
                )
            }
        },
        onClick = {
            showPicker = true
        },
        modifier = modifier,
        selected = false,
    )

    if (showPicker) {
        AssistantPickerSheet(
            settings = settings,
            currentAssistant = state.currentAssistant,
            onAssistantSelected = { assistant ->
                showPicker = false
                state.setSelectAssistant(assistant)
            },
            onDismiss = {
                showPicker = false
            }
        )
    }
}

@Composable
private fun AssistantPickerSheet(
    settings: Settings,
    currentAssistant: Assistant,
    onAssistantSelected: (Assistant) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)

    // 标签过滤状态
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }

    // 根据选中的标签过滤助手
    val filteredAssistants = remember(settings.assistants, selectedTagIds) {
        if (selectedTagIds.isEmpty()) {
            settings.assistants
        } else {
            settings.assistants.filter { assistant ->
                assistant.tags.any { tagId -> tagId in selectedTagIds }
            }
        }
    }

    MoxwSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        title = stringResource(R.string.assistant_page_title),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = MoxwSheetPadding),
            verticalArrangement = Arrangement.spacedBy(MoxwSheetItemSpacing)
        ) {

            // 标签过滤器
            if (settings.assistantTags.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(settings.assistantTags, key = { tag -> tag.id }) { tag ->
                        FilterChip(
                            onClick = {
                                selectedTagIds = if (tag.id in selectedTagIds) {
                                    selectedTagIds - tag.id
                                } else {
                                    selectedTagIds + tag.id
                                }
                            },
                            label = { Text(tag.name) },
                            selected = tag.id in selectedTagIds,
                            shape = RoundedCornerShape(50),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 助手列表
            val navController = LocalNavController.current
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MoxwSheetItemSpacing),
            ) {
                items(filteredAssistants, key = { it.id }) { assistant ->
                    val checked = assistant.id == currentAssistant.id
                    MoxwSheetRow(
                        title = assistant.name.ifEmpty { defaultAssistantName },
                        selected = checked,
                        onClick = { onAssistantSelected(assistant) },
                        modifier = Modifier.animateItem(),
                        leading = {
                            AssistantAvatar(
                                assistant = assistant,
                                name = assistant.name.ifEmpty { defaultAssistantName },
                                modifier = Modifier.size(32.dp)
                            )
                        },
                        trailing = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        sheetState.hide()
                                        onDismiss()
                                        navController.navigate(Screen.AssistantDetail(assistant.id.toString()))
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Edit03,
                                    contentDescription = stringResource(R.string.accessibility_edit_assistant)
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
