package me.rerere.rikkahub.service.mission

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回声的幂等与「没有来源就不出声」。这里错了的两种后果：刷屏（同一状态反复推）和惊吓
 * （老任务突然开始往对话里插消息）。
 */
class MissionConversationEchoTest {

    private class Recorder {
        val sent = mutableListOf<Pair<String, String>>()
        suspend fun post(conversationId: String, text: String) {
            sent += conversationId to text
        }
    }

    private val sourceConversationId = "11111111-1111-1111-1111-111111111111"
    private val missionConversationId = "22222222-2222-2222-2222-222222222222"

    /**
     * limits_json 只写这一个键：`MissionLimits` 其余字段都有默认值，反序列化时自动补齐；
     * 老任务的行里压根没有这个键 —— 那正是「没有来源」的语义。
     */
    private fun mission(
        id: String = "m-1",
        title: String = "整理合同",
        sourceId: String? = sourceConversationId,
        artifacts: Int = 0,
    ) = MissionEntity(
        id = id,
        title = title,
        conversationId = missionConversationId,
        artifactCount = artifacts,
        limitsJson = sourceId?.let { "{\"sourceConversationId\":\"$it\"}" },
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `started is echoed once into the source conversation`() = runBlocking {
        val recorder = Recorder()
        val echo = MissionConversationEcho(recorder::post)
        val m = mission()

        echo.onStarted(m)
        echo.onStarted(m)

        assertEquals(1, recorder.sent.size)
        assertEquals(sourceConversationId, recorder.sent.single().first)
        assertTrue(recorder.sent.single().second.contains("已开始任务「整理合同」· 第 1 轮"))
    }

    @Test
    fun `each settled status is echoed once, and repeats are dropped`() = runBlocking {
        val recorder = Recorder()
        val echo = MissionConversationEcho(recorder::post)

        echo.onSettled(mission(artifacts = 3), MissionStatus.REVIEW, "还差一节没写完")
        echo.onSettled(mission(artifacts = 3), MissionStatus.REVIEW, "还差一节没写完")
        echo.onSettled(mission(artifacts = 3), MissionStatus.DONE, "已完成全部章节")
        echo.onSettled(mission(artifacts = 3), MissionStatus.DONE, "重复信号")

        assertEquals(2, recorder.sent.size)
        assertTrue(recorder.sent[0].second.contains("等你验收"))
        assertTrue(recorder.sent[0].second.contains("还差一节没写完"))
        assertTrue(recorder.sent[1].second.contains("已完成"))
        assertTrue("产物数要报", recorder.sent[1].second.contains("产物 3 个"))
    }

    @Test
    fun `a mission without a source conversation stays silent`() = runBlocking {
        val recorder = Recorder()
        val echo = MissionConversationEcho(recorder::post)

        echo.onStarted(mission(sourceId = null))
        echo.onSettled(mission(sourceId = null), MissionStatus.DONE, "done")

        assertTrue("老任务 / 工作台创建的任务不许回声", recorder.sent.isEmpty())
    }

    @Test
    fun `ACTIVE and ARCHIVED are not worth a message`() = runBlocking {
        val recorder = Recorder()
        val echo = MissionConversationEcho(recorder::post)
        val m = mission()

        echo.onSettled(m, MissionStatus.ACTIVE)
        echo.onSettled(m, MissionStatus.ARCHIVED)

        assertTrue(recorder.sent.isEmpty())
    }

    @Test
    fun `a broken source id is ignored instead of throwing`() = runBlocking {
        val recorder = Recorder()
        val echo = MissionConversationEcho(recorder::post)
        val m = mission(sourceId = "not-a-uuid")

        echo.onStarted(m)

        assertTrue(recorder.sent.isEmpty())
    }

    @Test
    fun `a failing post never escapes`() = runBlocking {
        val echo = MissionConversationEcho(post = { _, _ -> error("conversation deleted") })
        // 不抛就算通过：回声失败不影响任务本身
        echo.onStarted(mission())
        echo.onSettled(mission(), MissionStatus.DONE, "done")
    }

    @Test
    fun `settled text must not start with an empty title`() = runBlocking {
        val recorder = Recorder()
        val echo = MissionConversationEcho(recorder::post)
        echo.onSettled(mission(title = "  "), MissionStatus.BLOCKED, null)

        val text = recorder.sent.single().second
        assertTrue(text.contains("未命名任务"))
        assertFalse(text.contains("「」"))
    }
}
