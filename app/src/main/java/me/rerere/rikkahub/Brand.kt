package me.rerere.rikkahub

/**
 * Product identity.
 *
 * What used to hardcode the brand — on-disk folders under /sdcard, the built-in User-Agent, OAuth
 * landing pages, notification/recovery prose — reads it from [NAME] instead of a literal.
 *
 * This branch is the Moxw line: an assistant built around a local embedding model. Memory, the
 * file library, conversation history and the tool catalogue are all searched by meaning, there is
 * no keyword fallback anywhere, and the tool surface is the retrieval-driven one.
 */
object Brand {
    /** "Moxw". */
    val NAME: String = BuildConfig.BRAND_NAME
}
