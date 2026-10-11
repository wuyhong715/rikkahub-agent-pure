package me.rerere.rikkahub.data.vector

import me.rerere.locallm.litert.LitertEmbeddingCatalog

/**
 * Which accelerator a local embedding model should run on when the user has not said.
 *
 * The default is deliberately not a constant. The two accelerators do not compute the same function
 * (cosine 0.9997 on the same sentence), so asking for a rebuild is the cost of switching, and only
 * one of the two has anything to gain from it: the text encoder measures within noise on this
 * hardware (~57 ms on the CPU against ~63 ms on the GPU), while an image costs ~2.1 s because of the
 * vision tower. A model that embeds images is therefore the case worth defaulting to the GPU, and a
 * text-only one is the case where the GPU buys nothing and costs a rebuild.
 *
 * Pure, so the whole policy fits in a unit test - which matters more than usual here, because the
 * alternative is discovering the default on a device that has already indexed something.
 */
object EmbeddingAcceleratorRules {

    /**
     * Whether to use the GPU, given the user's preference and what the model does.
     *
     * [explicit] is tri-state on purpose: `null` means "not chosen yet" and follows [embedsImages],
     * while a boolean is a decision the user made and is never second-guessed. That is what keeps a
     * default from being a trap - the switch is a real switch, and a user who has answered it once
     * keeps their answer even when the model changes.
     */
    fun useGpu(explicit: Boolean?, embedsImages: Boolean): Boolean = explicit ?: embedsImages

    /**
     * Whether a selected file embeds images as images, for the default above.
     *
     * Only the curated `.litertlm` entries know this - a GGUF here is text-only, an uncurated
     * `.litertlm` is refused at load time, and a cloud model's pictures are its provider's business.
     * So an unknown file is `false`, which lands on the CPU: the safe direction, because it costs
     * nothing a user can notice, while defaulting an unknown model to the GPU would spend a rebuild
     * on a model that may not even load on it.
     */
    fun selectedModelEmbedsImages(fileName: String?): Boolean {
        val name = fileName?.takeIf { it.isNotBlank() } ?: return false
        return LitertEmbeddingCatalog.entryFor(name)?.vision == true
    }
}
