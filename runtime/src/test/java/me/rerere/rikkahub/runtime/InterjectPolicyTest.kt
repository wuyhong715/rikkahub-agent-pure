package me.rerere.rikkahub.runtime

import me.rerere.ai.ui.MessageInterject
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterjectPolicyTest {

    // ---- plainTextOf -------------------------------------------------------

    @Test
    fun `single text part is trimmed`() {
        assertEquals("把方案 B 换掉", InterjectPolicy.plainTextOf(listOf(UIMessagePart.Text("  把方案 B 换掉 "))))
    }

    @Test
    fun `multiple text parts are joined in order`() {
        val text = InterjectPolicy.plainTextOf(
            listOf(UIMessagePart.Text("第一句"), UIMessagePart.Text("第二句")),
        )
        assertEquals("第一句\n第二句", text)
    }

    @Test
    fun `attachment messages are not interjections`() {
        assertNull(
            InterjectPolicy.plainTextOf(
                listOf(
                    UIMessagePart.Text("看图"),
                    UIMessagePart.Image(url = "file:///tmp/a.png"),
                ),
            ),
        )
    }

    @Test
    fun `blank input is not an interjection`() {
        assertNull(InterjectPolicy.plainTextOf(listOf(UIMessagePart.Text("   \n "))))
        assertNull(InterjectPolicy.plainTextOf(emptyList()))
    }

    // ---- canInterject ------------------------------------------------------

    @Test
    fun `needs a live generating session`() {
        assertTrue(InterjectPolicy.canInterject(hasSession = true, isGenerating = true, plainText = "改用 B"))
        assertFalse(InterjectPolicy.canInterject(hasSession = false, isGenerating = true, plainText = "改用 B"))
        assertFalse(InterjectPolicy.canInterject(hasSession = true, isGenerating = false, plainText = "改用 B"))
        assertFalse(InterjectPolicy.canInterject(hasSession = true, isGenerating = true, plainText = null))
    }

    // ---- injectionText -----------------------------------------------------

    @Test
    fun `mission wording keeps the original directive`() {
        val text = InterjectPolicy.injectionText("改用 B 方案", inMission = true)
        assertTrue(text.startsWith("[用户插话 · 立即生效]"))
        assertTrue(text.contains("改用 B 方案"))
        assertTrue(text.contains("从下一步开始按这个调整方向"))
    }

    @Test
    fun `chat wording forbids double-counting the transcript copy`() {
        val text = InterjectPolicy.injectionText("  改用 B 方案  ", inMission = false)
        assertTrue(text.startsWith("[用户插话]"))
        assertTrue(text.contains("改用 B 方案"))
        assertFalse(text.contains("  改用 B 方案  ")) // trimmed user text, not the padded original
        assertTrue(text.contains("不是新增要求"))
        assertTrue(text.contains("从下一步开始按这个调整"))
    }

    // ---- stage -------------------------------------------------------------

    @Test
    fun `mission round promises the next step`() {
        assertEquals(MessageInterject.NEXT_STEP, InterjectPolicy.stage(inMission = true, hasUnfinishedToolWork = false))
    }

    @Test
    fun `pending tool work promises the next step`() {
        assertEquals(MessageInterject.NEXT_STEP, InterjectPolicy.stage(inMission = false, hasUnfinishedToolWork = true))
    }

    @Test
    fun `plain chat answer only promises after the turn`() {
        assertEquals(MessageInterject.AFTER_TURN, InterjectPolicy.stage(inMission = false, hasUnfinishedToolWork = false))
    }
}
