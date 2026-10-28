package me.rerere.rikkahub.data.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批 4 — 任务会话过滤。这里的错误有两种形态：任务会话漏进主列表（用户又分不清了），
 * 或者普通对话被误伤（用户找不到自己的聊天）。两边都要钉住。
 */
class MissionConversationFilterTest {

    private data class Conv(val id: String, val title: String = "")

    private val hidden = setOf("m-1", "m-2")

    @Test
    fun `conversation ids skip nulls and blanks`() {
        val ids = MissionConversationFilter.conversationIds(listOf("m-1", null, "", "  ", "m-2"))
        assertEquals(setOf("m-1", "m-2"), ids)
    }

    @Test
    fun `mission conversations are hidden when the switch is off`() {
        assertFalse(MissionConversationFilter.isVisible("m-1", hidden, showMissionConversations = false))
        assertTrue(MissionConversationFilter.isVisible("chat-1", hidden, showMissionConversations = false))
        assertTrue("没有绑定任务的会话永远可见", MissionConversationFilter.isVisible(null, hidden, false))
    }

    @Test
    fun `switch on restores everything`() {
        assertTrue(MissionConversationFilter.isVisible("m-1", hidden, showMissionConversations = true))
    }

    @Test
    fun `visible keeps order and drops only mission rows`() {
        val page = listOf(Conv("chat-1"), Conv("m-1"), Conv("chat-2"), Conv("m-2"))
        val shown = MissionConversationFilter.visible(
            items = page,
            hiddenIds = hidden,
            showMissionConversations = false,
            idOf = { it.id },
        )
        assertEquals(listOf("chat-1", "chat-2"), shown.map { it.id })
    }

    @Test
    fun `empty hidden set is a no-op even with the switch off`() {
        val page = listOf(Conv("chat-1"), Conv("chat-2"))
        assertEquals(
            page,
            MissionConversationFilter.visible(page, emptySet(), showMissionConversations = false) { it.id },
        )
    }

    @Test
    fun `active count only counts ACTIVE missions`() {
        val statuses = listOf(
            MissionStatus.ACTIVE.name,
            MissionStatus.REVIEW.name,
            MissionStatus.ACTIVE.name,
            MissionStatus.DONE.name,
            MissionStatus.BLOCKED.name,
            MissionStatus.ARCHIVED.name,
        )
        assertEquals(2, MissionConversationFilter.activeCount(statuses))
        assertEquals(0, MissionConversationFilter.activeCount(emptyList()))
    }
}
