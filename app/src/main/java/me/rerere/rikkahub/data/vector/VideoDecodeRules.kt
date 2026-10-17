package me.rerere.rikkahub.data.vector

/**
 * Which frames of a video are worth embedding, and how a hit points back at one.
 *
 * The engine has no video input - the SDK's `InputData` has text, image and audio, nothing else -
 * so a video is represented the way the model card describes it: sampled frames through the vision
 * encoder. Two numbers decide the cost, and both are ours rather than the model's:
 *
 *  * the model charges 140 tokens per frame by default, so a handful of frames per clip is what
 *    fits, not one per second;
 *  * every frame is a vision-tower pass, measured at ~2.1 s an image on this hardware, which is why
 *    the cap is a dozen rather than a hundred.
 *
 * Coverage rather than density follows from that: the interval is stretched so the sampled frames
 * span the whole clip instead of crowding its first minute. A search for "the part where the dog
 * jumps in" is answered by *a* frame near the moment, and a uniform grid is what makes "near"
 * bounded by the interval instead of by the clip's length.
 */
object VideoDecodeRules {

    /** Frames a single file may contribute per round. */
    const val MAX_FRAMES_PER_FILE = 12

    /**
     * The tightest sampling interval.
     *
     * Two seconds: closer than that, consecutive frames of a phone video are near-duplicates, and
     * near-duplicates are what makes a result list return the same moment twelve times.
     */
    const val MIN_INTERVAL_MS = 2_000

    /**
     * Longest edge handed to the vision encoder.
     *
     * The model patchifies and scales whatever it is given, so this is purely about decode cost: a
     * 4K frame is 8 MP of bitmap to allocate before the encoder throws most of it away. 1024 keeps a
     * frame legible for scene retrieval at a quarter of that.
     */
    const val MAX_EDGE_PX = 1_024

    /** JPEG quality for the frame handed to the engine. */
    const val JPEG_QUALITY = 85

    /**
     * When to sample a [durationMs] clip, spread over all of it.
     *
     * The interval is stretched rather than the tail dropped: a ten-minute recording sampled at the
     * minimum interval would spend the whole frame budget in its first 24 seconds, which is exactly
     * the case where a search must still reach the end.
     */
    fun frameTimesMs(
        durationMs: Long,
        maxFrames: Int = MAX_FRAMES_PER_FILE,
        minIntervalMs: Long = MIN_INTERVAL_MS.toLong(),
    ): List<Long> {
        if (durationMs <= 0 || maxFrames <= 0) return emptyList()
        val interval = maxOf(minIntervalMs.coerceAtLeast(1L), durationMs / maxFrames)
        val times = ArrayList<Long>(maxFrames)
        var at = 0L
        while (at < durationMs && times.size < maxFrames) {
            times += at
            at += interval
        }
        return times
    }

    /** The `inSampleSize` that brings a [width] x [height] frame under [MAX_EDGE_PX]. */
    fun sampleSizeFor(width: Int, height: Int, maxEdgePx: Int = MAX_EDGE_PX): Int =
        ImageDecodeRules.sampleSizeFor(width, height, maxEdgePx)
}
