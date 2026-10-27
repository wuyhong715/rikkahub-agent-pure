# 视觉优化验证报告（2026-10-27）

分支 `feat/ui-visual-polish-moxw` @ 基于 `357ee5c78`，工作树 `/workspace/wt-ui-visual`。

---

## 1. 结论先行

| 层级 | 状态 | 说明 |
|---|---|---|
| 语法（解析级） | ✅ 通过 | `kcheck` 对 8 个文件，**无** `syntax error` / `expecting` 类错误（只剩 classpath 噪音，见 §3.1） |
| 导入可解析性 | ✅ 通过 | `check_symbol_imports.py`：无 `me.rerere.rikkahub.*` 未解析导入（只剩外部库 `me.rerere.hugeicons` 噪音，见 §3.2） |
| 括号/结构平衡 | ✅ 通过 | 7 个文件 brace 差 0；`MissionDetailPage.kt` paren +2 与 **HEAD 原版一致**（字符串内括号所致），非本轮引入 |
| 新逻辑单测 | ✅ 通过（本机可执行） | `MissionStatusTone` + `MissionProgress` 用 stub enum 编译并跑通全部断言（§3.4） |
| **tokens 文件真编译** | ✅ **通过（真实 Compose 编译器 + 真实 Material3 jar）** | `UiVisual.kt` 用 `kotlin-compiler-embeddable` + `kotlin-compose-compiler-plugin` + gradle 缓存里的 compose/material3 jar 编译出 4 个 class，无类型错误（§3.6） |
| Gradle 编译 / `testMoxwDebugUnitTest` | ❌ 本机不可执行 | aarch64 rootfs + x86-64 `aapt2`/`aidl`，且 `material3/material-color-utilities` 子模块未初始化为本地必需（§2） |
| 远程 CI 编译 | ✅ **通过** | PR #436 的 Actions `build`：`assembleMoxwRelease` 成功，产出 `apk-moxw`（194.7MB）；`testMoxwReleaseUnitTest` 全绿，**`MissionStatusToneTest` 6 例 0 失败 0 跳过**（§7） |
| 实机视觉回归 | ⏸ 用户明确表示不需要 | 用户 2026-10-27 19:5x 回复「不用真机对比」；因此本轮交付不含实机前后截图（§5.2） |

**因此本轮的最强证据是 CI：整包 `assembleMoxwRelease` + `testMoxwReleaseUnitTest` 在 GitHub Actions 的 x86-64 runner 上真跑通过（PR #436）。唯一没做的是实机目视回归——用户明确表示不需要。**

---

## 2. 本机环境硬限制（沿用并复核 M100）

```
$ uname -m
aarch64
$ file /opt/android-sdk/build-tools/*/aapt2
ELF 64-bit LSB pie executable, x86-64     # cannot execute: Exec format error
$ git submodule status
-4fbc76d… llama-cpp/native/llama.cpp      # 未初始化
-6fd88eb… material3/material-color-utilities  # 未初始化 → :material3 报 Unresolved 'dynamiccolor'
```

结论不变：`:app` 的 `compileMoxwDebugKotlin` 需要 `processResources`（aapt2，x86-64）与 R 生成，本机无 qemu，跑不通。任何类型错误只能由 CI 暴露。

---

## 3. 实际执行的检查

### 3.1 解析级语法检查

```
$ /workspace/bin/kcheck UiVisual.kt MissionStatusTone.kt WorkbenchComponents.kt \
      WorkbenchContent.kt MissionDetailPage.kt ChatDrawer.kt ChatPage.kt MissionStatusToneTest.kt
（输出 30 余行 error，全部属于“无 classpath 的连带噪音”）
real 0m12.5s
```

逐条归类（**没有一条是语法错误**）：

| 输出形态 | 条数 | 判定 |
|---|---|---|
| `this declaration needs opt-in … ExperimentalStdlibApi` | 13 | 噪音：`androidx.compose.*` 解析失败后对 `Dp`/`Color` 之类符号的连带误判 |
| `no parameter with name 'maxWidth' / 'minWidth' / 'alpha' / 'assistants' …` | 9 | 噪音：同名类型未解析，编译器退化成别的重载 |
| `'when' expression must be exhaustive` | 4 | 噪音：被 `when` 的枚举/类型未解析（`MissionStatus`、`MissionStatusTone` 在无 classpath 时不可见）。**新增的 `when` 已列全 5 个枚举值**，见 §3.4 实际编译验证 |
| `'operator' modifier is required` | 2 | 噪音 |
| `annotation argument must be a compile-time constant` | 1 | 噪音（`@OptIn` 参数未解析） |
| `syntax error` / `expecting '…'` | **0** | ← 本轮真正要确认的东西 |

> 教训沿用 M100：检查器必须 `grep -iE "syntax error|expecting '"`（大小写不敏感 + 兼容 `syntax error:` 前缀），并**用已知坏样本反向验证敏感性**，否则 0 命中不代表没问题。

### 3.2 导入可解析性

```
$ python3 /workspace/bin/check_symbol_imports.py . <8 files>
（46 行，全部为 me.rerere.hugeicons / me.rerere.hugeicons.stroke.* → package not found）
```

`hugeicons` 是外部依赖（不在本仓库），属预期噪音。
本轮新增的 5 个导入全部命中仓库内声明，**没有** `package not found`：

| 新导入 | 声明处 | 结果 |
|---|---|---|
| `me.rerere.rikkahub.ui.theme.GroupCard` | `ui/theme/UiVisual.kt` | ✅ |
| `me.rerere.rikkahub.ui.theme.SectionHeader` | 同上 | ✅ |
| `me.rerere.rikkahub.ui.theme.Spacing` | 同上 | ✅ |
| `me.rerere.rikkahub.ui.theme.Hairline` / `Radius` | 同上 | ✅ |
| `me.rerere.rikkahub.Screen` | `RouteActivity.kt`（`sealed interface Screen`） | ✅ |

### 3.3 结构平衡

```
UiVisual.kt              brace +0  paren +0
MissionStatusTone.kt     brace +0  paren +0
WorkbenchComponents.kt   brace +0  paren +0
WorkbenchContent.kt      brace +0  paren +0
MissionDetailPage.kt     brace +0  paren +2   ← 与 git show HEAD:<file> 完全一致（原版即 +2）
ChatDrawer.kt            brace +0  paren +0
ChatPage.kt              brace +0  paren +0
```

### 3.4 新增纯逻辑的真实编译 + 执行

`MissionStatusTone.kt` 与 `MissionProgress` 不依赖 Compose，因此可以**真编译真运行**：

```bash
$ mkdir /tmp/vt && cd /tmp/vt
$ cat > Stub.kt <<'EOF'
package stub
enum class MissionStatus(val isTerminal: Boolean) { ACTIVE(false), BLOCKED(false), REVIEW(false), DONE(true), ARCHIVED(true) }
EOF
# 把 MissionStatusTone.kt 的 package 与 import 指向 stub 后编译
$ kotlinc Stub.kt Tone.kt Main.kt -include-runtime -d vt.jar
$ java -jar vt.jar
ALL PURE-LOGIC CHECKS PASSED
```

覆盖的断言（与 `MissionStatusToneTest` 一一对应）：

1. 5 个状态 → 5 个语气，`toSet().size == 5`（无复用）；
2. 每个状态落在文档声明的语气上；
3. `ATTENTION` 只对应 `BLOCKED`；
4. `ACTIVE` 与 `REVIEW` 语气不同（运行中 ≠ 待验收）；
5. `fraction(0,0) == null`、`fraction(3,-1) == null`；
6. `fraction` 在 0/6→0、3/6→0.5、9/6→1（clamp）、-2/6→0。

> 这一条是**真编译 + 真执行**，但它只覆盖不依赖 Compose 的纯函数，**不能**推断 Compose 侧代码能编译。

### 3.5 视觉规范自查（diff 级）

```
$ git diff | grep -E "^\+" | grep -E "Color\(0x|Color\.(White|Black|…)|\.sp\b|fontSize"
+                                fontSize = 11.sp,
+                                lineHeight = 12.sp,
```

- 新增 **0** 个硬编码颜色；唯一新增的 sp 是修 `8.sp → 11.sp` 那处（有意为之）。
- 新增间距全部走 `Spacing`；新增圆角全部走 `Radius`（`chip`/`card`）。
- 未新增用户可见字符串；未改动 `values-en` 等英文资源。

### 3.6 tokens 文件真编译（本轮唯一“真编译”的 Compose 代码）

背景：`kotlinc`（独立发行版）**不能**编译 `@Composable` 函数——没有 compose 插件时，带默认参数的 composable 会在 codegen 阶段抛 `ERROR_EXPR 'Default Stub'`。解决办法是改用 **embeddable 编译器 + compose 编译器插件**，classpath 直接用 gradle 缓存里已下载的 androidx jar：

```bash
KC=…/kotlin-compiler-embeddable-2.4.10.jar
PLUGIN=…/kotlin-compose-compiler-plugin-embeddable-2.4.10.jar
SNT=…/kotlin-stdlib-2.4.10.jar ; REFLECT=…/kotlin-reflect-2.4.10.jar
CO=…/kotlinx-coroutines-core-jvm-1.8.0.jar ; AN=…/annotations-23.0.0.jar
CP=$(find ~/.gradle/caches/9.5.0/transforms -name classes.jar | tr '\n' ':')

java -cp "$KC:$SNT:$REFLECT:$CO:$AN" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -cp "$CP:$SNT:$AN" \
  -Xplugin="$PLUGIN" -jvm-target 17 -nowarn \
  -d /tmp/uivisual-out app/src/main/java/me/rerere/rikkahub/ui/theme/UiVisual.kt
# → 无输出，生成 4 个 .class
```

这条验证覆盖了本轮**最不确定的那些 API**：

- `Surface(onClick = …)` 带 `border` 的可点重载（`javap` 复核签名 `Surface-o_FOJdg(…BorderStroke…)` 存在）；
- `colorScheme.surfaceContainerLow` / `outlineVariant` 槽位存在；
- `Modifier.weight(1f, fill = false)`、`heightIn(min = …)`、`RoundedCornerShape(Dp)`；
- `ColumnScope` 内容 lambda 的转发（`GroupCard` 的两个分支）。

**注意边界**：这次只编译了 `UiVisual.kt` 一个文件。`WorkbenchComponents.kt` / `MissionDetailPage.kt` 依赖生成的 `R` 类与整个模块，仍然只能靠 CI。

---

## 4. 残留问题（已知，未在本轮解决）

| ID | 问题 | 原因 | 建议 |
|---|---|---|---|
| R1 | 无实机前后对比图 | 设备锁屏 + 通知栏展开；`take_screenshot` → `tool_not_authorized`；新包需覆盖安装授权 | 授权后装 debug 包，或由 CI 产物手动安装；本轮以 dp/sp 源码证据替代 |
| R2 | 无 Gradle 编译结论 | aarch64 无法执行 x86-64 SDK 工具链 | 必须靠 CI（`assembleMoxwRelease` + 单测），见 §5 |
| ~~R3~~ | ~~「绑定对话」可点区域 < 48dp~~ | **本轮已修**：加 `heightIn(min = 48.dp)`；代价是 Hero 卡增高约 28dp（原行高 ≈20dp），换来一个真正点得中的链接 | 若首屏空间继续吃紧，可退回 40dp 并接受略低于指南 |
| R4 | `StatCell` 未加 `Role.Button` 语义 | 避免为一格统计再引入语义 import；`clickable` 已提供 Click action | 若无障碍审查要求，补 `.semantics { role = Role.Button }` |
| R5 | `MissionCard` 内“运行中”圆点 + 左侧色条 + 药丸三处同语义 | 三者颜色同源（`primary`），不算冲突，但确实冗余 | 若后续再简化，可去掉圆点只留文字 |
| R6 | `Surface` / `RoundedCornerShape` / `clickable` 等 import 在 `WorkbenchContent.kt` 可能变未使用 | 删掉 Hero 卡后部分 import 失去引用（Kotlin 只警告不报错） | CI 若开 `allWarningsAsErrors` 需清理，本轮未开 |

---

## 5. 要做但需要授权的两步

### 5.1 远程 CI（编译 + 单测）—— ✅ 已完成

```bash
# 在 /workspace/wt-ui-visual 内
git add -A
git commit -m "feat(ui): 成套视觉优化（视觉基元/工作台摘要/任务详情层级/顶栏字号）"
git push -u origin feat/ui-visual-polish-moxw
gh pr create --base moxw --title "feat(ui): 成套视觉优化" --body-file /tmp/pr-body.md
```
- 影响：向 `wuyhong715/rikkahub-agent-pure` 推一条**新特性分支** + 开一个 PR（不合并、不改 `moxw`）。
- 回滚：`git push origin --delete feat/ui-visual-polish-moxw` + 关闭 PR；`moxw` 分支不受影响。
- 预期产出：Actions `build` 在 x86-64 runner 上真跑 `assembleMoxwRelease` + `testMoxwDebugUnitTest`，把 `MissionStatusToneTest` 真正跑一遍。

### 5.2 实机视觉回归 —— 用户明确不需要（2026-10-27 19:52「不用真机对比」）

- 方式 A：CI 产物 `app-moxw-release.apk` 手动安装（**覆盖**当前 `com.moxw.agent` 0.8.0，用户数据保留在 app 私有目录，但版本回退麻烦）。
- 方式 B：只在授权时段内装、截完图再装回旧包（需要旧包 apk）。
- 本轮**没有**执行任何一种。

---

## 6. 文件清单

新增：

- `app/src/main/java/me/rerere/rikkahub/ui/theme/UiVisual.kt`（视觉 tokens + `GroupCard` + `SectionHeader`）
- `app/src/main/java/me/rerere/rikkahub/ui/pages/workbench/MissionStatusTone.kt`（状态语气 + `MissionProgress`，纯逻辑）
- `app/src/test/java/me/rerere/rikkahub/ui/pages/workbench/MissionStatusToneTest.kt`（6 个用例）
- `docs/engineering/UI-VISUAL-2026-10-27.md`、`docs/engineering/UI-VISUAL-VERIFY-2026-10-27.md`

修改：

- `ui/pages/workbench/WorkbenchContent.kt`（Hero → `StatStrip`，组标题 → `SectionHeader`，间距走 token）
- `ui/pages/workbench/WorkbenchComponents.kt`（状态语气映射、`MissionCard` 表面与进度色、新增 `StatStrip`）
- `ui/pages/workbench/MissionDetailPage.kt`（Hero 内联验收进度、绑定对话可点、卡片统一 `GroupCard`、两处 `SectionHeader`）
- `ui/pages/chat/ChatPage.kt`（顶栏副标题 8sp → 11sp）
- `ui/pages/chat/ChatDrawer.kt`（助手卡与底部入口之间加发丝分隔线）


---

## 7. CI 实际结果（PR #436，run 40498880974 / 40498858611）

```
$ curl .../commits/17438fab7…/check-runs
assemble moxw + unit tests   completed  success   (run 40498880974, job 122171226356)
assemble moxw + unit tests   completed  success   (run 40498858611, job 122171151303)

$ curl .../actions/runs/40498880974/artifacts
apk-moxw             194,688,783 B
unit-test-report-moxw    642,097 B
room-schemas-moxw         95,456 B
```

单测报告里查本轮新增的用例（下载 artifact 后 grep）：

```
Test results - me.rerere.rikkahub.ui.pages.workbench.MissionStatusToneTest
  summary: 6 tests, 0 failures, 0 skipped, 0.004s, 100% successful
  blocked is the only attention tone           1 0 0 0s 100%
  each status keeps its documented tone        1 0 0 …
```

也就是说：

- `WorkbenchComponents.kt` / `WorkbenchContent.kt` / `MissionDetailPage.kt` / `ChatPage.kt` / `ChatDrawer.kt` 的 Compose 代码**真编译通过**（含 `GroupCard` 的 `Surface(onClick, border)`、`StatStrip` 的 lambda 分支、`Screen.Chat` 导航参数）；
- `MissionStatusToneTest` 在真实 test task（`testMoxwReleaseUnitTest`）里跑过，不是“写了没跑”；
- 整包 release APK 能产出，说明资源/Manifest/依赖图都没被这轮改动破坏。
