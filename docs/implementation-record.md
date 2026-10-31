# Moxw 参考对照改进：实施记录

日期：2026-10-31
PR 分支：`moxw-reference-audit-hardening`，基线为当时最新 `origin/moxw`。

## 目标与基线保护

- 审查最初在 `fix/setting-ssh-save-state` 工作区进行，其中 `ChatInput.kt` 已有未提交的 reasoning 控件改动。为避免把该改动或该分支后续提交带入 PR，本 PR 从最新 `moxw` 建立独立分支；该 reasoning 改动已存在于 PR 基线中，不属于本 PR 的差异。
- 本 PR 仅包含 `RunResumePolicy.kt`、`RunResumePolicyTest.kt` 与两份审查文档。

## 改动

1. `app/src/main/java/me/rerere/rikkahub/data/agentrun/RunResumePolicy.kt`
   - 在 `missionOwnsRun()` 中对 `runId == null` 显式返回 `false`。
   - mission 恢复现在必须满足：mission claim 存在、run id 存在、status 为 ACTIVE、executionState 为 RUNNING、`mission.lastRunId` 与该 run id 完全相同。
   - 这是 fail-closed 修正：旧逻辑在 run id 缺失时跳过归属比对，虽然 production coordinator 目前传入 `run.id`，但纯策略 API 的默认参数是 null，违反 mission ownership 契约。
2. `app/src/test/java/me/rerere/rikkahub/data/agentrun/RunResumePolicyTest.kt`
   - 新增 `a mission run without a run id is never resumed`，断言缺失 run id 时为 `NOT_RESUMABLE`。
3. `docs/comparison-report.md`
   - 新增跨仓差距与实施对照报告，覆盖 Eta、DeepseekHarness-Android、Codex、Kimi Code、DeepSeek Harness 和 Moxw，列出路径证据、优先级/风险、暂缓项与不应照搬的设计。

## 验证结果

- `git diff --check`：通过，无 whitespace 错误。
- 用户文件复核：`ChatInput.kt` 的 `git hash-object` 仍为 `75bf92da528e9f68a023252073601dc9480445c8`；其 diff 与开工时一致。
- 工作区 targeted test：执行 `./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.agentrun.RunResumePolicyTest --console=plain`。**未进入测试执行**；项目根 `build.gradle.kts:2` 所需 `com.android.application` 9.3.1 在 workspace Gradle 的 Google、Maven Central、Gradle Plugin Portal 均解析失败。
- Termux targeted test：设备环境有 JDK 21、Gradle 9.8.2，且已缓存 AGP 9.3.1。为隔离 Termux clone 的用户更改，曾在 detached 临时 worktree 中尝试应用仅包含本次两个代码文件的 patch，并运行同一 targeted test。调用超过 600 秒，无测试结果返回；该临时 worktree 已清理，Termux 主 clone 的未提交文件未改动。**不能认定测试通过**。
- Android UI 实机验证：未执行。本次改动是纯策略函数逻辑，实机 UI 不足以替代单元测试。

## 未处理风险与待办建议

- 由于测试没能完成，Kotlin 编译、JUnit 执行和新断言的运行结果仍未验证。应在 Android Gradle Plugin 可解析/Gradle 构建可完成后运行上述定向测试；通过前不要将该改动作为已测试发布。
- production coordinator 当前通过 run entity 传入非空 id；该边界主要防御将来的调用方、数据/迁移异常或直接策略调用。现无证据表明线上会把 null 传到 mission resume。
- 未扩展检查完整审批优先级和 Room/Coordinator/Supervisor 跨层集成；参考报告中列为后续候选，因测试未能运行且本次要求范围受控，不扩成第二项行为改动。
- Codex OS sandbox / shell DSL、Kimi Bash AST 危险命令解析及 DeepSeek Harness 统一审批 event sourcing 均涉及多后端/跨层设计，未移植。

## 验证命令与结果摘要

```text
PASS  git diff --check
BLOCKED ./gradlew :app:testDebugUnitTest --tests me.rerere.rikkahub.data.agentrun.RunResumePolicyTest --console=plain
       AGP 9.3.1 无法在 workspace 环境解析，测试未启动
TIMEOUT Termux detached-worktree 上运行相同 targeted test，600 秒未返回
PASS  用户 ChatInput.kt 哈希与初始值一致、diff 未变化
```
