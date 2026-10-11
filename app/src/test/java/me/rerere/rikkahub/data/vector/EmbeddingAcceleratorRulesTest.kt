package me.rerere.rikkahub.data.vector

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The default accelerator.
 *
 * Worth its own test because the default is invisible until it is wrong: the switch shows the
 * resolved value, so nothing on screen distinguishes "the user chose the CPU" from "nobody chose".
 * A regression here is not a slower index, either - on the hardware this was measured on, asking
 * for the GPU means `initialize()` fails and there is no index at all - so the default has to be
 * asserted rather than assumed.
 */
class EmbeddingAcceleratorRulesTest {

    @Test
    fun `an unanswered switch means the CPU`() {
        assertTrue(!EmbeddingAcceleratorRules.useGpu(null))
    }

    @Test
    fun `an answered switch is never second-guessed`() {
        // Both directions: a user who turned it on gets the attempt, and one who turned it off
        // keeps that. What stands between the attempt and a dead feature is the downgrade in
        // `LitertLmEmbedder.load`, not this rule.
        assertTrue(EmbeddingAcceleratorRules.useGpu(true))
        assertTrue(!EmbeddingAcceleratorRules.useGpu(false))
    }
}
