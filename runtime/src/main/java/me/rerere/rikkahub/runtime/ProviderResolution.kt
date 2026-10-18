package me.rerere.rikkahub.runtime

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting

/**
 * Provider resolution for a model (P4: extracted from :app's PreferencesStore.kt —
 * pure :ai-type logic, needed by the generation loop and by :app UI alike).
 *
 * A model's own `providerOverwrite` wins over the list match when [checkOverwrite] is
 * set; the overwrite is returned with an empty model list to avoid pretending the
 * overwritten provider "contains" the model.
 */
fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}
