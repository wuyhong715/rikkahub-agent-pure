package me.rerere.rikkahub.ui.pages.workbench

import me.rerere.rikkahub.data.mission.MissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 这组测试守的是“视觉语义”本身，不是像素：
 *
 * - 每个任务状态拿到**唯一**的语气 —— 两个状态共用一个语气，就会出现“运行中”和
 *   “待验收”同色这类近义冲突，用户只能读文字才知道区别。
 * - “卡住”必须是唯一一个报警语气 —— 一屏里出现两处红，红色就失去意义。
 * - 验收进度在没有验收项时必须是 null，而不是 0 或 NaN，否则卡片会画出一条
 *   看起来“已经通过”的实心进度条。
 */
class MissionStatusToneTest {

    private val allStatuses = MissionStatus.entries

    @Test
    fun `every status maps to exactly one tone`() {
        // 枚举本身就是全量的，这里断言的是“没有漏写 when 分支导致的同一语气复用”。
        val tones = allStatuses.map { statusTone(it) }
        assertEquals(allStatuses.size, tones.toSet().size)
    }

    @Test
    fun `each status keeps its documented tone`() {
        assertEquals(MissionStatusTone.PROGRESS, statusTone(MissionStatus.ACTIVE))
        assertEquals(MissionStatusTone.ATTENTION, statusTone(MissionStatus.BLOCKED))
        assertEquals(MissionStatusTone.REVIEW, statusTone(MissionStatus.REVIEW))
        assertEquals(MissionStatusTone.COMPLETE, statusTone(MissionStatus.DONE))
        assertEquals(MissionStatusTone.MUTED, statusTone(MissionStatus.ARCHIVED))
    }

    @Test
    fun `blocked is the only attention tone`() {
        val attention = allStatuses.filter { statusTone(it) == MissionStatusTone.ATTENTION }
        assertEquals(listOf(MissionStatus.BLOCKED), attention)
    }

    @Test
    fun `running and review never share a tone`() {
        // 这两条是最容易被同一种颜色糊在一起的状态：都在“等下一步”，但一个在跑、一个要人验收。
        val running = statusTone(MissionStatus.ACTIVE)
        val review = statusTone(MissionStatus.REVIEW)
        assertEquals(false, running == review)
    }

    @Test
    fun `progress fraction is null when there is nothing to check`() {
        assertNull(MissionProgress.fraction(done = 0, total = 0))
        assertNull(MissionProgress.fraction(done = 3, total = -1))
    }

    @Test
    fun `progress fraction is clamped into zero one`() {
        assertEquals(0f, MissionProgress.fraction(done = 0, total = 6)!!, 0.0001f)
        assertEquals(0.5f, MissionProgress.fraction(done = 3, total = 6)!!, 0.0001f)
        assertEquals(1f, MissionProgress.fraction(done = 6, total = 6)!!, 0.0001f)
        // 验收项被删掉之后 checked 里可能残留更大的下标，不能画出 >100% 的进度。
        assertEquals(1f, MissionProgress.fraction(done = 9, total = 6)!!, 0.0001f)
        assertEquals(0f, MissionProgress.fraction(done = -2, total = 6)!!, 0.0001f)
    }
}
