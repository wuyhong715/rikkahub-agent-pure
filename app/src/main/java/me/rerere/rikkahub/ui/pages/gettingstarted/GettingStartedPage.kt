package me.rerere.rikkahub.ui.pages.gettingstarted

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.LocalToolGates
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.permissions.PermissionInventory
import me.rerere.rikkahub.data.permissions.isAggressive
import me.rerere.rikkahub.data.permissions.keepAliveVendorOf
import me.rerere.rikkahub.data.vector.EmbeddingModelFiles
import me.rerere.rikkahub.data.vector.EmbeddingReadiness
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.writeBooleanPreference
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus

/** Preference key: once the user has seen (or dismissed) the first-run guide, never show it again. */
const val GETTING_STARTED_SEEN_KEY = "getting_started_seen"

enum class GettingStartedStep { Model, Embedding, Tools, Permissions, Workspace }

/**
 * The steps without which the app cannot do its job at all. Everything else only *adds*
 * capability, so the guide tracks progress against these two rather than treating all five
 * as equally required — on Moxw a user with both models configured already has a working
 * agent, and a "done 2 of 5" that never resolves reads as a failure.
 *
 * Moxw-specific: [GettingStartedStep.Embedding] is the piece the stock line does not have.
 * Retrieval in this product runs through the on-device embedding model, so a chat model
 * alone buys a plain chat app — no tools, no memory.
 */
val REQUIRED_GETTING_STARTED_STEPS: Set<GettingStartedStep> =
    setOf(GettingStartedStep.Model, GettingStartedStep.Embedding)

/**
 * A model is "configured" when at least one enabled provider carries at least one model.
 * That is exactly what the user sees after pasting an API key: the provider light flips on
 * and its model list stops being empty.
 */
fun Settings.hasConfiguredModel(): Boolean =
    providers.any { it.enabled && it.models.isNotEmpty() }

/**
 * Pure Kotlin so it can be unit-tested on the JVM — the caller supplies [deniedPermissions]
 * from [PermissionInventory] and [embeddingReady] from [EmbeddingReadiness], both of which need
 * a Context. False means "still to do".
 *
 * The tools step now asks a different question than the stock line: the catalogue is always
 * assembled and ranked by meaning, so "did you turn tools on" is meaningless. What a user can
 * still decide is which of the *gated* groups — the ones that need something only they can
 * provide (Shizuku, Termux, the accessibility service, ...) — are on. [LocalToolGates.isGated]
 * is the same predicate the tool page uses, so the checklist and the page cannot disagree.
 */
fun evaluateGettingStartedSteps(
    settings: Settings,
    deniedPermissions: Int,
    embeddingReady: Boolean,
): Map<GettingStartedStep, Boolean> =
    mapOf(
        GettingStartedStep.Model to settings.hasConfiguredModel(),
        GettingStartedStep.Embedding to embeddingReady,
        GettingStartedStep.Tools to settings.assistants.any { a ->
            a.localTools.any { LocalToolGates.isGated(it) }
        },
        GettingStartedStep.Permissions to (deniedPermissions == 0),
        GettingStartedStep.Workspace to settings.assistants.any { it.workspaceId != null },
    )

/**
 * First-run guide. The stock RikkaHub opens straight into an empty chat with no model, no
 * tools and no permissions — so the single most common complaint is "I installed it and
 * don't know what to do". This page turns that dead end into an ordered, tappable checklist
 * that points at the exact setting each capability lives in.
 *
 * The Moxw build adds the one prerequisite the stock line does not have: an on-device
 * embedding model, which memory, the file library, past-chat search and the tool catalogue
 * all run on. It is listed second, right after the chat model, because without it the two
 * required steps are not done no matter what else is set up.
 */
@Composable
fun GettingStartedPage() {
    val settings = LocalSettings.current
    val nav = LocalNavController.current
    val ctx = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Only the two service bindings (accessibility + notification listener) gate the app's
    // headline capabilities and are the ones users can never find on their own. The other
    // runtime permissions stay "ask when needed", so we don't count them here.
    val pendingServicePermissions = remember(settings, ctx) {
        runCatching {
            PermissionInventory.build(ctx).count { row ->
                row.group == PermissionInventory.Group.ServicesAndIntegrations &&
                    row.status == PermissionInventory.Status.DENIED
            }
        }.getOrDefault(0)
    }

    // Resolved through the same rules the embedder itself uses, so this checklist cannot claim
    // semantic search is ready while the service refuses to run — a configured file that was
    // deleted, or a curated model installed under its repository name, land the same way here.
    val embeddingReady = remember(settings.embeddingModelFile, settings.embeddingBackend, settings.embeddingCloudModel) {
        runCatching {
            EmbeddingReadiness.isReady(settings, EmbeddingModelFiles.installedFiles(ctx))
        }.getOrDefault(false)
    }

    val steps = evaluateGettingStartedSteps(settings, pendingServicePermissions, embeddingReady)
    val requiredTotal = REQUIRED_GETTING_STARTED_STEPS.size
    val requiredDone = REQUIRED_GETTING_STARTED_STEPS.count { steps[it] == true }
    val assistantId = settings.getCurrentAssistant().id.toString()
    // Only aggressive OEM ROMs reap the accessibility service in the background; for everyone
    // else the tip is noise, so gate it on the vendor.
    val keepAliveVendor = keepAliveVendorOf(Build.MANUFACTURER, Build.BRAND)

    fun finish() {
        ctx.writeBooleanPreference(GETTING_STARTED_SEEN_KEY, true)
        nav.popBackStack()
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.getting_started_title)) },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") {
                IntroCard(requiredDone = requiredDone, requiredTotal = requiredTotal)
            }

            item(key = "step-model") {
                StepCard(
                    index = 1,
                    titleRes = R.string.getting_started_step_model_title,
                    descRes = R.string.getting_started_step_model_desc,
                    done = steps[GettingStartedStep.Model] == true,
                    optional = false,
                    onAction = { nav.navigate(Screen.SettingProvider) },
                )
            }

            item(key = "step-embedding") {
                StepCard(
                    index = 2,
                    titleRes = R.string.getting_started_step_embedding_title,
                    descRes = R.string.getting_started_step_embedding_desc,
                    done = steps[GettingStartedStep.Embedding] == true,
                    optional = false,
                    onAction = { nav.navigate(Screen.SettingEmbedding) },
                )
            }

            item(key = "step-tools") {
                StepCard(
                    index = 3,
                    titleRes = R.string.getting_started_step_tools_title,
                    descRes = R.string.getting_started_step_tools_desc,
                    done = steps[GettingStartedStep.Tools] == true,
                    optional = true,
                    onAction = { nav.navigate(Screen.AssistantLocalTool(assistantId)) },
                )
            }

            item(key = "step-permissions") {
                StepCard(
                    index = 4,
                    titleRes = R.string.getting_started_step_permissions_title,
                    descRes = R.string.getting_started_step_permissions_desc,
                    done = steps[GettingStartedStep.Permissions] == true,
                    optional = true,
                    onAction = { nav.navigate(Screen.SettingPermissions) },
                )
            }

            if (keepAliveVendor.isAggressive) {
                item(key = "keepalive") {
                    KeepAliveTip(onAction = { nav.navigate(Screen.SettingAccessibility) })
                }
            }

            item(key = "step-workspace") {
                StepCard(
                    index = 5,
                    titleRes = R.string.getting_started_step_workspace_title,
                    descRes = R.string.getting_started_step_workspace_desc,
                    done = steps[GettingStartedStep.Workspace] == true,
                    optional = true,
                    onAction = { nav.navigate(Screen.AssistantBasic(assistantId)) },
                )
            }

            item(key = "actions") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Button(
                        onClick = { finish() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.getting_started_finish))
                    }
                    TextButton(onClick = { finish() }) {
                        Text(stringResource(R.string.getting_started_later))
                    }
                }
            }
        }
    }
}

@Composable
private fun IntroCard(requiredDone: Int, requiredTotal: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.getting_started_intro_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.getting_started_intro_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.getting_started_progress, requiredDone, requiredTotal),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun StepCard(
    index: Int,
    titleRes: Int,
    descRes: Int,
    done: Boolean,
    optional: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    color = if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (done) "✓" else index.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (done) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (optional) {
                    OptionalBadge()
                }
            }
            Text(
                stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.width(8.dp))

        if (done) {
            Text(
                stringResource(R.string.getting_started_done),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(onClick = onAction) {
                Text(
                    stringResource(R.string.getting_started_go),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** A quiet chip that says a step is a "nice to have" — so an unfinished optional step reads as a choice, not a failure. */
@Composable
private fun OptionalBadge() {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            stringResource(R.string.getting_started_optional),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * Shown on the first-run guide for OEMs that reap background apps (Vivo/Xiaomi/Huawei/OPPO):
 * the accessibility service they just enabled can be dropped by the system a few minutes
 * later, which reads as a bug. Points them at the keep-alive steps before it happens.
 */
@Composable
private fun KeepAliveTip(onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.getting_started_keepalive_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.getting_started_keepalive_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.getting_started_keepalive_open))
        }
    }
}
