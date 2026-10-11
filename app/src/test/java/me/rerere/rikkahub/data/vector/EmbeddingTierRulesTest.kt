package me.rerere.rikkahub.data.vector

import me.rerere.llamacpp.EmbeddingFocus
import me.rerere.llamacpp.EmbeddingTier
import me.rerere.llamacpp.LlamaCppEmbeddingCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-adaptive half of the model choice.
 *
 * Every case here is a phone nobody has to own: the point of the rule being pure is that the
 * interesting devices - 3 GB of RAM with plenty of storage, a flagship with a full disk - can be
 * asked about directly. Byte sizes come from the catalogue, so a case that says "700 MB is enough
 * for the standard rung" is arithmetic against the real file, not a number typed twice.
 */
class EmbeddingTierRulesTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1_000_000L

    private fun phone(ramGb: Int, freeMb: Long, chinese: Boolean = true) = EmbeddingDeviceFacts(
        totalRamBytes = ramGb * gb,
        availableDiskBytes = freeMb * mb,
        prefersChinese = chinese,
    )

    private fun fileOf(tier: EmbeddingTier, focus: EmbeddingFocus): String =
        LlamaCppEmbeddingCatalog.forSlot(tier, focus)!!.entry.file

    @Test
    fun `a phone we know nothing about is offered the light rung`() {
        val recommendation = EmbeddingTierRules.recommendedSlot(phone(ramGb = 3, freeMb = 2000))
        assertEquals(EmbeddingTier.LIGHT, recommendation!!.tier)
        assertEquals(fileOf(EmbeddingTier.LIGHT, EmbeddingFocus.CHINESE), recommendation.entry.file)
    }

    @Test
    fun `the language setting picks the lane, not the rung`() {
        val english = EmbeddingTierRules.recommendedSlot(phone(3, 2000, chinese = false))!!
        assertEquals(EmbeddingTier.LIGHT, english.tier)
        assertEquals(fileOf(EmbeddingTier.LIGHT, EmbeddingFocus.MULTILINGUAL), english.entry.file)
        assertEquals(EmbeddingFocus.MULTILINGUAL, EmbeddingTierRules.focusOf(phone(3, 2000, false)))
        assertEquals(EmbeddingFocus.CHINESE, EmbeddingTierRules.focusOf(phone(3, 2000, true)))
    }

    @Test
    fun `a phone bought in the last few years is offered the standard rung`() {
        val recommendation = EmbeddingTierRules.recommendedSlot(phone(ramGb = 6, freeMb = 1000))!!
        assertEquals(EmbeddingTier.STANDARD, recommendation.tier)
        assertEquals(fileOf(EmbeddingTier.STANDARD, EmbeddingFocus.CHINESE), recommendation.entry.file)
    }

    @Test
    fun `the top rung takes 8 GB of RAM`() {
        val atEight = EmbeddingTierRules.recommendedSlot(phone(ramGb = 8, freeMb = 5000))!!
        assertEquals(EmbeddingTier.TOP, atEight.tier)

        // The same disk on a 6 GB phone stops one rung lower: the RAM gate is a policy about what
        // else the phone is running, and it is checked before the disk.
        val atSix = EmbeddingTierRules.recommendedSlot(phone(ramGb = 6, freeMb = 5000))!!
        assertEquals(EmbeddingTier.STANDARD, atSix.tier)
    }

    @Test
    fun `a flagship with a full disk is stepped down rather than refused`() {
        // 8 GB of RAM but 700 MB free: enough for the standard rung's 606 MB, not for the top
        // rung's 1.2 GB.
        val recommendation = EmbeddingTierRules.recommendedSlot(phone(ramGb = 8, freeMb = 700))!!
        assertEquals(EmbeddingTier.STANDARD, recommendation.tier)
    }

    @Test
    fun `a phone with almost nothing free is told nothing`() {
        // Null is the answer the picker has to be able to render: a 200 MB suggestion on a phone
        // with 200 MB free would fill the disk the index needs.
        assertNull(EmbeddingTierRules.recommendedSlot(phone(ramGb = 12, freeMb = 200)))
    }

    @Test
    fun `an English phone with no room is not offered a Chinese model`() {
        // The lane is not negotiable downwards: bge-base-zh is 110 MB and would fit, and it would
        // also return nothing useful for an English corpus.
        assertNull(EmbeddingTierRules.recommendedSlot(phone(ramGb = 3, freeMb = 100, chinese = false)))
    }

    @Test
    fun `the rungs only go up as the phone gets bigger`() {
        val ranks = listOf(2, 4, 6, 8, 12).map {
            EmbeddingTierRules.recommendedSlot(phone(ramGb = it, freeMb = 5000))!!.tier.rank
        }
        assertEquals(ranks.sorted(), ranks)
        assertEquals(EmbeddingTier.LIGHT.rank, ranks.first())
        assertEquals(EmbeddingTier.TOP.rank, ranks.last())
    }

    @Test
    fun `a model needs three times its own size in free space`() {
        val entry =
            LlamaCppEmbeddingCatalog.forSlot(EmbeddingTier.LIGHT, EmbeddingFocus.CHINESE)!!.entry
        val exact = entry.sizeBytes * EmbeddingTierRules.DISK_HEADROOM

        assertTrue(EmbeddingTierRules.fits(entry, phone(8, 0).copy(availableDiskBytes = exact)))
        assertTrue(!EmbeddingTierRules.fits(entry, phone(8, 0).copy(availableDiskBytes = exact - 1)))
    }

    @Test
    fun `the suggestion is always one of the models the picker offers`() {
        val offered = LlamaCppEmbeddingCatalog.PICKER_ORDER.map { it.file }.toSet()
        val phones = listOf(true, false).flatMap { chinese ->
            listOf(2, 4, 6, 8, 12).flatMap { ram ->
                listOf(100L, 600L, 2000L, 20000L).map { free -> phone(ram, free, chinese) }
            }
        }

        val suggestions = phones.mapNotNull { EmbeddingTierRules.recommendedSlot(it) }
        assertTrue("no phone in the matrix got a suggestion", suggestions.isNotEmpty())
        suggestions.forEach { suggestion ->
            assertTrue(
                "${suggestion.entry.file} is not in the picker order",
                suggestion.entry.file in offered,
            )
            assertEquals(LlamaCppEmbeddingCatalog.forSlot(suggestion.tier, suggestion.focus), suggestion)
        }
    }

    @Test
    fun `the light rung is the floor even when it does not fit`() {
        // highestFittingTier answers about RAM and about the rungs above; whether the light entry
        // itself fits on the disk is what recommendedSlot adds. Keeping the two apart is what lets
        // the picker show the light rung as "too big for this disk" instead of hiding it.
        val tiny = phone(ramGb = 2, freeMb = 10)
        assertEquals(EmbeddingTier.LIGHT, EmbeddingTierRules.highestFittingTier(tiny))
        assertNull(EmbeddingTierRules.recommendedSlot(tiny))
    }
}
