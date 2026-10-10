package me.rerere.rikkahub.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSearchRulesTest {

    private fun candidate(conversation: String, message: String, chars: Int = 100) =
        ConversationSearchRules.Candidate(conversation, message, chars)

    @Test
    fun `a document key carries the conversation and can be read back`() {
        val key = ConversationSearchRules.docKeyOf("conv-1", "msg-9")
        assertEquals("conv-1|msg-9", key)
        assertEquals("conv-1", ConversationSearchRules.conversationIdOf(key))
    }

    @Test
    fun `a key without a separator has no conversation`() {
        assertNull(ConversationSearchRules.conversationIdOf("conv-1"))
        assertNull(ConversationSearchRules.conversationIdOf(""))
    }

    @Test
    fun `trivial messages are not worth embedding`() {
        assertFalse(ConversationSearchRules.isIndexable("ok"))
        assertFalse(ConversationSearchRules.isIndexable("   "))
        assertFalse(ConversationSearchRules.isIndexable("thanks!"))
        assertTrue(ConversationSearchRules.isIndexable("that is worth remembering"))
    }

    @Test
    fun `the estimate is capped like the index cap`() {
        assertEquals(500, ConversationSearchRules.estimatedChars(500))
        assertEquals(
            ConversationSearchRules.MAX_MESSAGE_CHARS,
            ConversationSearchRules.estimatedChars(ConversationSearchRules.MAX_MESSAGE_CHARS * 4),
        )
    }

    @Test
    fun `a round reads messages the index has never seen first`() {
        val candidates = listOf(
            candidate("c", "2"),
            candidate("c", "1"),
            candidate("c", "3"),
        )
        val round = ConversationSearchRules.planRound(
            candidates,
            indexed = setOf(ConversationSearchRules.docKeyOf("c", "1")),
        )
        assertEquals(
            listOf("c|2", "c|3", "c|1"),
            round.take.map { it.docKey },
        )
    }

    @Test
    fun `a round stops at its budget and defers the rest`() {
        // Lengths stay under MAX_MESSAGE_CHARS because that is the only kind of candidate the
        // coordinator can produce - it truncates a message before the candidate is built. The
        // budget therefore bites on messages that are long *for a message*, which is the point:
        // 3k characters each is still three-ish chunks of embedding work.
        val candidates = (1..5).map { candidate("c", "$it", chars = 3_000) }
        val round = ConversationSearchRules.planRound(
            candidates,
            indexed = emptySet(),
            budgetChars = 6_000,
        )
        assertEquals(2, round.take.size)
        assertEquals(3, round.deferred)
    }

    @Test
    fun `the message count bounds a round of tiny messages`() {
        val candidates = (1..10).map { candidate("c", "$it", chars = 20) }
        val round = ConversationSearchRules.planRound(candidates, indexed = emptySet(), maxMessages = 4)
        assertEquals(4, round.take.size)
        assertEquals(6, round.deferred)
    }

    @Test
    fun `a message over the cap is reported rather than read`() {
        val huge = candidate("c", "big", chars = ConversationSearchRules.MAX_MESSAGE_CHARS + 1)
        val round = ConversationSearchRules.planRound(listOf(huge), indexed = emptySet())
        assertTrue(round.take.isEmpty())
        assertEquals(listOf("c|big"), round.tooBig)
    }

    @Test
    fun `a full walk forgets only what it no longer saw`() {
        val indexed = listOf("c|1", "c|2", "d|1")
        val seen = setOf("c|1", "d|1")
        assertEquals(listOf("c|2"), ConversationSearchRules.toForget(indexed, seen))
    }

    @Test
    fun `a scoped sync never forgets another conversation`() {
        // The bug this pins: the per-turn trigger reads one conversation, so without the scope it
        // would see every other conversation as deleted and wipe the history on the first turn.
        val indexed = listOf("c|1", "c|2", "other|1", "other|2")
        val seen = setOf("c|1")
        assertEquals(
            listOf("c|2"),
            ConversationSearchRules.toForgetIn("c", indexed, seen),
        )
    }

    @Test
    fun `a scoped sync still forgets a message deleted inside its conversation`() {
        val indexed = listOf("c|1", "c|2")
        val seen = setOf("c|1", "c|2")
        assertEquals(listOf("c|2"), ConversationSearchRules.toForgetIn("c", listOf("c|1", "c|2"), seen - "c|2"))
    }

    @Test
    fun `only the named conversation is eligible for a scoped sync`() {
        val indexed = listOf("c|1", "d|1")
        assertEquals(emptyList<String>(), ConversationSearchRules.toForgetIn("c", indexed, seen = setOf("c|1")))
    }
}
