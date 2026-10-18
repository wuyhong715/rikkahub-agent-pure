package me.rerere.rikkahub.runtime

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage

/**
 * AILogging — the generation-request log record (P4: moved to :runtime).
 *
 * The loop emits one [Generation] per model request; :app's AILoggingManager (which
 * implements [LoopLogSink]) retains the latest 32 for the developer page. Only the
 * sealed record lives in :runtime — the manager (SettingsStore / AppScope) stays :app.
 */
sealed class AILogging {
    data class Generation(
        val params: TextGenerationParams,
        val messages: List<UIMessage>,
        val providerSetting: ProviderSetting,
        val stream: Boolean,
    ) : AILogging()
}
