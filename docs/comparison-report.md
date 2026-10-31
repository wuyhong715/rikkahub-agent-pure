# Android Agent / Coding Agent 对照报告

日期：2026-10-31
审查基线：Moxw `fix/setting-ssh-save-state` 分支的工作区源码；本 PR 以最新 `moxw` 为基线，仅提交下述策略、测试与报告。
范围：Eta、DeepseekHarness-Android、当前 Moxw 源码，以及 Codex、Kimi Code、DeepSeek Harness 中可迁移的 agent 工程实践。依据工作区中的源码、测试与文档进行静态比较。

## 结论摘要

Moxw 已经具备较完整的 agent 工程基础：统一 run ledger、checkpoint、按 run 类型区分恢复策略、工具审批偏好、无人值守工具拒绝规则，以及纯 JVM 策略测试。参考实现最值得复用的不是 UI 或平台依赖，而是三个原则：**安全策略在 allow/auto 快捷路径之前求值、恢复时对状态归属不确定采取 fail-closed、复杂决策拆成纯函数并用边界测试固定**。

本次发现并修正了一个窄范围恢复边界：mission 恢复策略允许 `runId == null` 时跳过 run ownership 校验，与 `MissionClaim` 注释所述“mission 必须仍把该 run 作为当前轮次”不一致。现在缺失 run id 会返回 `NOT_RESUMABLE`。此改动有回归测试，但当前环境两种构建路径都没有获得测试通过结果，详见 [实施记录](implementation-record.md)。

## 比较维度与代码证据

| 维度 | Moxw 当前情况 | 参考实现证据 | 判断 |
|---|---|---|---|
| 可恢复执行与 side effect | `data/agentrun/RunResumePolicy.kt` 按 kind、checkpoint、conversation、attempt 与 mission claim 判定；`RunResumeCoordinator.kt` 取 mission 的 `lastRunId`；`AgentRunBootRecovery.kt` 对 stranded run 尝试恢复并汇总通知。`RunResumePolicyTest.kt` 覆盖多种 kind、mission ownership 与断点描述。 | Eta `app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentRunRecoveryCoordinator.kt` 仅在 runtime active/terminal 状态已知时决定 reattach/interrupted；其测试覆盖 active 状态未知时不误判中断（`app/src/test/kotlin/io/github/mangi/eta/ui/app/AgentRunRecoveryCoordinatorTest.kt`）。Eta checkpoint store 持久化 in-flight context / transcript（`agent/runtime/AgentRunCheckpointStore.kt`、`AgentRunCheckpointStoreTest.kt`）。 | Moxw 对副作用的 replay 区分比“统一重试”更合适；参考 Eta 的状态不确定不做结论、Codex/Kimi 的策略前置求值。已修正 mission claim 缺 run id 时的宽松情况。 |
| 工具审批、策略优先级 | `data/preferences/ToolApprovalPreferences.kt` 保存审批 mode 与 blacklist；`data/ai/tools/HeadlessToolApprovalPolicy.kt` 对无人值守环境中需人在场的工具返回显式拒绝；同目录 `HeadlessToolApprovalPolicyTest.kt` 覆盖策略。 | Kimi `packages/agent-core-v2/src/agent/permissionPolicy/permissionPolicyService.ts` 按序执行 deny、危险命令 ask、auto/session history、用户 ask/allow、敏感路径等策略并在第一个决策处停止；`permissionPolicyService.test.ts` 明确验证 deny 高于 YOLO、ask 高于 allow。Codex `codex-rs/core/src/exec_policy.rs` 把 Forbidden、NeedsHumanApproval、NeedsApproval、Allow 分开，并对不同 approval policy 与命令策略分别求值。 | 适合 Moxw 的是策略顺序可见、危险/拒绝规则不能被 auto 绕过、顺序有回归测试；不需要照搬 Codex 的 shell rule DSL。建议补一组 Moxw 端到端纯策略优先级测试，覆盖 Manual/Semi/Auto、默认危险工具、用户例外与 headless 拒绝交互。 |
| 设备动作循环与守卫 | Moxw 是 Android agent，UI 自动化工具会操作真实设备；应把失败、无效果、重复动作约束和明确拒绝留在执行边界。当前相关本地工具位于 `data/ai/tools/`，本任务未修改其行为。 | DeepseekHarness-Android `app/src/main/java/io/github/jackwener/dsh/core/capability/ActionGuard.kt` 的纯 `LoopGuard` 对 Repeat/NoEffect/Reread/Failure 分级提醒并最终停止；`app/src/test/java/io/github/jackwener/dsh/core/capability/ActionGuardTest.kt` 覆盖阈值、reset 和停止后拒绝。 | 可借鉴阈值决策纯函数化与“停止后所有受保护调用都拒绝”的测试模型。不要直接移植其阈值：Android UI、设备操作风险和 Moxw 已有用户确认/工具门禁语义需要先对齐。 |
| 审批事件与审计 | Moxw 运行状态有 `AgentRun` ledger；工具审批会在运行时请求用户决定。本次没有审阅全部网络 / Web / UI 审批通路，不对审计完整性下结论。 | DeepSeek Harness `packages/interaction/user-approval/tests/approval.spec.ts` 测试审批必须位于打开的 turn、无 listener 时 fail-closed 为 unavailable、并成对写入 asked/decided 审计事件；`packages/acp/acp/tests/approval.spec.ts` 与 `docs/ENGINE-CONTRACT.md` 定义 request / response 和 session 权限同步契约。 | 对 Moxw 有用的是明确 approval 生命周期、无人响应的确定结局与可追溯事件。将审批日志升级为全局事件溯源属于跨层迁移，风险高，本次不做。 |
| 能力投影与上下文重建 | Moxw 有工具 catalog、实例可用性筛选与 `AgentRun` checkpoint；本次不改工具声明或 context 编码。 | Eta `ui/model/ToolCapabilityProjection.kt` 将 runtime capabilities 映射成 UI 可用状态，并有纯测试；`AgentRunCheckpointStoreTest.kt` 验证替换 UI 后 checkpoint 仍可见。 | 适合继续采用“runtime truth -> 纯投影 -> UI”的边界；不应让 UI 自己推断危险/可执行性。当前没有足够证据认定 Moxw 该层有具体缺陷，因此列作后续审查项，不做猜测性改动。 |

## 按价值 / 风险排序的改进清单

1. **P1，已实施：mission 恢复必须有可验证的 run id。** 修改 `RunResumePolicy.missionOwnsRun()`，`runId == null` 直接拒绝恢复，且要求 `mission.lastRunId == runId`。原因是缺少 id 时无法证明这是 mission 当前轮次；影响仅为极端/不完整输入转入 `NOT_RESUMABLE`，由既有恢复流程报告，而不是重放。测试新增 `a mission run without a run id is never resumed`。
2. **P1，建议后续：补审批优先级的组合回归测试。** 重点覆盖危险默认工具、显式拒绝、用户 allow/ask 与 headless 的相互作用。参考 Kimi 的 `permissionPolicyService.test.ts` 对 deny/ask 高于 auto/allow 的断言。没有确认 Moxw 当前有可触发的错误路径，故本次不改审批行为。
3. **P2，建议后续：为 checkpoint / recovery 的状态未知、checkpoint 陈旧、mission 已停止等场景补充跨层契约测试。** Eta 的 recovery coordinator 测试展示了 runtime 状态未知时不应推断 interrupted。Moxw 已有纯 policy 与 repository 测试，但 Room、恢复 coordinator、Supervisor 的完整协同测试是否覆盖全部状态仍需单独盘点。
4. **P2，建议后续：把可执行性和审批原因投影给任务/工具 UI。** 参考 Eta capability projection；需先验证 Moxw 现有 UI 是否重复推导，以及变更对 Android 工具权限的用户影响。
5. **P3，暂不做：实现 Codex 式 shell 规则 DSL、OS sandbox / workspace write permission profiles。** Codex 的 `exec_policy.rs` 面向本地命令执行与平台 sandbox；Moxw 的执行实例横跨 Android 本地工具、Termux、SSH、workspace，单一 shell policy 不能提供同等隔离，照搬会造成“看起来安全、实际跨后端不一致”。应作为独立架构项目设计，不在本次局部修复中实现。
6. **P3，暂不做：把 DeepSeek Harness 的审批 event sourcing 完整移植到 Moxw。** 该仓库通过 turn enclosure、asked/decided audit 和 ACP 协议维持审批生命周期；Moxw 有现成 run ledger 和不同执行通道。要建立统一审批事件模型需要改数据库、网络 DTO、UI 与迁移，超出安全小步范围。

## 哪些设计不应照搬

- 不照搬 Codex 的 shell 命令规则和 OS sandbox 作为 Moxw 通用边界：Moxw 不只有 shell，远端 SSH 与 Android UI 的作用范围、权限主体不同。
- 不照搬 Kimi 的桌面 Bash AST 分析器。其 parser、wrapper 递归和 POSIX shell 语义并不等价于 Android 上多种执行后端；应在对应执行器内定义能力，不可用命令字符串猜测远端行为。
- 不把所有 stranded run 自动重放。Moxw `RunResumePolicy` 已明确把 chat、telegram、subagent、workflow、external automation 等不同副作用路径区别处理；参考 Eta 也只在 runtime 状态可知时做恢复判断。
- 不照搬 DeepseekHarness-Android 的设备动作计数阈值。重复点击、屏幕重读、ActionOutcome 在各 agent 的工具模型不同，先比较本地语义和保护现状再设阈值。
- 不因参考仓库有更多模块而做大规模迁移。Moxw 已有相近能力，改进应填具体边界缺口并由本仓测试证明。

## 证据边界

本报告是对工作区当时 checkout 的源码、测试、文档进行静态比较，不代表各参考仓库当前 upstream 最新版本，也未运行这些外部仓库的测试。Moxw 本次 targeted unit test 运行结果为“环境阻塞 / 超时”，而非通过；详见实施记录。