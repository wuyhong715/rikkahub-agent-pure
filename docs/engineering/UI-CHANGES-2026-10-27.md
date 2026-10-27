# UI / 交互优化变更说明（2026-10-27）

分支 `feat/ui-interaction-polish-moxw`，基于 `origin/moxw` @ `d3ef96913`。
审计问题编号对应 [`UI-AUDIT-2026-10-27.md`](./UI-AUDIT-2026-10-27.md)。

## 变更一览

| # | 文件 | 改了什么 | 为什么 |
|---|---|---|---|
| 1 | `ui/pages/workbench/MissionDetailPage.kt` | Hero 卡里的任务描述默认折叠 3 行，溢出时出现「展开全部 / 收起」 | 任务描述常是整段提示词，会把状态、进度、运行控制挤出首屏（P0） |
| 2 | `ui/components/ai/ChatInput.kt` | `loading` 且草稿非空时，在发送键左侧常驻一个独立「停止」按钮；`ActionIconButton` 增加 `size` 参数 | 原来只有「草稿为空」时发送键才变「停止」，一打字就再也停不下来（P1） |
| 3 | `ui/pages/chat/ChatDrawer.kt` | `DrawerAction` 从「圆形图标 + 长按 Tooltip」改成「圆形图标 + 常驻文字标签」；`label` 参数由 `@Composable () -> Unit` 改为 `String` | 抽屉是唯一全局导航，5 个入口只有图标，两个齿轮分不清（P1） |
| 4 | `ui/pages/workbench/CreateMissionSheet.kt` | 内容区改为可滚动 + 「创建」固定在底部（`Column` + `weight(1f, fill=false)` + `verticalScroll`） | 长表单里主操作原来在滚动末尾，键盘弹起后更难找（P1） |
| 5 | `ui/pages/workbench/WorkbenchComponents.kt` | 任务卡片验收进度加 4dp 细进度条（颜色跟随任务状态色） | 长任务只有 `0/6 验收` 数字，没有进度视觉（P2） |
| 6 | `app/src/main/res/values/strings.xml`、`values-zh/strings.xml` | 新增 `mission_detail_description_expand/_collapse`、`chat_drawer_nav_assistant/_more` | 新文案；其余语言回落到英文默认值 |

> 原计划的「消息操作图标放大到 48dp」在复核实测后**撤回**：另一处消息量得 49×59dp，已达标，改了只是无谓加内边距（审计 #8）。

## 行为兼容性

- 只改 Compose 布局与文案，没有触碰：模型调用、任务引擎（`MissionDetailVM` / `WorkbenchVM`）、计费、权限、数据库、路由表（`Screen` / `RouteActivity` 未改）。
- 所有 onClick 回调签名与原实现一致，导航目标不变；工作台仍是全屏页面 + 右上火箭入口。
- 新增字符串走 Android 资源体系；未翻译语言按系统默认回落到 `values/`（英文）。
- `CreateMissionSheet` 的提交逻辑（`MissionDraft` 构造）原样保留，只是把按钮移出滚动容器；该文件有约 320 行**纯缩进**变更，审阅时可用 `git diff -w` 看语义差异（13 行）。

## 验证

见 [`UI-VERIFY-2026-10-27.md`](./UI-VERIFY-2026-10-27.md)：编译命令、结果、未覆盖项。

## 回滚

```bash
cd /workspace/wt-ui
git checkout -- app/src/main            # 丢弃本轮全部源码/资源改动
git clean -f docs/engineering/UI-AUDIT-2026-10-27.md docs/engineering/UI-CHANGES-2026-10-27.md
# 或整支分支回退
git checkout d3ef96913 -- .
```

分支本身可整体删除：`git worktree remove /workspace/wt-ui`（先确认没有需要的本地改动）。
