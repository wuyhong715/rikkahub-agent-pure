package me.rerere.rikkahub.ui.pages.search

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.vector.ConversationSearchResult
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SearchPage(vm: SearchVM = koinViewModel()) {
    val navController = LocalNavController.current
    val focusRequester = remember { FocusRequester() }
    var showRebuildDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    if (showRebuildDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildDialog = false },
            title = { Text(stringResource(R.string.search_page_rebuild_index)) },
            text = { Text(stringResource(R.string.search_page_rebuild_index_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebuildDialog = false
                        vm.rebuildIndex()
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(stringResource(R.string.search_page_title)) },
                actions = {
                    IconButton(
                        onClick = { showRebuildDialog = true },
                        enabled = !vm.isRebuilding,
                    ) {
                        Icon(
                            HugeIcons.Refresh01,
                            contentDescription = stringResource(R.string.search_page_rebuild_button)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            OutlinedTextField(
                value = vm.searchQuery,
                onValueChange = { vm.onQueryChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.search_page_placeholder)) },
                shape = RoundedCornerShape(50),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { vm.search() }
                ),
            )

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
            ) {
                MessageSearchScope.entries.forEachIndexed { index, scope ->
                    SegmentedButton(
                        selected = vm.searchScope == scope,
                        onClick = { vm.onScopeChange(scope) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = MessageSearchScope.entries.size,
                        ),
                    ) {
                        Text(
                            stringResource(
                                when (scope) {
                                    MessageSearchScope.CURRENT_ASSISTANT -> R.string.search_page_scope_current_assistant
                                    MessageSearchScope.ALL_ASSISTANTS -> R.string.search_page_scope_all_assistants
                                }
                            )
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (vm.isLoading || vm.isRebuilding) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                when {
                    vm.isRebuilding -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.search_page_rebuilding_simple),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    vm.searchQuery.isBlank() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.search_page_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    vm.needsEmbeddingModel -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.search_page_needs_model),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    vm.results.isEmpty() && !vm.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.search_page_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(vm.results) { result ->
                                SearchResultItem(
                                    result = result,
                                    onClick = {
                                        navigateToChatPage(
                                            navController,
                                            chatId = Uuid.parse(result.conversationId),
                                            // Empty when the message no longer sits in any
                                            // node: open the conversation instead of refusing
                                            // the tap.
                                            nodeId = runCatching { Uuid.parse(result.nodeId) }
                                                .getOrNull(),
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    result: ConversationSearchResult,
    onClick: () -> Unit,
) {
    val untitled = stringResource(R.string.search_page_untitled)
    // No highlighting: no word matched. The passage *is* the answer here, and marking up
    // substrings of it would be inventing evidence for a match that was never made.
    val passage = remember(result.text) { result.text.trim().take(SEARCH_SNIPPET_CHARS) }

    Surface(
        onClick = onClick,
        color = CustomColors.listItemColors.containerColor,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = result.title.ifBlank { untitled },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = passage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = result.date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * How much of a hit's message a result shows. A message in the index can be ten thousand
 * characters long, and a list of twenty of those is not a list.
 */
private const val SEARCH_SNIPPET_CHARS = 400
