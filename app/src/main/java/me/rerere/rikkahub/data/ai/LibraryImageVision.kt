package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.vector.ImageSummaryCache
import me.rerere.rikkahub.data.vector.ImageSummaryRules
import java.io.File

/**
 * Describes one image of the file library, so that it can be searched for by what it *is*.
 *
 * The library's second opinion about a picture, beside on-device recognition: recognition reads the
 * characters that are in it, this asks a vision model what it shows. Both are needed - a search for
 * `GGML_ASSERT` needs the first, a search for "that screenshot of the failing build" needs this -
 * and [ImageSummaryRules] decides how the two are put together.
 *
 * Three things this class is careful about, all of them about *other people's money and pictures*:
 *
 *  - **Off unless asked for.** [Settings.libraryImageSummary] is the user's answer, and it is a
 *    switch of its own rather than "a vision model is configured", because the same model is
 *    already configured on many installs for chat attachments - turning the library on must not
 *    quietly start uploading the files it walks.
 *  - **A model that can actually see.** `Modality.IMAGE` in the model's input modalities is the
 *    app's own notion of that (the attachment picker and the tool surface read the same flag), and
 *    asking a model without it is a call that cannot succeed.
 *  - **Once per image.** Every round walks the library again, so the answer is remembered under
 *    [ImageSummaryRules.cacheKeyOf] - which also means an edited prompt or a different model is
 *    asked afresh rather than answered from the last one.
 */
class LibraryImageVision(
    private val settingsStore: SettingsStore,
    private val cache: ImageSummaryCache,
) {

    /**
     * The model's description of [file], or null when there is nobody to ask or no answer came.
     *
     * [path] and [sizeBytes] identify the file for the cache; [file] is what gets sent.
     */
    suspend fun describe(path: String, sizeBytes: Long, file: File): String? {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(settings.ocrModelId)
        val canSeeImages = model?.inputModalities?.contains(Modality.IMAGE) == true
        if (model == null) return null
        if (!ImageSummaryRules.enabled(settings.libraryImageSummary, canSeeImages)) return null

        val key = ImageSummaryRules.cacheKeyOf(
            path = path,
            sizeBytes = sizeBytes,
            modelUuid = model.id.toString(),
            prompt = settings.libraryImagePrompt,
        )
        cache.get(key)?.let { return it }

        val described = OcrTransformer.describe(
            part = UIMessagePart.Image("file://${file.absolutePath}"),
            prompt = settings.libraryImagePrompt,
        ) ?: return null

        cache.put(key, described)
        return described
    }
}
