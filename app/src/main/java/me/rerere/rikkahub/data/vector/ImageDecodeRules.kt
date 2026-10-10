package me.rerere.rikkahub.data.vector

/**
 * The arithmetic of decoding an image for text recognition, kept apart from the decode itself.
 *
 * A phone camera writes 4000-pixel-wide JPEGs, and decoding one at full size costs tens of
 * megabytes of heap before the recognizer has looked at a single pixel - while recognising text
 * gains nothing from resolution it cannot read. So an image is decoded at a reduced sample size,
 * and that sample size is the one number in this feature worth testing on its own: it is integer
 * arithmetic that is easy to get convincingly wrong, and being wrong does not throw - it produces
 * an image that is silently too blurry to read, or one that is silently too large to decode.
 *
 * `inSampleSize` is a power of two, and Android's decoder rounds anything else down to one, so this
 * picks the smallest power of two whose result still fits [DEFAULT_MAX_EDGE_PX].
 */
object ImageDecodeRules {

    /**
     * Longest edge of the bitmap handed to the recognizer.
     *
     * Chosen as "more than any screenshot needs, less than a photograph": a 1440-pixel-wide phone
     * screenshot is handed over untouched, while a 12 MP photograph is reduced to 1008 pixels, at
     * which its text is either legible or was never going to be.
     */
    const val DEFAULT_MAX_EDGE_PX = 1600

    /**
     * The `inSampleSize` for a [width] x [height] image decoded to a longest edge of [maxEdgePx].
     *
     * Never below 1: a decoder is allowed to enlarge, and an image that already fits must be handed
     * over as it is - upscaling a blurry screenshot does not make its text readable, and blowing a
     * small image up to 1600 pixels is a cost with no matching benefit.
     */
    fun sampleSizeFor(
        width: Int,
        height: Int,
        maxEdgePx: Int = DEFAULT_MAX_EDGE_PX,
    ): Int {
        if (width <= 0 || height <= 0) return 1
        if (maxEdgePx <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / sample > maxEdgePx) sample *= 2
        return sample
    }
}
