package me.rerere.rikkahub.data.vector

/**
 * Which accelerator a local embedding model runs on when the user has not said.
 *
 * The CPU, and this is a measurement rather than a preference. On a Snapdragon 8 Elite (SM8750 /
 * Adreno 830) the LiteRT GPU backend cannot build this model's graph at all: `initialize()` fails
 * with `Failed to modify graph with delegate`, the engine never comes up, and what the user loses is
 * not a faster index but *any* index - cold memory, the file library, history search and the tool
 * catalogue all sit behind it (measured 2026-10-11, on a release build, from `logcat`).
 *
 * So "not chosen" cannot mean "the faster one", and it cannot mean "ask the device" either: the SDK
 * offers no way to find out without opening an engine, and the first thing a user would see for the
 * trouble is everything they came for being unavailable. The switch stays, and
 * [me.rerere.locallm.litert.LitertLmEmbedder.load] keeps a bad turn of it from costing the feature -
 * a non-CPU open that fails is retried on the CPU - but this is what happens when nobody has turned
 * it.
 *
 * Nothing is given up by it. For text the two are within noise here (~57 ms on the CPU against
 * ~63 ms on the GPU), and the vision tower - the one part that would have gained - is pinned to the
 * CPU for exactly the same reason, because `visionBackend` is built into the same graph.
 */
object EmbeddingAcceleratorRules {

    /**
     * Whether to use the GPU, given what the user chose.
     *
     * `null` is "not chosen", and the answer is no. A boolean is a decision, and is never
     * second-guessed: a user who turns it on gets the GPU attempt, and the retry in the loader is
     * what stands between that attempt failing and semantic search being gone.
     */
    fun useGpu(explicit: Boolean?): Boolean = explicit == true
}
