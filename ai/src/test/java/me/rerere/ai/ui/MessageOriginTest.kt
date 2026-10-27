package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App 气泡的数据层契约：origin 走消息 JSON（不加 DB 迁移），`speaker` 从 origin 推导，
 * 且 **role 永远不变** —— 模型看到的仍是一条 user 消息。
 */
class MessageOriginTest {

    @Test
    fun `role decides speaker when origin is absent`() {
        assertEquals(MessageOrigin.USER, UIMessage.user("hi").speaker)
        assertEquals(MessageOrigin.ASSISTANT, UIMessage.assistant("hello").speaker)
        assertFalse(UIMessage.user("hi").speaker.isApp)
    }

    @Test
    fun `origin overrides the role derived speaker`() {
        val brief = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("任务简报")),
            origin = MessageOrigin.APP_BRIEF,
        )
        assertEquals(MessageRole.USER, brief.role) // 铁律：不改 role
        assertEquals(MessageOrigin.APP_BRIEF, brief.speaker)
        assertTrue(brief.speaker.isApp)
    }

    @Test
    fun `only user and assistant are not app speakers`() {
        assertFalse(MessageOrigin.USER.isApp)
        assertFalse(MessageOrigin.ASSISTANT.isApp)
        listOf(
            MessageOrigin.APP_BRIEF,
            MessageOrigin.APP_HANDOFF,
            MessageOrigin.APP_REWORK,
            MessageOrigin.APP_NOTICE,
            MessageOrigin.APP_REPORT,
            MessageOrigin.SUBAGENT_SUMMARY,
        ).forEach { assertTrue("$it should be an app speaker", it.isApp) }
    }

    @Test
    fun `origin survives the message json`() {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("任务：Moxw 全局插话")),
            origin = MessageOrigin.APP_BRIEF,
        )
        val encoded = Json.encodeToString(message)
        assertTrue(encoded.contains("APP_BRIEF"))

        val decoded = Json.decodeFromString<UIMessage>(encoded)
        assertEquals(MessageOrigin.APP_BRIEF, decoded.origin)
        assertEquals(MessageRole.USER, decoded.role)
        assertEquals(MessageOrigin.APP_BRIEF, decoded.speaker)
    }

    @Test
    fun `legacy message without origin still loads`() {
        // 旧数据的 JSON 里没有 origin 字段：解码后 origin = null，身份按 role 推导。
        val legacy = Json.encodeToString(UIMessage.user("旧消息"))
        assertFalse(legacy.contains("origin"))

        val decoded = Json.decodeFromString<UIMessage>(legacy)
        assertNull(decoded.origin)
        assertEquals(MessageOrigin.USER, decoded.speaker)
    }
}
