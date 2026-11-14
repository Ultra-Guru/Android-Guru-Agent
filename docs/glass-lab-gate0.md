# 玻璃实验室成品化 · Gate-0 落地记录

> 版本：Gate-0 v1.0 · 2026-11-14 · 上位文档：《玻璃实验室成品化规格书 Gate-0》
> （本文件是该规格书的**落地记录与验收台账**，规格书本体见 PR 描述附件）

## 0. 流程裁决（状态机）

Gate-0 未通过前，**不允许把任何实验室材质替换/接入正式 UI**：

| 原分期 | 内容 | 状态 |
|---|---|---|
| v1.1 P0-0 | `drawOutline` import 修复 | ✅ 保留（已在 main） |
| v1.2 P0-A～P0-E | 74 部件接入 / 会话快照 / 双 Local / 设置页 / G2 内部件 | ⛔ **BLOCKED_BY_GATE0** |
| v1.3 P0-F | 生效策略三档 / 设备分级 / 设置页卡片 | ⛔ **BLOCKED_BY_GATE0** |

解禁条件：G0-1～G0-8 全绿（真机探针 + Gate-0 CI check 双绿）+ 人工签字。
已入库的 `LocalConversationSkin` 分支等 v1.3 内容不回退 —— 本 PR 通过
同源回归（G0-8）让实验室重新覆盖它们。

## 1. 落地清单（D-1～D-10 → 文件 → 测试）

| 编号 | 问题 | 修法 | 文件 | 锁 |
|---|---|---|---|---|
| D-1 | 发布按钮被 GlassSurface 裁剪层裁掉 | 玻璃底 `matchParentSize` 与内容分层；按钮在外层 Box `TopCenter − 18dp` | `GlassLabDarkBar_v2.kt` | 人工验收（按钮凸出可见） |
| D-2 | 中央 76dp 发布位盖死两项（每页仅 2 项可点） | 五槽模型 `nav\|nav\|发布\|nav\|nav`；`slotOf(i)` 映射 | `GlassDockMath.kt` | `GlassLabDarkBarLayoutTest`（含 v1 等分反证） |
| D-3 | 药丸跨页飞出、第 2 页无高亮 | 药丸**页内独立**，跨页交给 Pager 原生手势；选中投影 = 当前页页内选中 | `GlassDockMath.selectionAfterSettle` | `GlassLabDarkBarLayoutTest` |
| D-4 | Dock 误用 FAB 的 Floating 档（框中框放大器） | 新增 `GlassRecipeBook.Dock`（blur 14 / tint 0.42 / lens 0 / 色散关 / 2dp / corner 34）+ 四档对照 chips | `GlassRecipe.kt` | `GlassRecipeParityTest.dock-*` |
| D-5 | NIGHT / DAY 两个 tab 同图 | `DarkBarSectionV2(mode)`：NIGHT 暗底亮纹理 / DAY 白底暗纹理；并排台同屏双态 | `GlassLabDarkBar_v2.kt` / `GlassLabBench.kt` | 人工验收（并排截图差异） |
| D-6 | 64dp 纯色大色块糊成均匀色 | `TexturedSamplingColumn`：1dp 细网格 + 45° 斜纹 + 彩色小方块 + 等宽微字（drawWithCache） | `GlassLabProbes.kt` | ΔE 探针量化 |
| D-7 | 硬编码中文 | 全部 label / 标题 / 提示走 `stringResource`，values 与 values-zh 双份 | `strings_ui.xml` ×2 | i18n 镜像门禁 |
| D-8 | 验收清单写死 PASS | `ChecklistSection` 改运行时探针驱动；NOT RUN 也是诚实状态 | `GlassLabSections.kt` | 运行时（探针未跑即 NOT RUN） |
| D-9 | 无提炼机制（两套手写值） | `GlassRecipe` 单一数据源 + 导出闭环；`GlassStyle` 九档**从 GlassRecipeBook 生成** | `GlassRecipe.kt` / `GlassStyle.kt` | `GlassRecipeParityTest`（H-7 机械化） |
| D-10 | 实验室不消费会话快照（路径 ≠ 生产） | 短路判定提取为 `GlassSkinGate` 纯函数，生产与并排台读同一函数 | `GlassSkinGate.kt` / `GlassSurface.kt` / `GlassLabBench.kt` | `Gate0ParityTest` |

## 2. 六条硬指标的落点

| 指标 | 落地 |
|---|---|
| P-1 同源 | `GlassRecipeBook` 唯一真源；`GlassStyle` companion 九档 = `recipe.toGlassStyle()`；`GlassLabSamples.GlassSpec` 数值骨架 = Book 派生 + 显式 copy 偏移；`paramLines()` 单一排版出口 |
| P-2 双态并排 | `SideBySideBenchSection`：同一 recipe / 同一滚动相位，两栏独立 ApexTheme；会话快照预设切换 |
| P-3 可调可导出 | `RecipeParamPanel`（10 维滑杆 + 色散开关）+ `RecipeExportDialog`（exportKotlin / exportJson + 剪贴板）；JSON 无损回读有测试 |
| P-4 可量化验收 | 五类运行时探针：ΔE（抓 `LayerBackdrop.graphicsLayer` 采样层两帧算 CIE76）、WCAG 对比度（Frosted 最劣情况 ≥ 4.5:1）、帧时间 P95（FrameClock 实测，active vs idle）、降级档（SDK / 省电 / 减少动效 → PaleFlat/Frosted/Backdrop/Ambient）、容器完整性（Dock 底边像素扫描双底边）；可交互遍历（8 项程序化点击） |
| P-5 可回归 | `GlassLabGoldenTest` 数值金样（四档 × API 28/31/33 × 短路态，漂移即红）+ Gate-0 workflow 独立 check；位图金样边界见 §4 |
| P-6 可交互 | D-1/D-2/D-3 修复 + `GlassLabDarkBarLayoutTest` 槽位全覆盖 + 运行时遍历探针 |

## 3. CI 接线

- **`gate0.yml`（新）**：kotlinc 裸编译 + 四套 JVM 测试（约 2 分钟），是
  §0 解禁的唯一独立信号；Job Summary 打印覆盖面台账。
- **ci.yml（复用）**：同一批测试经 `:app:testDebugUnitTest` 在 app-compile
  双跑；三道静态门禁（文件预算 / 反模式 / 括号平衡）与 i18n 镜像门禁照常生效。
- 测试纪律：四套测试均为纯 JVM（零 Android / 零 Compose 依赖），kotlinc
  与 Gradle 双通道可跑 —— `GlassRecipe` / `GlassSkinGate` / `GlassDockMath`
  三个生产文件保持零 Compose import 是硬约束（CI 裸编译的前提）。

## 4. 诚实边界（Gate-0 签字前必读）

- **位图金样**：P-5 的像素级截图 diff（NIGHT/DAY × 档位 × API 的位图对比）
  需要 instrumentation / Robolectric 通道，当前依赖矩阵不含。本 PR 落地
  数值金样（参数 → 渲染管线配置的完整映射），位图金样随 instrumentation
  通道补齐后并入 Gate-0 check。
- **容器完整性探针**：像素扫描基于亮度剖面启发式（3px 平滑 + 0.06 阈值），
  能抓「双硬边界」，对柔和色差带的灵敏度低于人眼 —— 它是回归哨兵，不是
  视觉评审的替代品。
- **帧时间基线**：P95 对比「空闲基线 × 1.4 与 33ms 预算的较大者」，真机
  不同刷新率下预算语义不变（帧预算按时间不按帧数）。
- **探针未跑 ≠ PASS**：验收清单的 NOT RUN 状态是诚实状态 —— 八项验收
  要求真机把每个探针跑过一遍。

## 5. G0 验收台账（人工签字栏）

- [ ] **G0-1** D-1/D-2/D-3：按钮凸出 18dp 完整可见；「遍历 8 项」探针 8/8；
      第 2 页有选中高亮；换页无药丸飞出 —— 签字：＿＿＿
- [ ] **G0-2** D-4：Dock 用 `GlassRecipeBook.Dock`，容器完整性扫描单边界
      （无双底边 / 双圆角 / 彩虹边） —— 签字：＿＿＿
- [ ] **G0-3** D-5/D-6：并排台 NIGHT / DAY 差异可辨；ΔE 探针 ≥ 3.0 —— 签字：＿＿＿
- [ ] **G0-4** P-1 同源：`GlassRecipeParityTest` 全绿 —— 签字：＿＿＿
- [ ] **G0-5** P-3 导出：滑杆调参 → 导出 Kotlin → 粘进 `GlassRecipeBook` →
      实验室与正式组件同步变化 —— 签字：＿＿＿
- [ ] **G0-6** P-4 探针：对比度 ≥ 4.5:1；降级档真实命中；帧时间不劣于基线；
      清单无硬编码 PASS —— 签字：＿＿＿
- [ ] **G0-7** P-5 回归：`GlassLabGoldenTest` 进 CI（gate0.yml），四档 ×
      API 三档全绿 —— 签字：＿＿＿
- [ ] **G0-8** P-1/D-10 路径一致：`Gate0ParityTest` 断言实验室消费
      `LocalConversationSkin`（并排台 MINIMAL 预设两栏同时扁平化）—— 签字：＿＿＿

> 八项全绿 + 签字 → 把 §0 表格两项改为 `READY`，恢复 v1.2 P0-A~E /
> v1.3 P0-F 排期。任一红 → 保持 `BLOCKED_BY_GATE0`。

## 6. H-7（新增硬规则）

任何 `ui/glass` 目录下的参数改动，必须先改 `GlassRecipeBook`（单一真源）
并过 `GlassRecipeParityTest`；禁止直接改 `GlassStyle` 常量绕过实验室 ——
`GlassStyle` 九档自 Book 生成后，该规则已被编译器与测试双重机械化
（常量不存在了，绕行路线消失）。
