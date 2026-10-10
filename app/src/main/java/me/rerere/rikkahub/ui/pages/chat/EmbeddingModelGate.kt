package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.vector.EmbeddingModelFiles
import me.rerere.rikkahub.data.vector.EmbeddingReadiness
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean

/** Set once the user has seen the gate, so it is a first-run notice and not a nag. */
private const val EMBEDDING_GATE_ACK_KEY = "moxw_embedding_gate_acknowledged"

/**
 * Moxw — the embedding model is a prerequisite, not an enhancement, and this is the one place that
 * says so out loud.
 *
 * Every retrieval in this app runs through the local embedding model: cold memory, the file
 * library, conversation history, and the tool catalogue. There is no keyword path left behind any
 * of them (see [me.rerere.rikkahub.data.ai.tools.buildToolCatalogTools]), so an install without a
 * model is not a degraded app — it is an app where the assistant has no tools and remembers
 * nothing. The assistant and search pages each carry a standing hint, but those are only found by
 * someone who already went looking; the first chat is where a new install actually is.
 *
 * Shown once. The acknowledgement is a plain preference rather than a [Settings] field because it
 * is a piece of UI state ("has this notice been seen"), and `Settings` is persisted field by field
 * with a test that enforces it — a notice does not belong in that list.
 *
 * "Install" leads to the assistant's memory page, the one screen that both explains the model and
 * can download it: the same destination the other two hints point at.
 */
@Composable
fun EmbeddingModelGate(setting: Settings) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    var acknowledged by rememberSharedPreferenceBoolean(EMBEDDING_GATE_ACK_KEY)

    // Resolved through the same rules the embedder itself uses, so this notice cannot disagree
    // with the feature it is about: a configured file that was deleted, or a curated model
    // installed under its repository name, land the same way here as they do at embedding time.
    // One resolver for every screen that asks this question, so a settings row can never claim
    // semantic search is ready while the embedder itself refuses to run.
    val installed = remember(setting.embeddingModelFile, setting.embeddingBackend, setting.embeddingCloudModel) {
        EmbeddingReadiness.isReady(setting, EmbeddingModelFiles.installedFiles(context))
    }

    if (installed || acknowledged) return

    AlertDialog(
        onDismissRequest = { acknowledged = true },
        title = { Text(stringResource(R.string.embedding_gate_title)) },
        text = { Text(stringResource(R.string.embedding_gate_desc)) },
        confirmButton = {
            TextButton(
                onClick = {
                    acknowledged = true
                    navController.navigate(
                        Screen.AssistantMemory(id = setting.getCurrentAssistant().id.toString())
                    )
                }
            ) {
                Text(stringResource(R.string.embedding_gate_action))
            }
        },
        dismissButton = {
            TextButton(onClick = { acknowledged = true }) {
                Text(stringResource(R.string.embedding_gate_later))
            }
        },
    )
}
