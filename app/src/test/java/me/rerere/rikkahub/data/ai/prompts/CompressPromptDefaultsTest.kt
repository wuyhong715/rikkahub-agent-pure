package me.rerere.rikkahub.data.ai.prompts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compaction prompt is persisted on every settings save, so shipping a better default is not
 * enough on its own — an existing install keeps whatever text was written the first time. The
 * upgrade must therefore tell "never touched" from "deliberately edited", and must leave an edited
 * prompt byte-for-byte alone.
 */
class CompressPromptDefaultsTest {

    @Test
    fun `untouched and blank prompts upgrade`() {
        assertEquals(DEFAULT_COMPRESS_PROMPT, CompressPromptDefaults.upgrade(LEGACY_COMPRESS_PROMPT))
        assertEquals(DEFAULT_COMPRESS_PROMPT, CompressPromptDefaults.upgrade(""))
        assertEquals(DEFAULT_COMPRESS_PROMPT, CompressPromptDefaults.upgrade("   \n  "))
    }

    @Test
    fun `a whitespace-reformatted shipped default still upgrades`() {
        val reformatted = LEGACY_COMPRESS_PROMPT.replace("\n", "\n\n").replace("  ", " ")
        assertEquals(DEFAULT_COMPRESS_PROMPT, CompressPromptDefaults.upgrade(reformatted))
    }

    @Test
    fun `an edited prompt is kept verbatim`() {
        val mine = "Summarize in bullet points, max 300 words."
        assertEquals(mine, CompressPromptDefaults.upgrade(mine))
        // Appending to a shipped default counts as an edit, not as untouched.
        val extended = LEGACY_COMPRESS_PROMPT + "\nAlso keep the deploy commands."
        assertNotEquals(DEFAULT_COMPRESS_PROMPT, CompressPromptDefaults.upgrade(extended))
        assertEquals(extended, CompressPromptDefaults.upgrade(extended))
    }

    @Test
    fun `upgrade is idempotent`() {
        assertEquals(DEFAULT_COMPRESS_PROMPT, CompressPromptDefaults.upgrade(DEFAULT_COMPRESS_PROMPT))
    }

    @Test
    fun `every placeholder the renderer substitutes is present`() {
        // ChatService.applyPlaceholders substitutes these by name; a default missing one silently
        // leaves the literal token in the prompt.
        for (placeholder in listOf("{target_tokens}", "{locale}", "{additional_context}", "{content}")) {
            assertTrue(
                "default compress prompt is missing $placeholder",
                DEFAULT_COMPRESS_PROMPT.contains(placeholder),
            )
        }
    }

    @Test
    fun `the handoff keeps what the tool ledger cannot carry`() {
        // Regression guard for the reason this prompt changed: Moxw already retains a deterministic
        // ledger of completed tool calls, so re-transcribing commands would duplicate it; the gaps
        // and the forward plan are the parts nothing else preserves.
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("do not re-transcribe commands"))
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("still do not know"))
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("forward plan"))
        assertFalse(DEFAULT_COMPRESS_PROMPT.contains("conversation compression assistant"))
    }
}
