package me.rerere.rikkahub.service.mission

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.mission.AcceptanceCheck
import me.rerere.rikkahub.data.mission.AcceptanceKind
import me.rerere.rikkahub.data.mission.MissionEntity
import me.rerere.rikkahub.data.mission.MissionLimits
import me.rerere.rikkahub.data.mission.MissionStatus
import me.rerere.rikkahub.runtime.util.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批 H — 验收判定：命令翻译、退出码语义、基础设施故障不阻断、引号转义。
 *
 * 这一层最容易出的错是「把不通过判成通过」——任务于是在没有交付物的情况下关闭。
 * 所以每个 kind 都单独钉一条。
 */
class MissionAcceptanceVerifierTest {

    private val sent = mutableListOf<String>()

    private fun verifier(scripts: Map<String, HookResult> = emptyMap()) = MissionAcceptanceVerifier(
        shell = MissionHookShell { _, command, _ ->
            sent += command
            scripts[command] ?: HookResult(exitCode = 0)
        }
    )

    private fun mission(
        checks: List<AcceptanceCheck>,
        workspaceId: String? = "ws1",
    ) = MissionEntity(
        id = "m1",
        title = "交付报告",
        description = "写一份报告",
        status = MissionStatus.ACTIVE.name,
        conversationId = null,
        workspaceId = workspaceId,
        limitsJson = JsonInstant.encodeToString(MissionLimits(acceptanceChecks = checks)),
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `no checks means no work`() = runBlocking {
        val outcomes = verifier().verify(mission(emptyList()))
        assertTrue(outcomes.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `missing workspace reports every check as not run instead of failing them`() = runBlocking {
        val outcomes = verifier().verify(
            mission(listOf(AcceptanceCheck(text = "测试通过", kind = AcceptanceKind.COMMAND, target = "pytest")), workspaceId = null)
        )
        assertNull(outcomes.single().passed)
        assertFalse("基础设施故障不该判工作不合格", outcomes.single().blocking)
        assertTrue(outcomes.single().detail.contains("没有工作区"))
    }

    @Test
    fun `command exit code decides pass and fail`() = runBlocking {
        val ok = verifier().verify(
            mission(listOf(AcceptanceCheck(text = "测试通过", kind = AcceptanceKind.COMMAND, target = "pytest -q")))
        )
        assertTrue(ok.single().passed == true)

        val failed = verifier(mapOf("pytest -q" to HookResult(exitCode = 1, stderr = "3 failed")))
            .verify(mission(listOf(AcceptanceCheck(text = "测试通过", kind = AcceptanceKind.COMMAND, target = "pytest -q"))))
        assertTrue(failed.single().passed == false)
        assertTrue(failed.single().blocking)
        assertTrue(failed.single().detail.contains("3 failed"))
    }

    @Test
    fun `file checks translate into shell tests with quoting`() = runBlocking {
        verifier().verify(
            mission(
                listOf(
                    AcceptanceCheck(text = "有总结", kind = AcceptanceKind.FILE_EXISTS, target = "out/总结.md"),
                    AcceptanceCheck(
                        text = "没有占位符",
                        kind = AcceptanceKind.FILE_NOT_CONTAINS,
                        path = "out/总结.md",
                        target = "TODO",
                    ),
                )
            )
        )
        assertEquals("test -e 'out/总结.md'", sent[0])
        assertEquals("! grep -qF -- 'TODO' 'out/总结.md'", sent[1])
    }

    @Test
    fun `single quotes in targets are escaped`() {
        assertEquals("'it'\\''s'", MissionAcceptanceVerifier.shellQuote("it's"))
    }

    @Test
    fun `min bytes compares the reported size`() = runBlocking {
        val check = AcceptanceCheck(
            text = "报告不是空的",
            kind = AcceptanceKind.MIN_BYTES,
            target = "report.md",
            value = 1_000L,
        )
        val pass = verifier(mapOf("wc -c < 'report.md'" to HookResult(exitCode = 0, stdout = "1200\n")))
            .verify(mission(listOf(check)))
        assertTrue(pass.single().passed == true)

        val fail = verifier(mapOf("wc -c < 'report.md'" to HookResult(exitCode = 0, stdout = "12\n")))
            .verify(mission(listOf(check)))
        assertTrue(fail.single().passed == false)
        assertTrue(fail.single().detail.contains("12"))
    }

    @Test
    fun `timeout is a failure with a clear detail`() = runBlocking {
        val outcome = verifier(mapOf("pytest" to HookResult(timedOut = true)))
            .verify(mission(listOf(AcceptanceCheck(text = "测试", kind = AcceptanceKind.COMMAND, target = "pytest"))))
            .single()
        assertTrue(outcome.passed == false)
        assertTrue(outcome.detail.contains("超时"))
    }

    @Test
    fun `manual checks are recorded but never block`() = runBlocking {
        val outcome = verifier()
            .verify(mission(listOf(AcceptanceCheck(text = "语气自然", kind = AcceptanceKind.MANUAL))))
            .single()
        assertNull(outcome.passed)
        assertFalse(outcome.blocking)
    }
}
