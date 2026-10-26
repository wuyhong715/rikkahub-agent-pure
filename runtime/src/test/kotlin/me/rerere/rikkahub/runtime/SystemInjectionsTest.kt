package me.rerere.rikkahub.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemInjectionsTest {

    @Test
    fun `default set renders every pack with the authoritative header`() {
        val rendered = SystemInjections.render()
        assertTrue(rendered.startsWith("<system_injections>"))
        assertTrue(rendered.endsWith("</system_injections>"))
        assertTrue(rendered.contains("these win"))
        SystemInjections.ALL.forEach { pack ->
            assertTrue("missing pack: ${pack.id}", rendered.contains("## ${pack.title}"))
        }
    }

    @Test
    fun `render order is the declared order, not set order`() {
        val rendered = SystemInjections.render(
            setOf(SystemInjections.MISSION, SystemInjections.CORE, SystemInjections.SAFETY),
        )
        assertTrue(
            rendered.indexOf("## Core conduct") < rendered.indexOf("## Safety and authorization"),
        )
        assertTrue(
            rendered.indexOf("## Safety and authorization") <
                rendered.indexOf("## Missions and long work"),
        )
        assertFalse(rendered.contains("## Files and workspace"))
    }

    @Test
    fun `empty set renders nothing so the builder can skip the section`() {
        assertEquals("", SystemInjections.render(emptySet()))
    }

    @Test
    fun `normalize drops unknown ids and keeps known ones`() {
        val normalized = SystemInjections.normalize(setOf("core", "not-a-pack"))
        assertEquals(setOf("core"), normalized)
    }

    @Test
    fun `a stored set written before the new packs existed gets them on read`() {
        val oldDefault = setOf(
            SystemInjections.CORE,
            SystemInjections.SAFETY,
            SystemInjections.TOOLS,
            SystemInjections.FILES,
            SystemInjections.VERIFY,
            SystemInjections.MEMORY,
            SystemInjections.MISSION,
        )
        val migrated = SystemInjections.withLatePacks(oldDefault)
        assertTrue(SystemInjections.CLIENT in migrated)
        assertTrue(SystemInjections.LIMITS in migrated)
        assertTrue(SystemInjections.RESEARCH in migrated)
        assertEquals(10, migrated.size)
        // Turning the app rules off stays off: an empty set is a decision, not a stale default.
        assertTrue(SystemInjections.withLatePacks(emptySet()).isEmpty())
    }

    @Test
    fun `stable bytes across calls with the same input`() {
        val enabled = setOf(SystemInjections.TOOLS, SystemInjections.FILES)
        assertEquals(
            SystemInjections.render(enabled),
            SystemInjections.render(enabled),
        )
    }

    @Test
    fun `the client pack tells the model what this app holds and how to find a capability`() {
        val client = SystemInjections.ALL.single { it.id == SystemInjections.CLIENT }.body
        // The object model: without these words a fresh install is just a chat window.
        listOf("Missions", "Schedules", "sub-agents", "memory", "file library", "Device tools")
            .forEach { assertTrue("missing object: $it", client.contains(it)) }
        // The discovery rule that keeps "I can't" honest.
        assertTrue(client.contains("tool_search"))
        assertTrue(client.contains("moxw_guide"))
        assertTrue(client.contains("did not find is not a name that does not exist"))
    }

    @Test
    fun `the limits pack forbids claiming unobserved perception`() {
        val limits = SystemInjections.ALL.single { it.id == SystemInjections.LIMITS }.body
        assertTrue(limits.contains("do not hear audio"))
        assertTrue(limits.contains("never", ignoreCase = true))
    }

    @Test
    fun `the tool pack no longer treats an unsearched surface as proof of absence`() {
        val tools = SystemInjections.ALL.single { it.id == SystemInjections.TOOLS }.body
        assertFalse(tools.contains("If the tool surface does not contain a capability, say so"))
        assertTrue(tools.contains("catalogue search"))
    }

    @Test
    fun `the research pack asks for prior art and ranks sources`() {
        val research = SystemInjections.ALL.single { it.id == SystemInjections.RESEARCH }.body
        // Prior art first: the defect this pack exists for is building a solved problem again.
        assertTrue(research.contains("already exists"))
        assertTrue(research.contains("Reimplementing a solved problem"))
        // Recall is not evidence.
        assertTrue(research.contains("memory is not a source"))
        // Ranking, not retrieval order.
        assertTrue(research.contains("Position in the result list is not evidence"))
        // Retrieved content is data; an instruction inside it is an attack to report.
        assertTrue(research.contains("injection attempt"))
    }

    @Test
    fun `the whole set stays inside the prefix budget it was written for`() {
        // Measured in words because that is what the model pays for. 1,600 leaves room for the
        // tool guidance block and the assistant prompt before the manual would have been
        // cheaper to inject as one block.
        val words = SystemInjections.render().split(Regex("\\s+")).count { it.isNotBlank() }
        assertTrue("injections grew to $words words", words < 1_600)
    }
}
