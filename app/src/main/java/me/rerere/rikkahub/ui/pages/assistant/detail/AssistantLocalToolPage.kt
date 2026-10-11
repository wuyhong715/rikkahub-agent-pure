package me.rerere.rikkahub.ui.pages.assistant.detail

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agentdef.LocalToolGroups
import me.rerere.rikkahub.data.ai.tools.LocalToolGates
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.shizuku.ShizukuManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.localToolTitleRes
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import rikka.shizuku.Shizuku

private const val TAG = "AssistantLocalToolPage"

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantLocalToolContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            // Transform-based path used by every control on this page. Each tap runs inside
            // SettingsStore.update's mutex against the genuinely-current Assistant, so rapid
            // taps (especially through a permission-grant round-trip to system Settings) all
            // serialise against the actual current state instead of whatever stale snapshot
            // the recomposition was holding.
            onUpdateAssistant = { transform -> vm.updateAssistant(transform) },
        )
    }
}

/**
 * Moxw — the local-tool page, rebuilt around the two questions it can actually answer.
 *
 * ## What changed, and why
 *
 * The page used to be ~50 enable switches, one per tool group, plus a per-tool list with a second
 * switch (hide this one tool) and a pin button. On this product line that first layer is gone: every
 * group feeds one embedding-ranked catalogue (`ToolRankFusion` / `ToolVectorIndex`), so *not*
 * enabling a group never hid anything from the model — the ranking decides what a turn sees, and a
 * smaller catalogue only removes things the ranking might have wanted. "All on" is therefore the
 * truthful default, and [LocalToolGates] enforces it at the single assembly point rather than
 * trusting a stored list.
 *
 * What remains is the one choice the user really owns:
 *
 *  - **Gated groups** (see [LocalToolGates]) keep their enable switch, because their tools cannot
 *    do anything until the user supplies something the app cannot supply for itself — an external
 *    app, a system service, hardware, a token. Without the prerequisite the group is pure noise in
 *    the catalogue, so it may be switched off, and off means "not in the tool table at all".
 *  - **Resident tools** — the pins ([Assistant.pinnedToolNames]). A turn already receives whatever
 *    the ranking retrieved; a pinned tool skips the ranking and is attached every single turn. That
 *    is the only way a tool gets in front of the model *without* being searched for, which is why
 *    screen automation has to be pinnable: the model must not have to discover `take_screenshot`
 *    before it can look at the screen.
 *
 * Both granularities are offered, because both are real: pin a whole group ("always have the file
 * tools"), or open it and pin one tool inside it ("only `read_file`").
 */
@Composable
private fun AssistantLocalToolContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
) {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val telegramBotPreferences = koinInject<TelegramBotPreferences>()
    val localToolFactory = koinInject<LocalTools>()

    // Group titles resolved once, outside any non-composable predicate, so the search filter can
    // match a group by its human name (which is how a Chinese-language user looks for "电池").
    val groupTitles: Map<LocalToolOption, String> = remember(ctx) {
        LocalToolGroups.all.associateWith { ctx.getString(localToolTitleRes(it)) }
    }

    // The live factory is the only authority on what a group actually produces: a name typed into
    // the page could drift from the surface the model receives. Computed once per page visit — the
    // inventory does not depend on anything the user changes here.
    val groupTools: Map<LocalToolOption, List<String>> = remember(localToolFactory) {
        LocalToolGroups.all.associateWith { option ->
            localToolFactory.getTools(listOf(option))
                .map { it.name }
                .filterNot { it in LocalTools.HIDDEN_TOOL_NAMES }
        }
    }

    val enabledGroups: Set<LocalToolOption> = remember(assistant.localTools) {
        LocalToolGates.effective(assistant.localTools).toSet()
    }
    val pinned: List<String> = assistant.pinnedToolNames

    var query by remember { mutableStateOf("") }
    var expandedGroups by remember { mutableStateOf(emptySet<LocalToolOption>()) }

    // Hardware-availability gate for the NFC switch: a device with no NFC chip can never run the
    // nfc tools, so the row is shown disabled with a "no NFC hardware" subtitle rather than
    // letting the user enable a group that would only ever error.
    val hasNfc = remember {
        ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC)
    }

    fun setGroupEnabled(option: LocalToolOption, enabled: Boolean) {
        onUpdateAssistant { current ->
            current.copy(
                localTools = if (enabled) current.localTools + option
                else current.localTools - option,
            )
        }
    }

    fun setPinned(toolName: String, enabled: Boolean) {
        onUpdateAssistant { current ->
            current.copy(
                pinnedToolNames = if (enabled) {
                    (current.pinnedToolNames + toolName).distinct()
                } else {
                    current.pinnedToolNames - toolName
                },
            )
        }
    }

    fun setGroupPinned(toolNames: List<String>, enabled: Boolean) {
        onUpdateAssistant { current ->
            current.copy(
                pinnedToolNames = if (enabled) {
                    (current.pinnedToolNames + toolNames).distinct()
                } else {
                    current.pinnedToolNames - toolNames.toSet()
                },
            )
        }
    }

    // Shizuku's permission result arrives asynchronously via this listener, not an Activity
    // callback (see ShizukuManager.requestPermission), so requestPermission() below needs one
    // registered to satisfy that contract. This page doesn't surface the result in its own UI
    // (Settings -> Shizuku owns the status display); it just logs the outcome.
    DisposableEffect(Unit) {
        val permissionResult = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Shizuku permission request result: granted=$granted")
        }
        ShizukuManager.addRequestPermissionResultListener(permissionResult)
        onDispose {
            ShizukuManager.removeRequestPermissionResultListener(permissionResult)
        }
    }

    // Setup-hint popups for the gated switches whose successful enable depends on user setup the
    // switch itself can't perform: Termux's allow-external-apps property, the Telegram bot token,
    // and the agent-keyboard installation. Each is gated to fire at most once per visit to this
    // screen, and only when the missing thing is actually missing.
    var showTermuxPostGrantDialog by remember { mutableStateOf(false) }
    var showTelegramNoTokenDialog by remember { mutableStateOf(false) }
    var showKeyboardSetupDialog by remember { mutableStateOf(false) }
    var termuxDialogShownThisVisit by remember { mutableStateOf(false) }
    var telegramDialogShownThisVisit by remember { mutableStateOf(false) }
    var keyboardDialogShownThisVisit by remember { mutableStateOf(false) }

    val termuxCommand = stringResource(R.string.assistant_page_local_tools_termux_postgrant_command)
    val termuxCopiedText = stringResource(R.string.assistant_page_local_tools_termux_postgrant_copied)

    if (showTermuxPostGrantDialog) {
        AlertDialog(
            onDismissRequest = { showTermuxPostGrantDialog = false },
            title = { Text(stringResource(R.string.assistant_page_local_tools_termux_postgrant_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.assistant_page_local_tools_termux_postgrant_message))
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = termuxCommand,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ctx.writeClipboardText(termuxCommand)
                    toaster.show(termuxCopiedText)
                    showTermuxPostGrantDialog = false
                }) {
                    Text(stringResource(R.string.assistant_page_local_tools_termux_postgrant_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTermuxPostGrantDialog = false }) {
                    Text(stringResource(R.string.assistant_page_local_tools_dialog_dismiss))
                }
            },
        )
    }

    if (showTelegramNoTokenDialog) {
        AlertDialog(
            onDismissRequest = { showTelegramNoTokenDialog = false },
            title = { Text(stringResource(R.string.assistant_page_local_tools_telegram_notoken_title)) },
            text = { Text(stringResource(R.string.assistant_page_local_tools_telegram_notoken_message)) },
            confirmButton = {
                TextButton(onClick = { showTelegramNoTokenDialog = false }) {
                    Text(stringResource(R.string.assistant_page_local_tools_dialog_dismiss))
                }
            },
        )
    }

    if (showKeyboardSetupDialog) {
        AlertDialog(
            onDismissRequest = { showKeyboardSetupDialog = false },
            title = { Text(stringResource(R.string.assistant_page_local_tools_keyboard_setup_title)) },
            text = { Text(stringResource(R.string.assistant_page_local_tools_keyboard_setup_message)) },
            confirmButton = {
                TextButton(onClick = { showKeyboardSetupDialog = false }) {
                    Text(stringResource(R.string.assistant_page_local_tools_dialog_dismiss))
                }
            },
        )
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
            text = stringResource(R.string.assistant_page_local_tools_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- gated groups: the ones that still have an off switch ------------------------------
        SectionLabel(stringResource(R.string.assistant_page_local_tools_section_gated))
        CardGroup {
            // Iterating LocalToolGates.gated (not a hand-written list) so the page cannot quietly
            // keep a switch for a group that is no longer gated, or miss one that became gated.
            // The `else` is reachable only by adding a group to LocalToolGates without teaching
            // this page how it is set up: it still renders a working switch, just without the
            // bespoke status line / setup hint.
            LocalToolGates.gated.forEach { option ->
                when (option) {
                    LocalToolOption.ScreenAutomation -> gatedRow(
                        title = groupTitles[option].orEmpty(),
                        subtitle = { Text(stringResource(R.string.assistant_page_local_tools_screen_automation_desc)) },
                        trailing = {
                            PermissionedSwitch(
                                checked = option in enabledGroups,
                                onCheckedChange = { setGroupEnabled(option, it) },
                                requiresAccessibilityService = true,
                            )
                        },
                    )

                    LocalToolOption.NotificationListener -> gatedRow(
                        title = groupTitles[option].orEmpty(),
                        subtitle = { Text(stringResource(R.string.assistant_page_local_tools_notifications_desc)) },
                        trailing = {
                            PermissionedSwitch(
                                checked = option in enabledGroups,
                                onCheckedChange = { setGroupEnabled(option, it) },
                                requiresNotificationListener = true,
                            )
                        },
                    )

                    LocalToolOption.Shizuku -> gatedRow(
                        title = groupTitles[option].orEmpty(),
                        subtitle = { Text(stringResource(R.string.assistant_page_local_tools_shizuku_desc)) },
                        trailing = {
                            // Turning this on fires Shizuku's own consent flow immediately (mirrors
                            // the request made from Settings -> Shizuku), so the app registers in
                            // the Shizuku manager's Application list and the user sees the dialog
                            // right when they express intent, instead of only after visiting that
                            // settings page. A dead binder means there is nothing to request;
                            // Settings -> Shizuku owns the setup guidance for that case. Always
                            // request when the binder is alive, without gating on
                            // isPermissionGranted() first: on some devices/manager versions
                            // checkSelfPermission() reports GRANTED even though the Shizuku manager
                            // app has never actually seen this app, which made that gate skip the
                            // request entirely. Shizuku no-ops the dialog when genuinely granted,
                            // so this is harmless in the healthy case.
                            PermissionedSwitch(
                                checked = option in enabledGroups,
                                onCheckedChange = { enabled ->
                                    setGroupEnabled(option, enabled)
                                    if (enabled && ShizukuManager.isBinderAlive()) {
                                        val selfPermission = runCatching { Shizuku.checkSelfPermission() }
                                            .getOrElse { "error: ${it.message}" }
                                        val rationale = runCatching { Shizuku.shouldShowRequestPermissionRationale() }
                                            .getOrElse { "error: ${it.message}" }
                                        Log.d(
                                            TAG,
                                            "Shizuku permission request: binderAlive=true, " +
                                                "checkSelfPermission=$selfPermission, " +
                                                "shouldShowRequestPermissionRationale=$rationale"
                                        )
                                        ShizukuManager.requestPermission()
                                    }
                                },
                            )
                        },
                    )

                    LocalToolOption.Termux -> gatedRow(
                        title = groupTitles[option].orEmpty(),
                        subtitle = { TermuxStatusRowSubtitle(enabled = option in enabledGroups) },
                        trailing = {
                            PermissionedSwitch(
                                checked = option in enabledGroups,
                                onCheckedChange = { newValue ->
                                    setGroupEnabled(option, newValue)
                                    if (newValue && !termuxDialogShownThisVisit) {
                                        // Skip the dialog if a recent successful verify proves the
                                        // property file is already in place — nothing new to teach.
                                        val recentlyVerified = TermuxIntegration.lastVerifiedOkAtMs > 0 &&
                                            (System.currentTimeMillis() - TermuxIntegration.lastVerifiedOkAtMs) < 24L * 60 * 60 * 1000
                                        if (!recentlyVerified) {
                                            termuxDialogShownThisVisit = true
                                            showTermuxPostGrantDialog = true
                                        }
                                    }
                                },
                                // Termux's RUN_COMMAND service is gated behind a dangerous-level
                                // custom permission. Requesting it through the standard runtime flow
                                // pops the system dialog so termux_run_command works without an adb
                                // grant. If Termux is not installed the request silently no-ops.
                                requiredRuntimePerms = listOf("com.termux.permission.RUN_COMMAND"),
                            )
                        },
                    )

                    LocalToolOption.KeyboardControl -> gatedRow(
                        title = groupTitles[option].orEmpty(),
                        subtitle = { KeyboardStatusRowSubtitle() },
                        trailing = {
                            PermissionedSwitch(
                                checked = option in enabledGroups,
                                onCheckedChange = { newValue ->
                                    setGroupEnabled(option, newValue)
                                    // One-time enable-time hint: the switch can't install the
                                    // companion app or switch the active IME for the user.
                                    if (newValue && !keyboardDialogShownThisVisit) {
                                        keyboardDialogShownThisVisit = true
                                        showKeyboardSetupDialog = true
                                    }
                                },
                            )
                        },
                    )

                    LocalToolOption.Nfc -> gatedRow(
                        title = groupTitles[option].orEmpty(),
                        subtitle = {
                            Text(
                                if (hasNfc) stringResource(R.string.assistant_page_local_tools_nfc_desc)
                                else stringResource(R.string.assistant_page_local_tools_nfc_unavailable)
                            )
                        },
                        trailing = {
                            PermissionedSwitch(
                                checked = hasNfc && option in enabledGroups,
                                // Guard the callback too: even if the switch is somehow toggled,
                                // a device with no NFC chip never gets the group enabled.
                                onCheckedChange = { if (hasNfc) setGroupEnabled(option, it) },
                                enabled = hasNfc,
                            )
                        },
                    )

                    LocalToolOption.TelegramBot -> gatedRow(
                        title = groupTitles[option].orEmpty(),
                        subtitle = { Text(stringResource(R.string.assistant_page_local_tools_telegram_desc)) },
                        trailing = {
                            PermissionedSwitch(
                                checked = option in enabledGroups,
                                onCheckedChange = { newValue ->
                                    setGroupEnabled(option, newValue)
                                    if (newValue && !telegramDialogShownThisVisit) {
                                        scope.launch {
                                            if (telegramBotPreferences.current().token.isBlank()) {
                                                telegramDialogShownThisVisit = true
                                                showTelegramNoTokenDialog = true
                                            }
                                        }
                                    }
                                },
                            )
                        },
                    )

                    else -> gatedRow(
                        title = groupTitles[option].orEmpty(),
                        subtitle = null,
                        trailing = {
                            PermissionedSwitch(
                                checked = option in enabledGroups,
                                onCheckedChange = { setGroupEnabled(option, it) },
                            )
                        },
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.assistant_page_local_tools_gated_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- resident tools: the one choice the user still owns ----------------------------------
        SectionLabel(stringResource(R.string.assistant_page_local_tools_section_resident))
        Text(
            text = stringResource(R.string.assistant_page_local_tools_resident_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.assistant_page_local_tools_search_hint)) },
        )

        val trimmedQuery = query.trim()
        // A gated group that is switched off is not in the tool table, so it has nothing to pin —
        // it disappears from this list rather than offering a switch that would only ever be inert.
        val residentGroups = LocalToolGroups.all.filter { option ->
            val inTable = !LocalToolGates.isGated(option) || option in enabledGroups
            if (!inTable) return@filter false
            if (trimmedQuery.isEmpty()) return@filter true
            groupTitles[option]?.contains(trimmedQuery, ignoreCase = true) == true ||
                groupTools[option].orEmpty().any { it.contains(trimmedQuery, ignoreCase = true) }
        }

        CardGroup {
            if (residentGroups.isEmpty()) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_local_tools_no_match)) },
                )
            }
            residentGroups.forEach { option ->
                val toolNames = groupTools[option].orEmpty()
                val pinnedCount = toolNames.count { it in pinned }
                // A search is a question about specific tools, so it opens the groups it matched:
                // making the user expand a group the search just found would be a second search.
                val open = trimmedQuery.isNotEmpty() || option in expandedGroups
                item(
                    onClick = {
                        expandedGroups = if (option in expandedGroups) {
                            expandedGroups - option
                        } else {
                            expandedGroups + option
                        }
                    },
                    headlineContent = { Text(groupTitles[option].orEmpty()) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.assistant_page_local_tools_resident_count,
                                pinnedCount,
                                toolNames.size,
                            )
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                // Only "every tool in this group is pinned" reads as on; a partial
                                // pin shows off with the count above saying how partial. Flipping
                                // it on therefore means "the rest of them too", never "unpin".
                                checked = toolNames.isNotEmpty() && pinnedCount == toolNames.size,
                                onCheckedChange = { enabled -> setGroupPinned(toolNames, enabled) },
                            )
                            if (trimmedQuery.isEmpty()) {
                                Icon(
                                    imageVector = if (open) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                )
                            }
                        }
                    },
                )
                if (open) {
                    toolNames
                        .filter { trimmedQuery.isEmpty() || it.contains(trimmedQuery, ignoreCase = true) }
                        .forEach { toolName ->
                            item(
                                headlineContent = {
                                    Text(
                                        text = toolName,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = toolName in pinned,
                                        onCheckedChange = { enabled -> setPinned(toolName, enabled) },
                                    )
                                },
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
}

/**
 * One gated group's row. Deliberately **not** `@Composable`: it is called from inside
 * [CardGroup]'s content lambda, which is a plain function type, and a composable cannot be
 * invoked from there. Nothing here composes anything itself — it only hands the caller's
 * `@Composable` lambdas to [CardGroupScope.item], which is exactly what that lambda is for.
 */
private fun CardGroupScope.gatedRow(
    title: String,
    subtitle: (@Composable () -> Unit)?,
    trailing: @Composable () -> Unit,
) {
    item(
        headlineContent = { Text(title) },
        supportingContent = subtitle,
        trailingContent = trailing,
    )
}

/**
 * Status subtitle for the Keyboard control toggle. Shows a colored dot summarising whether
 * the co-signed agent-keyboard app is installed and whether it is the active keyboard —
 * both are required for the keyboard_* tools to do anything. Recomputed on every onResume
 * so it reflects an install / IME switch the user just made in system settings.
 */
@Composable
private fun KeyboardStatusRowSubtitle() {
    val ctx = LocalContext.current

    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val installed = remember(resumeTick) {
        runCatching {
            ctx.packageManager.getPackageInfo(KEYBOARD_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }
    val isActiveIme = remember(resumeTick) {
        installed && runCatching {
            android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
            )?.startsWith("$KEYBOARD_PACKAGE/") == true
        }.getOrDefault(false)
    }

    val (dotColor, label) = when {
        !installed -> androidx.compose.ui.graphics.Color(0xFFEF4444) to
            stringResource(R.string.assistant_page_local_tools_keyboard_status_not_installed)
        !isActiveIme -> androidx.compose.ui.graphics.Color(0xFFF59E0B) to
            stringResource(R.string.assistant_page_local_tools_keyboard_status_installed)
        else -> androidx.compose.ui.graphics.Color(0xFF22C55E) to
            stringResource(R.string.assistant_page_local_tools_keyboard_desc)
    }

    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = dotColor)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val KEYBOARD_PACKAGE = "dev.patrickgold.florisboard"

@Composable
private fun PermissionedSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    requiredRuntimePerms: List<String> = emptyList(),
    requiresWriteSettings: Boolean = false,
    requiresDndAccess: Boolean = false,
    requiresAccessibilityService: Boolean = false,
    requiresNotificationListener: Boolean = false,
    requiresAllFilesAccess: Boolean = false,
    enabled: Boolean = true,
) {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val deniedToastFmt = stringResource(R.string.assistant_page_local_tools_perm_denied_toast)

    var showDialog by remember { mutableStateOf(false) }
    var pendingSpecialResume by remember { mutableStateOf(false) }
    // Bumped on ON_RESUME so permissionMissing recomputes when the user returns from settings.
    var resumeTrigger by remember { mutableStateOf(0) }

    // Runtime permission launcher
    val runtimePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isEmpty()) {
            onCheckedChange(true)
        } else {
            toaster.show(
                message = String.format(deniedToastFmt, denied.joinToString(", ")),
                type = ToastType.Error,
            )
        }
    }

    // Special permission settings launcher
    val specialPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // result is ignored — actual check happens on ON_RESUME
    }

    // Lifecycle observer: handles both special-perm resume and re-evaluating
    // the permissionMissing hint when the user returns from settings.
    // Keyed on lifecycleOwner only — the closure reads other state, so re-installing
    // on every state change would be wasteful (and was a measured perf bug across the
    // ~30 PermissionedSwitch instances rendered on this screen).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTrigger++
                if (pendingSpecialResume) {
                    val granted = when {
                        requiresWriteSettings -> PermissionHelper.hasWriteSettings(ctx)
                        requiresDndAccess -> PermissionHelper.hasDndAccess(ctx)
                        requiresAccessibilityService -> PermissionHelper.hasAccessibilityService(ctx)
                        requiresNotificationListener -> PermissionHelper.hasNotificationListener(ctx)
                        requiresAllFilesAccess -> PermissionHelper.hasAllFilesAccess(ctx)
                        else -> false
                    }
                    pendingSpecialResume = false
                    if (granted) {
                        onCheckedChange(true)
                    } else {
                        val name = when {
                            requiresWriteSettings -> "WRITE_SETTINGS"
                            requiresDndAccess -> "DND access"
                            requiresAccessibilityService -> "Accessibility service"
                            requiresNotificationListener -> "Notification access"
                            requiresAllFilesAccess -> "All files access"
                            else -> ""
                        }
                        toaster.show(
                            message = String.format(deniedToastFmt, name),
                            type = ToastType.Error,
                        )
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun requestPermission() {
        when {
            requiresNotificationListener -> {
                if (PermissionHelper.hasNotificationListener(ctx)) {
                    onCheckedChange(true)
                } else {
                    showDialog = true
                }
            }

            requiresAccessibilityService -> {
                if (PermissionHelper.hasAccessibilityService(ctx)) {
                    onCheckedChange(true)
                } else {
                    showDialog = true
                }
            }

            requiresWriteSettings -> {
                if (PermissionHelper.hasWriteSettings(ctx)) {
                    onCheckedChange(true)
                } else {
                    showDialog = true
                }
            }

            requiresDndAccess -> {
                if (PermissionHelper.hasDndAccess(ctx)) {
                    onCheckedChange(true)
                } else {
                    showDialog = true
                }
            }

            requiresAllFilesAccess -> {
                if (PermissionHelper.hasAllFilesAccess(ctx)) {
                    onCheckedChange(true)
                } else {
                    showDialog = true
                }
            }

            requiredRuntimePerms.isNotEmpty() -> {
                if (PermissionHelper.hasRuntime(ctx, requiredRuntimePerms)) {
                    onCheckedChange(true)
                } else {
                    runtimePermLauncher.launch(requiredRuntimePerms.toTypedArray())
                }
            }

            else -> onCheckedChange(true)
        }
    }

    // Recomputed each ON_RESUME via resumeTrigger so the hint reflects any
    // permissions the user toggled in system settings while we were paused.
    // Key on a stable string derived from the perms list — Compose can't compare
    // raw List<String> for structural equality across recomps, so passing the list
    // directly invalidated this remember on every parent recomp.
    val permsKey = remember(requiredRuntimePerms) { requiredRuntimePerms.joinToString(",") }
    val permissionMissing = remember(checked, resumeTrigger, permsKey, requiresWriteSettings, requiresDndAccess, requiresAccessibilityService, requiresNotificationListener, requiresAllFilesAccess) {
        checked && when {
            requiredRuntimePerms.isNotEmpty() -> !PermissionHelper.hasRuntime(ctx, requiredRuntimePerms)
            requiresWriteSettings -> !PermissionHelper.hasWriteSettings(ctx)
            requiresDndAccess -> !PermissionHelper.hasDndAccess(ctx)
            requiresAccessibilityService -> !PermissionHelper.hasAccessibilityService(ctx)
            requiresNotificationListener -> !PermissionHelper.hasNotificationListener(ctx)
            requiresAllFilesAccess -> !PermissionHelper.hasAllFilesAccess(ctx)
            else -> false
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(stringResource(R.string.assistant_page_local_tools_perm_special_dialog_title))
            },
            text = {
                Text(stringResource(R.string.assistant_page_local_tools_perm_special_dialog_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    val intent = when {
                        requiresWriteSettings -> PermissionHelper.writeSettingsIntent(ctx)
                        requiresDndAccess -> PermissionHelper.dndAccessIntent(ctx)
                        requiresAccessibilityService -> PermissionHelper.accessibilitySettingsIntent()
                        requiresNotificationListener -> PermissionHelper.notificationListenerSettingsIntent()
                        requiresAllFilesAccess -> PermissionHelper.allFilesAccessIntent(ctx)
                        else -> null
                    }
                    if (intent != null) {
                        pendingSpecialResume = true
                        specialPermLauncher.launch(intent)
                    }
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    Column(horizontalAlignment = Alignment.End) {
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { newChecked ->
                if (!newChecked) {
                    onCheckedChange(false)
                    return@Switch
                }
                // Turning ON
                requestPermission()
            }
        )
        if (permissionMissing) {
            Text(
                text = stringResource(R.string.assistant_page_local_tools_perm_needed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { requestPermission() },
            )
        }
    }
}

/**
 * Subtitle row for the Termux toggle: shows a colored dot summarising the integration
 * state plus a "Verify" affordance that runs an end-to-end smoke test (sends a tiny
 * command through Termux and waits for the result). The dot is ONLY green after a
 * successful verification within the last hour — that's the only signal that proves
 * `allow-external-apps=true` is actually in effect, since we cannot read Termux's
 * private home dir.
 */
@Composable
private fun TermuxStatusRowSubtitle(enabled: Boolean) {
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val staticState = remember(resumeTick) { TermuxIntegration.state(ctx) }

    var verifying by remember { mutableStateOf(false) }
    var lastVerifyError by remember { mutableStateOf<String?>(null) }

    // Reads the process-scoped timestamp from TermuxIntegration, backed by TermuxPreferences
    // so a successful verify keeps the dot green across app restarts too, not just navigation
    // within this session (GitHub #14). No recency window: matches SettingTermuxPage's rule.
    // resumeTick triggers a recompute on every onResume.
    val lastVerifiedOkAt = remember(resumeTick) { TermuxIntegration.lastVerifiedOkAtMs }
    val verified = lastVerifiedOkAt > 0

    val (dotColor, label) = when {
        staticState == TermuxIntegration.State.NOT_INSTALLED ->
            androidx.compose.ui.graphics.Color(0xFFEF4444) to stringResource(R.string.assistant_page_local_tools_termux_status_not_installed)
        staticState == TermuxIntegration.State.NO_PERMISSION ->
            androidx.compose.ui.graphics.Color(0xFFF59E0B) to stringResource(R.string.assistant_page_local_tools_termux_status_no_permission)
        verified ->
            androidx.compose.ui.graphics.Color(0xFF22C55E) to stringResource(R.string.assistant_page_local_tools_termux_status_ok)
        lastVerifyError != null ->
            androidx.compose.ui.graphics.Color(0xFFEF4444) to (lastVerifyError ?: "")
        else ->
            androidx.compose.ui.graphics.Color(0xFFEAB308) to stringResource(R.string.assistant_page_local_tools_termux_status_untested)
    }

    val verifyHint = stringResource(R.string.assistant_page_local_tools_termux_verify)
    val verifyingHint = stringResource(R.string.assistant_page_local_tools_termux_verifying)

    val canVerify = !verifying && enabled && staticState == TermuxIntegration.State.READY

    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .let { if (canVerify) it.clickable {
                if (verifying) return@clickable
                verifying = true
                lastVerifyError = null
                scope.launch {
                    val result = TermuxIntegration.verify(ctx)
                    verifying = false
                    when (result) {
                        TermuxIntegration.VerifyResult.Ok -> {
                            TermuxIntegration.markVerifiedOk()
                            resumeTick++  // force recompose so verifiedRecently flips
                            toaster.show(ctx.getString(R.string.assistant_page_local_tools_termux_verify_ok))
                        }
                        TermuxIntegration.VerifyResult.AllowExternalAppsMissing -> {
                            TermuxIntegration.clearVerified()
                            resumeTick++
                            lastVerifyError = ctx.getString(R.string.assistant_page_local_tools_termux_verify_props_missing)
                            toaster.show(lastVerifyError ?: "", type = ToastType.Error)
                        }
                        TermuxIntegration.VerifyResult.NoPermission -> {
                            TermuxIntegration.clearVerified()
                            resumeTick++
                            lastVerifyError = ctx.getString(R.string.assistant_page_local_tools_termux_verify_no_permission)
                            toaster.show(lastVerifyError ?: "", type = ToastType.Error)
                        }
                        TermuxIntegration.VerifyResult.NotInstalled -> {
                            TermuxIntegration.clearVerified()
                            resumeTick++
                            lastVerifyError = ctx.getString(R.string.assistant_page_local_tools_termux_status_not_installed)
                        }
                        is TermuxIntegration.VerifyResult.UnexpectedOutput -> {
                            TermuxIntegration.clearVerified()
                            resumeTick++
                            lastVerifyError = ctx.getString(R.string.assistant_page_local_tools_termux_verify_unexpected, result.stdout.take(60))
                            toaster.show(lastVerifyError ?: "", type = ToastType.Error)
                        }
                        is TermuxIntegration.VerifyResult.OtherError -> {
                            // Transient failure (Termux killed, timeout, etc), not proof the
                            // setup is wrong: don't erase a previously-verified state over it.
                            resumeTick++
                            lastVerifyError = result.message
                            toaster.show(result.message, type = ToastType.Error)
                        }
                    }
                }
            } else it }
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.size(10.dp)
        ) {
            drawCircle(color = dotColor)
        }
        Text(
            text = if (verifying) verifyingHint
                   else if (canVerify && !verified) "$label · $verifyHint"
                   else label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
