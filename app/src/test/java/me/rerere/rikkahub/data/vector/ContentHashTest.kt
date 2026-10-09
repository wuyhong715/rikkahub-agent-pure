package me.rerere.rikkahub.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContentHashTest {

    /**
     * The published FNV-1a 64-bit vectors. Worth pinning rather than only testing
     * self-consistency: if the offset basis or the prime is mistyped, every hash is still
     * deterministic and self-consistent, and the only symptom would be an index that re-embeds
     * itself after every app upgrade - a bug that costs real battery and is nearly invisible.
     */
    @Test
    fun `matches the published FNV-1a 64 vectors`() {
        assertEquals(0xcbf29ce484222325UL.toLong(), ContentHash.of(""))
        assertEquals(0xaf63dc4c8601ec8cUL.toLong(), ContentHash.of("a"))
        assertEquals(0xe71fa2190541574bUL.toLong(), ContentHash.of("abc"))
    }

    @Test
    fun `hashes non-ascii text by its utf-8 bytes`() {
        // "猫" is E7 8C AB in UTF-8. Byte-level hashing is what makes the fingerprint depend on
        // the actual bytes that get embedded, not on a JVM's idea of characters.
        assertEquals(0x2798b01b6cf1574fUL.toLong(), ContentHash.of("猫"))
    }

    @Test
    fun `the same text always hashes the same`() {
        assertEquals(ContentHash.of("deploy the release"), ContentHash.of("deploy the release"))
    }

    @Test
    fun `a one-character edit changes the hash`() {
        assertNotEquals(ContentHash.of("deploy the release"), ContentHash.of("deploy the release."))
    }

    @Test
    fun `fold is order sensitive`() {
        // Two documents whose chunks are the same but rearranged are different documents; a fold
        // that ignored order would skip re-embedding after a reorder.
        assertNotEquals(
            ContentHash.fold(listOf(1L, 2L)),
            ContentHash.fold(listOf(2L, 1L)),
        )
    }

    @Test
    fun `fold of nothing is the offset basis`() {
        assertEquals(ContentHash.of(""), ContentHash.fold(emptyList()))
    }

    @Test
    fun `fold changes when any member changes`() {
        assertNotEquals(
            ContentHash.fold(listOf(1L, 2L, 3L)),
            ContentHash.fold(listOf(1L, 9L, 3L)),
        )
    }
}
