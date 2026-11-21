# Agent Task Packages —— 方案到实施的 Agent 可执行分解规范

> ### 🗺️ 系列导读 —— 本文档属于「四份姊妹 Spec」批次（2026-11）
>
> | # | 文档 | 管什么 |
> |---|------|--------|
> | ① | **本文（系列方法论）** | spec → Agent 任务包的分解规范：六条硬约束 + 分解四步法 + 执行协议 + 反模式清单 |
> | ② | [`terminal/TERMINAL_CHROOT_MATURITY_T99.md`](terminal/TERMINAL_CHROOT_MATURITY_T99.md) | 运行时纵深：chroot 后端生产化 —— T98 自认六缺口的逐条清偿（su 统一 / 授权缓存 / 挂载自愈 / 冒烟探针 / CA bundle / 遥测接活） |
> | ③ | [`permission-degradation-ladder.md`](permission-degradation-ladder.md) | 运行时纵深：未授权诚实降级 —— 六域声明式阶梯 + 选路归一 + 错误类型化 + 能力面统一 |
> | ④ | [`readme-v3-beautify.md`](readme-v3-beautify.md) | 展示层：README v3 美化 —— 数据治理 → 素材激活 → 结构重组 → 视觉升级 |
>
> **推荐阅读顺序**：本文先行（②③④ 的「实施分解」节均按本文的任务包结构撰写，
> 每包 ≤3 文件 / 验收可脚本化 / 回滚无残留）→ ② 与 ③ 可任选或并行（二者共享
> `SuAuthorizationCache` 与统一 su 候选表，交叉点与边界已在 ③ 的 §8 声明）→
> ④ 独立（展示层，随时可做）。四份可独立采纳，不互相阻塞。
>
> **系列来源**：源自仓库主人 2026-11 的方案讨论链（chroot 实现 → Ubuntu 本地
> 化 → 最优方案创新 → Agent 任务化执行 → 未授权降级语义 → README 美化参考），
> 逐环收敛为可评审、可分包实施、可独立回滚的 spec。

> 状态：**Spec（待评审）**。回答一个反复出现的问题：仓库主人审阅一份
> spec（如 `docs/terminal/TERMINAL_CHROOT_MATURITY_T99.md`）后说
> 「我用 agent 完成」——那么这份 spec 必须先被分解成**Agent 真正能
> 独立执行、独立验证、独立回滚**的任务包，而不是停留在人类工程师
> 的叙事粒度。本篇把这个分解动作本身规范化，让任何 spec 都能机械地
> 翻译成任务包清单。
>
> 关联：T76（`docs/task-execution-architecture.md` 任务运行时）·
> Issue #147 v3（SubAgentRunner）· `AGENTS.md`（仓库协作规则，Rules
> 系统自动注入）· `docs/long-tasks.md`（长任务中心）·
> `docs/permission-degradation-ladder.md`（权限降级）· T99（首个按本
> 规范分解的消费方）。

---

## 0. 一句话定位

spec 写给评审者，任务包写给 agent。**任务包 = spec 的某个改进项在
「一次连续的 Agent 会话 + 一次 PR + 一次 revert」边界内的完整切片**。

## 1. 为什么需要本规范（现状缺口）

仓库已有一套很强的执行基础设施，但「方案 → 执行」的翻译层没有规范：

| 已有设施 | 提供什么 | 不提供什么 |
|----------|----------|------------|
| TaskRuntime（T76） | 状态机/检查点/崩溃恢复/journal | 不回答「任务怎么切才适合 agent」——它执行任意粒度的任务 |
| SubAgentRunner（#147） | 隔离上下文的探索/调研/评审子代理 | 主代理与子代理的**任务边界**由调用方（模型）即兴决定 |
| AGENTS.md | 仓库纪律（CI 门禁/Kotlin 陷阱/测试纪律）注入会话 | 纪律是约束不是分解法 |
| 长任务中心（v1.2） | 留档/检查点/复制/续跑 | 记录已完成任务，不产生任务 |
| T 系列文档惯例 | 审计→方案→已知限制的写作规范 | spec 的「实施分解」节格式不统一（T99 之前各写各的） |

实际后果（观察自仓库历史 PR）：大特性 PR 动辄数十文件、数千行 diff，
review 负担重；agent 中途失败时恢复成本高（journal 里 RUNNING 态的
operation 是 UNKNOWN，恢复策略只能 VERIFY——切片越小，UNKNOWN 面
越小，恢复越廉价，见 `task/RecoveryPolicy.kt` L10-21 的决策矩阵）。

## 2. 任务包定义（核心契约）

一个任务包是满足以下**全部**条件的最小交付单元：

```yaml
# 概念模型（物理形态是 spec 里的一节 markdown，不是新文件格式）
id: su-auth-cache            # 短横线小写，全局唯一，PR 分支名同源
goal: 一句话目标（动词开头）   # 「su 授权结果进入 30s TTL 缓存」
scope:
  files:                     # 硬边界：只许改这些文件（新增文件也要列出）
    - platform/privilege/src/main/kotlin/.../SuAuthorizationCache.kt
    - platform/terminal/src/main/kotlin/.../chroot/LinuxChrootBackend.kt
  modules: [platform:privilege, platform:terminal]   # 受影响 Gradle 模块
acceptance:                  # 可脚本化验收，全部通过才算完成
  - command: ./gradlew :platform:privilege:testDebugUnitTest
    expect: exit 0
  - command: ./gradlew :platform:terminal:testDebugUnitTest
    expect: exit 0
  - rule: LinuxChrootBackendTest 新增授权三态矩阵（GRANTED/DENIED/UNKNOWN）
rollback:                    # 回滚 = git revert 单 commit，无残留
  - 单 commit 交付（main 合并策略 squash）
risks:                       # 已知风险与自愈路径
  - DENIED 误报 → TTL 30s 自愈 + refreshStatus 重置
size:
  main_lines: ~200           # 预估主源码行数
  test_lines: ~250
dependencies: [su-unify]     # 前置任务包 id（DAG 边）
```

### 2.1 六条硬约束（缺一就不是合法任务包）

1. **≤3 个改动文件**（不含测试）——超过说明切片太大，继续拆；
2. **验收可脚本化**——每条验收要么是 shell 命令 + 期望退出码，要么是
   「测试文件 X 新增断言组 Y」这种可 grep 的客观陈述。**「代码看起来
   对了」不是验收**；
3. **回滚无残留**——单 commit squash 合并；不引入跨任务包的中间状态
   （例如 P1 改接口 P2 才接线这种「半成品上 main」绝对禁止——P1 必须
   自带接线与测试）；
4. **依赖显式声明**——DAG 边写明，禁止隐式依赖（「顺手改了隔壁文件」）；
5. **规模有上限**——main 源码 ≤400 行/包（仓库单文件预算 1200 行的
   1/3），超出则说明这包承载了两个关注点；
6. **真机依赖显式标注**——凡验收需要真机的（Root 设备/ptrace 受限
   机型），任务包标注 `device-required: true` 并在 PR 文末写
   NOT VERIFIED 声明（`docs/ubuntu-lifecycle.md` L73-81 惯例），
   **不阻塞合并但阻塞宣称完成**。

### 2.2 与既有设施的映射

| 任务包概念 | 落到哪个既有设施 |
|------------|------------------|
| goal + acceptance | PR 模板 `.github/PULL_REQUEST_TEMPLATE.md` 的 What & Why / Testing 节 |
| scope.files | PR diff 本身 + `scripts/guard-rails`（若存在路径白名单机制则对接） |
| dependencies | PR 描述里的「依赖 PR #N」链接 + 合并顺序（仓库主人执行） |
| 执行载体 | 主代理直连 TaskRuntime（大包）或 SubAgentRunner `Custom` 类型（探索/评审包） |
| journal 恢复 | `ToolExecutionPolicy` 的幂等分类 + `RecoveryPolicy` UNKNOWN→VERIFY |
| changelog 片段 | `.changelog/unreleased/<date>-<id>.md`，`<!-- pr: <id> -->` 标记 |

## 3. 分解方法（spec 作者操作手册）

拿到一份 spec 的「实施分解」节，按以下顺序机械执行：

### 第一步：按数据流切，不按层切

错误的切法（按架构层）：「P1 改 privilege 模块 / P2 改 terminal 模块 /
P3 改 app 模块」——层间切片必然出现跨包接口中间态，违反硬约束 3。

正确的切法（按数据流闭环）：每个包 = 一个「信号从产生到消费」的完整
闭环。以 T99 为例：

- `su-unify` = 发现逻辑（privilege 内部）→ 两个消费方接线 → 测试全绿：闭环；
- `su-auth-cache` = 缓存状态机（privilege）→ availability 消费（terminal）→ 负反馈修正：闭环；
- `chroot-telemetry` = 旗标产生（EnvironmentStateUpdater）→ prompt 摘要消费：闭环。

### 第二步：为并行性标注 DAG

无依赖的包显式标 `(可并行)`——供仓库主人并行开多个 agent 会话（每个
会话一个包，互不踩文件）。T99 的 P3/P4/P5 三包零交集，可三会话并行。

### 第三步：把「验证」翻译成 agent 可自查的命令

agent 完成一个包后，必须能**自己**跑验收（本地验证顺序，AGENTS.md
「本地验证」节）：改 core 层跑模块 test，改 app 层跑
`:app:compileDebugKotlin`。spec 作者的责任是把验收命令写死在包里，
不留「酌情验证」的模糊空间。

### 第四步：标注风险自愈路径

每条 risk 必须写「失败了系统会怎样」——这决定 agent 遇到失败时是
重试、降级还是上报。对齐 `RecoveryPolicy` 的三分类：RETRY（幂等，
直接重跑）/ VERIFY（非幂等，先查效果）/ 上报（AskUser 族）。

## 4. 执行协议（agent 会话怎么消费任务包）

```
┌─ 会话启动 ─────────────────────────────────────────────┐
│ AGENTS.md 自动注入（Rules v1.1 #164）                    │
│ 任务包内容粘贴/引用（spec 章节 URL 或本地路径）           │
├─ 执行中 ───────────────────────────────────────────────┤
│ 1. 读 scope.files 现状（不许越界改文件）                  │
│ 2. Kotlin 陷阱清单自查（AGENTS.md「Kotlin 陷阱」5 条）    │
│ 3. 实现 + 测试（测试纪律：无 mock 框架，手写 fake/假钟）  │
│ 4. 跑 acceptance.command 逐条核对                        │
│ 5. 文件预算/反模式/括号平衡三自查（AGENTS.md CI 门禁）    │
├─ 交付 ─────────────────────────────────────────────────┤
│ 单 commit：feat(scope): 中文摘要 —— 关键词 / 关键词       │
│ PR 描述按模板四节 + changelog 片段 <!-- pr: <id> -->     │
│ device-required 项 → 文末 NOT VERIFIED 声明              │
├─ 失败恢复 ─────────────────────────────────────────────┤
│ 会话内失败 → TaskRuntime journal 恢复（UNKNOWN→VERIFY）  │
│ 会话死亡 → 长任务中心续跑（buildResumePrompt）           │
│ 包级返工 → revert 单 commit，任务包重派                   │
└─────────────────────────────────────────────────────────┘
```

**PR 由仓库主人合并（agent 只提交不合并）**——AGENTS.md 既有纪律，
任务包协议不改变这一点。

## 5. 反模式清单（历史教训）

| 反模式 | 为什么错 | 正确姿势 |
|--------|----------|----------|
| 「P1 先上接口，P2 再接实现」 | main 上出现死接口（仓库曾清理过 `ShellStreamSource` 三件套死代码，T92 §4） | 每包自带消费方与测试 |
| 验收写「功能正常」 | agent 无法自查，等于没有验收 | 命令 + 期望退出码 / 可 grep 断言 |
| 一包跨 5+ 文件 | review 失效、revert 粒度失效 | 按数据流再切 |
| 隐式依赖隔壁包的内部状态 | 并行会话互相踩 | 依赖只能走公共契约（接口/状态流） |
| 真机验证冒充已验证 | 违反仓库诚实哲学（NOT VERIFIED 声明就是为此存在） | device-required 标注 |
| spec 把「后续工作」含糊带过 | 后续工作 = 无验收 = 不可交付 | 要么进 DAG（有验收），要么进「已知限制」（显式不做） |

## 6. 与 T99 的关系（首个应用实例）

`docs/terminal/TERMINAL_CHROOT_MATURITY_T99.md` §5 的 P1-P6 是本规范
的首个完整应用：六包全部满足六条硬约束（最大 P2 = 2 个改动文件、
main ~200 行）、DAG 为 P1→P2→(P3∥P4∥P5)→P6、真机依赖项全部标注。
本规范被采纳后，后续所有带「实施分解」节的 spec 应遵循同样结构。

## 7. 测试与验证本规范自身

规范类文档的验收是**一致性审计**而非单测：

- [ ] T99 §5 六个任务包逐包核对六条硬约束（本 PR 已完成自检）；
- [ ] `docs/pr-body.md`、`.github/PULL_REQUEST_TEMPLATE.md` 与本规范
  的 PR 节字段一一对应（What & Why ↔ goal；Testing ↔ acceptance；
  Quality gates ↔ 硬约束 5 的行数预算）；
- [ ] AGENTS.md 的本地验证顺序能覆盖本规范所有 acceptance 命令形态
  （模块 test / :app:compileDebugKotlin 两类）。

## 8. 已知限制

1. 任务包物理形态是 spec 内的 markdown 节，**不引入新文件格式/解析器**
   （防御式哲学：spec 是给人读的，结构化程度止步于人可核对）；
2. DAG 的「可并行」标注基于文件零交集推断，**未做模块级构建锁验证**
   （两个并行会话各自跑 gradle 互不影响——Gradle daemon 隔离天然满足，
   但 Kotlin/KSP 共享缓存目录在极端情况下可能竞争，遇一例再治理）；
3. 任务包与 GitHub Project/Issue 的联动是后续工作（当前 PR 描述承载
   依赖关系已够用，不上工具链）。
