package me.rerere.rikkahub

/**
 * Flavor-scoped product identity.
 *
 * The two shipping lines (see cold-memory M21/M30) share one source tree. What used to hardcode
 * the brand — on-disk folders under /sdcard, the built-in User-Agent, OAuth landing pages,
 * notification/recovery prose — reads it from [NAME] instead of a literal.
 *
 * They are *not* identical in behaviour, though. The Moxw line is built around a local embedding
 * model: memory, the file library, conversation history and the tool catalogue are all searched by
 * meaning, there is no keyword fallback left anywhere, and the tool surface is always the
 * retrieval-driven one. The pure line keeps its keyword paths, its direct tool surface and its
 * `DIRECT`/`PROGRESSIVE_CATALOG` choice. [isMoxw] is the single place a runtime branch asks which
 * product it is in; cold-memory M30 is the map of what differs.
 */
object Brand {
    /** "RikkaHub" on the pure flavor, "Moxw" on the moxw flavor. */
    val NAME: String = BuildConfig.BRAND_NAME

    /** "pure" on the pure flavor, "moxw" on the moxw flavor. */
    val ID: String = BuildConfig.BRAND_ID

    /**
     * True on the Moxw line.
     *
     * Gates the features that only exist there (the whole vector stack, the semantic search tools,
     * the embedding-model gate, the retrieval-driven tool surface) as well as the pure-only
     * behaviour the Moxw line removed (keyword fallbacks, the direct tool surface).
     */
    val isMoxw: Boolean = ID == "moxw"
}
