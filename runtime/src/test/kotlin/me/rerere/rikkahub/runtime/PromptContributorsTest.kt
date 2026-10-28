package me.rerere.rikkahub.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-level blocks have fixed places in the prompt. These tests pin the two properties that
 * matter when the remaining transformer-side block is moved over: the placement is declared once
 * (not implied by plumbing order), and adding the block through the builder cannot change a
 * single byte of what the pipeline sent before it was switched on.
 */
class PromptContributorsTest {

    private val builder = SystemPromptBuilder()

    @Test
    fun `the environment block sits between app rules and tool documentation`() {
        val (stable, _) = builder.buildSections(
            assistantPrompt = "A",
            systemInjectionPrompt = "RULES",
            appEnvironmentPrompt = "<app_environment>E</app_environment>",
            toolPrompts = listOf("tool docs"),
        )

        assertTrue(stable.indexOf("RULES") < stable.indexOf("<app_environment>"))
        assertTrue(stable.indexOf("<app_environment>") < stable.indexOf("tool docs"))
    }

    @Test
    fun `a blank environment keeps the prompt byte-identical`() {
        // This is what makes the migration incremental: every call site that does not pass the
        // block must keep sending exactly what it sent before.
        assertEquals(
            builder.buildSections(assistantPrompt = "A", systemInjectionPrompt = "I", toolPrompts = listOf("t")),
            builder.buildSections(
                assistantPrompt = "A",
                systemInjectionPrompt = "I",
                appEnvironmentPrompt = "",
                toolPrompts = listOf("t"),
            ),
        )
        assertEquals("A", builder.buildSections("A", appEnvironmentPrompt = "   ").first)
    }

    @Test
    fun `contribution metadata is declared, not implied`() {
        val env = requireNotNull(PromptContributors.appEnvironment("E"))
        assertEquals(PromptContributors.APP_ENVIRONMENT, env.name)
        assertEquals(PromptContributors.APP_ENVIRONMENT_ORDER, env.order)
        assertEquals(PromptStability.STABLE, env.stability)

        val policy = requireNotNull(PromptContributors.runtimePolicy("P"))
        assertEquals(PromptContributors.RUNTIME_POLICY_ORDER, policy.order)
        assertEquals(PromptStability.VOLATILE, policy.stability)

        assertNull(PromptContributors.appEnvironment(null))
        assertNull(PromptContributors.runtimePolicy(" "))
        assertEquals(1, PromptContributors.appBlocks("E", null).size)
    }

    @Test
    fun `the runtime policy still ends the volatile block`() {
        val (_, volatile) = builder.buildSections(
            assistantPrompt = "A",
            memoryPrompt = "M",
            recentChatsPrompt = "C",
            systemAddendum = "S",
            policyPrompt = "P",
        )
        assertTrue(volatile.endsWith("P"))
    }
}
