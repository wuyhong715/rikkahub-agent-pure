package me.rerere.rikkahub.data.vector

/**
 * How a *described* image becomes the text that gets indexed.
 *
 * The file library has two ways to find out what is in a picture, and they answer different
 * questions. Recognition ([ImageTextExtractor]) reads the characters that are in it, locally, for
 * free, and precisely - it is what makes `GGML_ASSERT` findable. A vision model says what the
 * picture *is* - "an Android Studio window with a failing Gradle build" - which is the half a
 * person searching for it usually has in mind, and the half recognition cannot produce.
 *
 * Neither is a substitute for the other, so both go into the same text, and this file decides what
 * that text is. It is pure and tested because the failure it guards is quiet: an empty string here
 * is a file that never enters the index (and is therefore re-read every round, forever), and a
 * *failure message* here is worse - it puts the words "could not be read" into the index as though
 * the picture had them written on it.
 */
object ImageSummaryRules {

    /**
     * Whether an image should be described at all.
     *
     * Two independent conditions, both required. The switch is the user's answer to "may my images
     * be sent to a provider" - off by default, and the reason this is not simply "a model is
     * configured" is that a vision model is already configured for chat attachments on many
     * installs, and turning the library on must not silently start uploading their files.
     *
     * `modelCanSeeImages` is the app's own notion of a model that can look at a picture:
     * `Modality.IMAGE` in `Model.inputModalities`, the same flag the attachment picker and the
     * tool surface read. A model that does not have it will be refused by the provider, so asking
     * it is a call that cannot succeed.
     */
    fun enabled(switchedOn: Boolean, modelCanSeeImages: Boolean): Boolean =
        switchedOn && modelCanSeeImages

    /**
     * The text an image is indexed as.
     *
     * The description comes first because it is the whole-image statement - it is what a search for
     * "that screenshot of the failing build" has to match - and the recognised text follows as what
     * is literally on the image, on its own line, where an exact identifier or error code still
     * lands in the same passage.
     *
     * With neither, the file's own name is the text: an image that is not in the index is one the
     * next round reads first, so a photograph nothing can be said about would be recognised - and,
     * with the model on, *uploaded* - again every five minutes, forever.
     */
    fun compose(ocrText: String?, summary: String?, name: String): String {
        val parts = listOfNotNull(
            summary?.trim()?.takeIf { it.isNotEmpty() },
            ocrText?.trim()?.takeIf { it.isNotEmpty() },
        )
        return if (parts.isEmpty()) {
            WorkspaceLibraryRules.placeholderForImage(name)
        } else {
            parts.joinToString("\n\n")
        }
    }

    /**
     * What a remembered description is remembered *as*: the file, the model, and the question.
     *
     * All three, because a cache that answered a different question than the one being asked is
     * worse than no cache: the description is the half of the index text nobody can check by
     * looking at the file, so a stale one is invisible. Changing the prompt or the model therefore
     * has to miss, and does - which also means an edited prompt re-describes the library over the
     * next rounds rather than never.
     *
     * The file is identified by path and size rather than by hashing its bytes: this runs for every
     * candidate image of every round, a photograph can be megabytes, and the cost of reading all of
     * them to decide whether to read one would dwarf the call being avoided. The case that loses is
     * an image replaced in place by a different image of exactly the same byte count; what happens
     * then is an older description beside freshly recognised text, which search still finds.
     */
    fun cacheKeyOf(path: String, sizeBytes: Long, modelUuid: String, prompt: String): String =
        "$CACHE_KEY_VERSION$modelUuid:${ContentHash.of(prompt)}:$sizeBytes:$path"

    /** Bumped when the shape of a remembered description changes, so old entries simply age out. */
    private const val CACHE_KEY_VERSION = "v1:"
}
