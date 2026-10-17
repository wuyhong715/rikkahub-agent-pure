package me.rerere.rikkahub.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexSyncRulesTest {

    private fun chunk(index: Int, text: String) =
        TextChunk(index = index, text = text, contentHash = ContentHash.of(text))

    private fun stored(id: Long, index: Int, contentHash: Long, modelId: String = MODEL) =
        StoredChunk(id = id, index = index, contentHash = contentHash, modelId = modelId)

    @Test
    fun `an untouched document is a no-op`() {
        val fresh = listOf(chunk(0, "alpha"), chunk(1, "beta"))
        val existing = fresh.mapIndexed { i, c -> stored(i + 1L, c.index, c.contentHash) }

        val plan = IndexSyncRules.plan(existing, fresh, MODEL)

        assertTrue(plan is SyncPlan.Unchanged)
        assertTrue(IndexSyncRules.isNoOp(plan))
    }

    @Test
    fun `editing one chunk re-embeds only that chunk`() {
        val existing = listOf(
            stored(10, 0, ContentHash.of("alpha")),
            stored(11, 1, ContentHash.of("beta")),
            stored(12, 2, ContentHash.of("gamma")),
        )
        val fresh = listOf(chunk(0, "alpha"), chunk(1, "beta edited"), chunk(2, "gamma"))

        val plan = IndexSyncRules.plan(existing, fresh, MODEL)

        assertTrue(plan is SyncPlan.Replace)
        plan as SyncPlan.Replace
        assertEquals(mapOf(0 to 10L, 2 to 12L), plan.reuse)
        assertEquals(3, plan.chunks.size)
        assertFalse(IndexSyncRules.needsNoEmbedding(plan))
    }

    @Test
    fun `a document that only lost its last chunk needs no embedding at all`() {
        val existing = listOf(
            stored(10, 0, ContentHash.of("alpha")),
            stored(11, 1, ContentHash.of("beta")),
        )
        val fresh = listOf(chunk(0, "alpha"), chunk(1, "beta"))

        // One chunk fewer in the fresh revision - the document lost its tail. The fold differs,
        // so it is a Replace - but every surviving chunk is reusable, so the model is never
        // called: the rows are rewritten from the vectors already on disk.
        val plan = IndexSyncRules.plan(existing, fresh.take(1), MODEL)

        assertTrue(plan is SyncPlan.Replace)
        plan as SyncPlan.Replace
        assertEquals(1, plan.reuse.size)
        assertEquals(1, plan.chunks.size)
        assertTrue(IndexSyncRules.needsNoEmbedding(plan))
    }

    @Test
    fun `a reordered document is not treated as unchanged`() {
        val a = "alpha"
        val b = "beta"
        val existing = listOf(stored(10, 0, ContentHash.of(a)), stored(11, 1, ContentHash.of(b)))
        val fresh = listOf(chunk(0, b), chunk(1, a))

        val plan = IndexSyncRules.plan(existing, fresh, MODEL)

        assertTrue(plan is SyncPlan.Replace)
        plan as SyncPlan.Replace
        // Position matters: the chunk now sitting at index 0 holds different text than before,
        // so nothing may be reused.
        assertTrue(plan.reuse.isEmpty())
    }

    @Test
    fun `a deleted document plans an empty replacement`() {
        val existing = listOf(stored(10, 0, ContentHash.of("alpha")))

        val plan = IndexSyncRules.plan(existing, emptyList(), MODEL)

        assertTrue(plan is SyncPlan.Replace)
        plan as SyncPlan.Replace
        assertEquals(0, plan.chunks.size)
        assertFalse(IndexSyncRules.isNoOp(plan))
        // Nothing to embed, but the rows still have to go: "no embedding" is not "no work".
        assertFalse(IndexSyncRules.needsNoEmbedding(plan))
    }

    @Test
    fun `a document with no stored chunks yet is embedded in full`() {
        val plan = IndexSyncRules.plan(emptyList(), listOf(chunk(0, "alpha")), MODEL)

        assertTrue(plan is SyncPlan.Replace)
        plan as SyncPlan.Replace
        assertTrue(plan.reuse.isEmpty())
        assertFalse(IndexSyncRules.needsNoEmbedding(plan))
    }

    @Test
    fun `a model swap re-embeds everything and reuses nothing`() {
        // Same content, different model: the fold would call this Unchanged, but the stored
        // vectors live in another model's space. This is the case that made "switch model and
        // rebuild" a no-op: every document planned Unchanged, the old rows stayed, and a search
        // under the new model found nothing - forever.
        val existing = listOf(
            stored(10, 0, ContentHash.of("alpha"), modelId = "old-model#cpu"),
            stored(11, 1, ContentHash.of("beta"), modelId = "old-model#cpu"),
        )
        val fresh = listOf(chunk(0, "alpha"), chunk(1, "beta"))

        val plan = IndexSyncRules.plan(existing, fresh, MODEL)

        assertTrue(plan is SyncPlan.Replace)
        plan as SyncPlan.Replace
        // A vector from another model is not an asset: reuse is empty even though every chunk
        // matches by content and position.
        assertTrue(plan.reuse.isEmpty())
        assertEquals(2, plan.chunks.size)
    }

    private companion object {
        const val MODEL = "embeddinggemma-2-text-vision-440m.litertlm#cpu"
    }
}
