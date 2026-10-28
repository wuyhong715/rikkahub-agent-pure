package me.rerere.rikkahub.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The section registry replaced a fixed-arity builder. Two things must stay true for that to be
 * safe: the rendered bytes must not drift, and adding a section must be a data operation rather
 * than an edit to `SystemPromptBuilder`.
 *
 * `SystemPromptBuilderTest` keeps pinning the byte-identical cases; this file pins the registry
 * itself, including the one deliberate difference (a blank tool prompt no longer leaves an empty
 * line in the stable prefix).
 */
class PromptSectionTest {

    private val builder = SystemPromptBuilder()

    @Test
    fun `sections are ordered by order then name, blanks dropped`() {
        val (stable, volatile) = PromptAssembler.assemble(
            listOf(
                PromptSection("c", 300, PromptStability.STABLE, "third"),
                PromptSection("a", 100, PromptStability.STABLE, "first"),
                PromptSection("b", 100, PromptStability.STABLE, "second-same-order"),
                PromptSection("blank", 200, PromptStability.STABLE, "   "),
                PromptSection("v", 100, PromptStability.VOLATILE, "vol"),
            )
        )
        // Equal orders fall back to name, so "a" precedes "b" — deterministic across runs.
        assertEquals("first\nsecond-same-order\nthird", stable)
        assertEquals("vol", volatile)
        assertFalse(stable.contains("vol"))
    }

    @Test
    fun `extra sections land in their own block at their own order`() {
        val (stable, volatile) = builder.buildSections(
            assistantPrompt = "A",
            memoryPrompt = "M",
            extraSections = listOf(
                PromptSection("missions", 300, PromptStability.STABLE, "MISSION RULES"),
                PromptSection("clock", 50, PromptStability.VOLATILE, "NOW 12:00"),
            ),
        )
        assertTrue(stable.indexOf("MISSION RULES") > stable.indexOf("A"))
        assertTrue(volatile.startsWith("NOW 12:00"))
        assertFalse(stable.contains("NOW"))
        assertFalse(volatile.contains("MISSION"))
    }

    @Test
    fun `a blank tool prompt no longer leaves an empty line in the stable prefix`() {
        // Tool.systemPrompt() defaults to "" — most tools have no documentation — so this used to
        // put one stray blank line per such tool inside the cached prefix.
        val (stable, _) = builder.buildSections(
            assistantPrompt = "A",
            toolPrompts = listOf("", "tool docs", ""),
        )
        assertFalse("stable block must not contain a blank line", Regex("\\n\\s*\\n").containsMatchIn(stable))
        assertTrue(stable.contains("tool docs"))
        assertTrue(stable.contains(SystemPromptBuilder.TOOL_COST_GUIDANCE))
    }

    @Test
    fun `the stable block is byte-identical when only volatile inputs change`() {
        fun stable(mem: String, chats: String) = builder.buildSections(
            assistantPrompt = "A",
            systemInjectionPrompt = "RULES",
            toolPrompts = listOf("tool docs"),
            memoryPrompt = mem,
            recentChatsPrompt = chats,
            extraSections = listOf(PromptSection("missions", 300, PromptStability.STABLE, "MISSION RULES")),
        ).first

        assertEquals(stable("mem v1", "chats v1"), stable("mem v2", "chats v2"))
    }

    @Test
    fun `optional drops blank contributions`() {
        assertNull(PromptAssembler.optional("x", 1, PromptStability.STABLE, null))
        assertNull(PromptAssembler.optional("x", 1, PromptStability.STABLE, "  "))
        assertEquals("hi", PromptAssembler.optional("x", 1, PromptStability.STABLE, "hi")?.text)
    }

    @Test
    fun `feature sections share the ordering with built-ins deterministically`() {
        // Names are the only tie-breaker, so a feature section sharing an order with a built-in one
        // must still be ordered deterministically (and visibly) rather than silently reordered.
        val sections = builder.sections(
            assistantPrompt = "A",
            extraSections = listOf(PromptSection("zzz-feature", 100, PromptStability.STABLE, "FEATURE")),
        )
        val rendered = PromptAssembler.assemble(sections).first
        assertTrue(rendered.indexOf("A") < rendered.indexOf("FEATURE"))
    }
}
