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
    fun `open count covers every non-terminal status`() {
        val statuses = listOf(
            MissionStatus.ACTIVE.name,
            MissionStatus.REVIEW.name,
            MissionStatus.ACTIVE.name,
            MissionStatus.DONE.name,
            MissionStatus.BLOCKED.name,
            MissionStatus.ARCHIVED.name,
        )
        // 旧口径（只数 ACTIVE）会给出 2，把「等待验收 / 卡住等人」两件事吞掉。
        assertEquals(4, MissionConversationFilter.openCount(statuses))
        assertEquals(0, MissionConversationFilter.openCount(emptyList()))
    }

    @Test
    fun `needs user count is BLOCKED plus REVIEW`() {
        val statuses = listOf(
            MissionStatus.ACTIVE.name,
            MissionStatus.REVIEW.name,
            MissionStatus.BLOCKED.name,
            MissionStatus.BLOCKED.name,
            MissionStatus.DONE.name,
            MissionStatus.ARCHIVED.name,
        )
        assertEquals(3, MissionConversationFilter.needsUserCount(statuses))
        assertEquals(0, MissionConversationFilter.needsUserCount(emptyList()))
    }

    @Test
    fun `a mission blocked before it ever ran still shows in the badge`() {
        // 用户在创建时被审批拦下：没有 ACTIVE 过，旧口径角标为 0。
        val statuses = listOf(MissionStatus.BLOCKED.name)
        assertEquals(1, MissionConversationFilter.openCount(statuses))
        assertEquals(1, MissionConversationFilter.needsUserCount(statuses))
    }

    @Test
    fun `needs user count never exceeds the open count`() {
        val statuses = MissionStatus.entries.map { it.name }
        assertTrue(
            MissionConversationFilter.needsUserCount(statuses) <=
                MissionConversationFilter.openCount(statuses)
        )
    }

    @Test
    fun `unknown status strings are ignored instead of throwing`() {
        val statuses = listOf("PAUSED", MissionStatus.ACTIVE.name, "")
        assertEquals(1, MissionConversationFilter.openCount(statuses))
        assertEquals(0, MissionConversationFilter.needsUserCount(statuses))
    }
}
