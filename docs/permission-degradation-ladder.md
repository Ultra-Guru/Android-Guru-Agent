# Permission Degradation Ladder —— 未授权诚实降级体系

> 状态：**Spec（待评审）**。用户需求原话：「如果用户没有授予权限，则是以
> 没有权限的方式运行」。现状离这句话还差四步：① 权限旗标只做可见性
> 不做决策输入；② 同一语义存在两套并行选路实现；③ 工具失败原因是
> 自由字符串，模型无法可靠区分「没权限」与「真坏了」；④ 没有一处
> 让模型看到「我此刻到底能用什么」的统一能力面。本篇把这四步补齐。
>
> 关联：T92（`docs/terminal/PRIVILEGE_CHAIN_AUDIT_T92.md` 权限链审计）·
> `docs/permission-auto-allow-and-stream-capsules.md`（权限口径 v2）·
> `docs/onboarding-permissions.md`（引导期八步授权）·
> `docs/capability-introspection.md`（能力自省）· T99（chroot 深化，
> 共享 §B 缓存语义）· `docs/terminal/TERMINAL_CHROOT_BACKEND_T98.md` §5.1。

---

## 0. 一句话定位

「没有权限的方式」= **每个动作都在当前真实授权状态下，沿声明式降级
阶梯走到最高可执行层级；走不到任何层级时，以类型化错误如实告知
（含修复指引），绝不伪造成功、绝不静默吞掉、绝不反复撞墙**。

## 1. 现状盘点（main 分支逐一核对）

### 1.1 已具备的地基（不动它们，站在上面）

| 设施 | 位置 | 提供 |
|------|------|------|
| 三级权限链探测 | `platform/privilege/PrivilegeDetector.kt` | ROOT > SHIZUKU > NORMAL_SHELL，30s TTL 缓存，`su -c id` 实测语义 |
| 权限状态流 | `DefaultPrivilegeManager.kt` | `rootAvailable / shizukuAvailable / accessibilityAvailable` 三个 StateFlow，binder 死亡/授权结果回灌 |
| 环境遥测三态 | `core/tool-registry/.../ToolEnvironmentState.kt` | true / false / **unknown（fail-open）**，TTL 过期回退 unknown |
| 环境门禁 | `ToolEnvironmentGate` | `requiredEnv` 声明 + `fixHint` 修复指引 + 「Do not retry」防撞墙 |
| 能力自省 | `docs/capability-introspection.md` | 权限阶梯 / 能力报告 / 市场检索 / 扩展攻略 |
| 引导授权 | `docs/onboarding-permissions.md` | 八步授权 + ON_RESUME 自动回填 |

### 1.2 四个缺口（本篇要清偿的）

**缺口 ① —— 降级阶梯散落在各工具的 if/else 里，没有声明式契约。**

同一个「UI 点击」语义，三处实现三种写法：

- `DefaultPrivilegeManager.executeUiAction`（L208-224）：a11y 语义 > root input > shizuku input——**有阶梯**；
- `UiTapTool`（`core/tool-registry/.../builtin/UiTools.kt` L86-101）：a11y 优先，回落 `shellExecutor("input tap …")`——shellExecutor 内部再走 PrivilegeDetector 三级——**阶梯藏在两层调用之后**；
- `ScreenshotTool`（同文件 L385-412）：a11y takeScreenshot > root screencap > shell 兜底，两段失败并列上报——**最诚实的一版**，但格式是特例不是惯例。

后果：新增特权工具时，作者只能去读三个范例各抄一部分；「没权限时
行为」从未被统一审计过。

**缺口 ② —— root/shizuku 旗标接入了可见性，但没有任何门禁/决策消费。**

`EnvironmentStateUpdater.kt` L39-41 自述：「当前无任何工具把它们声明为
requiredEnv（工具都自带降级链），接线后仅提升 prompt 可见性」。而
`EnvironmentAwareTool` KDoc（L162-169）的纪律是「能优雅降级的工具
绝不能声明能力」——**两边都对，但合起来意味着：降级阶梯本身永远不
会被系统知道，只活在工具内部**。模型每轮看到 `root=off` 却不知道
这会让 `UiTap` 走哪条路、`Screenshot` 是否还活着。

**缺口 ③ —— 两套并行选路，行为差异只有注释知道。**

- `PrivilegeDetector.executeShell`（L129-207）：root > shizuku > **`sh -c` 本地兜底**；
- `DefaultPrivilegeManager.executeShell`（L112-128）：root > shizuku > **直接失败**（「面向必须有权限的调用方」）。

同一台无 root 无 shizuku 的设备：走 Detector 的调用拿到本地 shell 结果
（可能权限不足报错文本），走 Manager 的调用拿到固定错误字符串。模型
看到两种错误无法关联成因。chroot 后端（T98）为避免卷入这个分叉，
干脆自持 `SuBinaryLocator`——又造出第三处 su 候选表（T99 §A 清偿）。

**缺口 ④ —— 失败原因是自由字符串，无类型化错误码。**

对比终端侧已建立纪律：`TerminalError:RootfsNotReady`、
`ChrootError:SU_NOT_FOUND`（T98 命名约定，模型可依据前缀路由到修复
动作）。而特权工具侧：`"Error: No privilege available. Need Root or
Shizuku."`、`"Accessibility service not running"`、`"Error: screenshot
failed. Privileged channel …: unavailable"`——三处三种措辞，无命名
空间，无机器可读代码。`fixHint` 机制只覆盖 `requiredEnv` 声明的
9 个旗标，覆盖不到降级阶梯的中途失败。

## 2. 目标与非目标

**目标**：

- H1 **声明式降级阶梯**：每个特权动作的能力需求与回退路径成为工具元数据，一处声明、处处可审计；
- H2 **能力面统一**：模型每轮可见「当前授权状态下每个能力域的真实可用层级」（CapabilityReport 扩展），换授权即刻刷新；
- H3 **选路归一**：两套 executeShell 合并为单一实现，差异显式化为参数而非注释；
- H4 **错误类型化**：权限相关失败统一 `PrivilegeError:<CODE>` 命名空间，含 fixHint 等价指引，模型可靠区分「没权限（可引导/可降级）」与「真故障（重试/上报）」。

**非目标**：

- 不把 root/shizuku 旗标变成 requiredEnv 门禁——降级阶梯哲学不变（能降级就不门禁），H2 是可见性不是拦截；
- 不做「自动引导用户授权」的弹窗轰炸——引导只在模型/用户显式发起需要特权的动作且当前层级不足时发生（既有 AskUser 通道承载）；
- 不动 PermissionDecider 的模式语义（BYPASS/PLAN/DEFAULT/ACCEPT_EDITS 四模式与 isDeleteLike 口径，`docs/permission-auto-allow-and-stream-capsules.md` 已冻结）；
- 不引入权限请求的 Android 运行时弹窗自动化（POST_NOTIFICATIONS 等既有 onboarding 机制覆盖）。

## 3. 方案设计

### §A 声明式降级阶梯 —— `PrivilegeLadder`（清偿缺口 ①）

**落点**：`core/tool-registry/.../ToolEnvironmentState.kt` 旁新增
`PrivilegeLadder.kt`（纯 JVM，无 Android 依赖——供 core 层工具与
platform 层管理器共享）。

```kotlin
/** 能力域：一组共享同一降级阶梯的动作语义 */
enum class PrivilegeDomain {
    SHELL_EXEC,      // 命令执行：root > shizuku > local-sh
    UI_ACTION,       // UI 操控：a11y > root-input > shizuku-input
    SCREENSHOT,      // 截屏：a11y > root-screencap > shell
    UI_TREE,         // 控件树：a11y（无回退——层级 0 即终止）
    FILE_ACCESS,     // 全盘文件：all-files > SAF 桥 > app 沙箱
    PACKAGE_OPS,     // 包管理：root/shizuku pm > app 内 PackageManager（只读子集）
}

/** 阶梯声明：按优先级排列的执行层级 */
data class LadderTier(
    val name: String,            // "root" / "shizuku" / "accessibility" / "local-shell" / …
    val requirement: () -> Boolean,   // 廉价判定（读 StateFlow 快照，绝不 exec）
)

data class PrivilegeLadder(
    val domain: PrivilegeDomain,
    val tiers: List<LadderTier>,     // 有序，首元素 = 最高层级
) {
    /** 当前最高可执行层级：null = 全梯不可用（诚实失败态） */
    fun highestAvailable(): LadderTier?
    /** 剩余可用层级（供错误信息告知「还能怎么干」） */
    fun availableTiers(): List<LadderTier>
}
```

**阶梯注册表**（单一事实源，`Ladders.kt`）——把现状三处 if/else 翻译
成数据：

| 域 | 阶梯（现状翻译） | 现状出处 |
|----|------------------|----------|
| SHELL_EXEC | root > shizuku > local-shell | PrivilegeDetector L170-207 |
| UI_ACTION | accessibility > root-input > shizuku-input | DefaultPrivilegeManager L208-224 |
| SCREENSHOT | accessibility > root-screencap > shell | DefaultPrivilegeManager #239 降级链 |
| UI_TREE | accessibility（单层） | DefaultPrivilegeManager L345-369 |
| PACKAGE_OPS | root/shizuku-pm > app-pm-readonly | AppListTool 等的现状隐式行为 |
| FILE_ACCESS | all-files > app-sandbox（SAF 桥由工作区机制另行承载，不进本表） | onboarding 工作区选择 |

**工具侧消费**：`AgentTool` 实现可选择暴露
`val privilegeDomain: PrivilegeDomain?`（默认 null = 无特权需求）。
**这不是门禁**——工具内部仍自行执行降级（行为不变），但元数据让
系统与模型知道「这个工具依赖哪个域的阶梯」（H2 的数据源）。

### §B 选路归一 —— `ShellRouter` 单实现（清偿缺口 ③）

**落点**：`platform/privilege` 新增 `shell/ShellRouter.kt`，两个既有
入口变薄壳：

```kotlin
class ShellRouter(
    private val detector: PrivilegeDetector.type,      // 探测复用
    private val authCache: SuAuthorizationCache,       // T99 §B（共享）
) {
    enum class FallbackPolicy { TO_LOCAL_SHELL, NONE }   // 差异显式化

    suspend fun execute(
        command: String,
        timeoutMs: Long,
        workDir: File?,
        fallback: FallbackPolicy,
    ): ShellResult
}
```

- `PrivilegeDetector.executeShell` → `router.execute(…, TO_LOCAL_SHELL)`（行为不变）；
- `DefaultPrivilegeManager.executeShell` → `router.execute(…, NONE)`（行为不变——「必须有权限」语义保留）；
- **审计日志单点化**：`privilege-chain via=… exit=… Nms cmd=…` 只在 Router 打一条（现状两处各自打，格式微差）；
- T99 的 `SuAuthorizationCache` 在此汇入——三个 su 消费方（Detector 实测 / chroot availability / Router）共享一份授权状态。

**兼容承诺**：两个既有入口的签名与返回值不变（冻结公共 API 纪律），
差异只在实现层收敛。

### §C 错误类型化 —— `PrivilegeError` 命名空间（清偿缺口 ④）

**对齐终端侧既有惯例**（`TerminalError:RootfsNotReady` /
`ChrootError:SU_NOT_FOUND`），特权侧统一前缀 `PrivilegeError:`：

| 代码 | 语义 | fixHint（模型可执行指引） |
|------|------|--------------------------|
| `PrivilegeError:NO_CHANNEL` | SHELL_EXEC 全梯不可用且 FallbackPolicy=NONE | "No root or Shizuku available. Ask the user to enable one (Settings → Permissions), or use TerminalExec inside Ubuntu instead." |
| `PrivilegeError:A11Y_NOT_RUNNING` | UI_TREE/UI_ACTION 的 a11y 层缺失 | "Accessibility service not enabled. Guide the user: Settings → Accessibility → … , or fall back to coordinate-based tools if root exists." |
| `PrivilegeError:TIER_DEGRADED` | 动作在非首选层级完成（信息性，非失败） | 不需要修复——输出尾部标注 `[executed via: shizuku (degraded from root)]`（对齐 ShellExecute 的 via 标注惯例，T92 §3） |
| `PrivilegeError:ALL_TIERS_FAILED` | 阶梯逐层尝试全部失败 | 并列各层失败原因（ScreenshotTool #239 惯例推广：可诊断，不互相遮蔽） |
| `PrivilegeError:SU_NOT_AUTHORIZED` | su 存在但授权被拒 | 与 T99 §B 的 ChrootError 同义——两个命名空间并存时以 domain 区分，fixHint 互相指引 |

**落点**：`platform/privilege` 新增 `PrivilegeError.kt`（sealed +
`fun asToolMessage(): String` 统一格式化）。消费方迁移清单（本 spec
实施时逐个替换自由字符串）：

1. `DefaultPrivilegeManager.executeShell` 的固定错误串 → NO_CHANNEL；
2. `getUiTree` 的 `"Accessibility service not running"` → A11Y_NOT_RUNNING；
3. `ScreenshotTool` 的两段拼接 → ALL_TIERS_FAILED（保留两段细节文本，仅加类型前缀）；
4. `UiTap` 走到本地 shell 失败时的裸 shell 错误 → 前缀 NO_CHANNEL（本地 shell 是阶梯一层，它的失败 = 全梯失败的最后一层）。

### §D 能力面统一 —— CapabilityReport 的权限行（清偿缺口 ②）

**落点**：扩展 `docs/capability-introspection.md` 定义的能力报告工具
（CapabilityIntrospectionTool），追加「权限能力域」分区：

```
=== Privilege Domains (current state) ===
shell_exec:    root=on  shizuku=off local=on   → tier: root
ui_action:     a11y=on  root=on               → tier: accessibility
screenshot:    a11y=on  root=on  shell=on     → tier: accessibility
ui_tree:       a11y=on                         → tier: accessibility
package_ops:   root=on  app=on                → tier: root
chroot(Ubuntu): su=found auth=granted rootfs=ready   → tier: ready
```

- **数据源**：§A 阶梯注册表 + PrivilegeManager 三流 + T99 §F 的 chroot 旗标——**快照式查询，不 exec**；
- **刷新时机**：授权状态流变化（binder 回灌 / a11y sticky / refreshStatus）→ 下一次工具调用即见新状态；模型也可显式调 CapabilityIntrospection 工具拉取；
- **进 prompt 的形态**：environment 摘要已有 `root=off shizuku=on` 行（T92 接活），追加**阶梯结论**而非原始旗标：`privilege: shell=root ui=accessibility tree=unavailable`——模型不需要自己从旗标推导阶梯。

### §E 授权引导闭环（贯穿 ①-④ 的用户体验层）

现状：模型遇到权限失败 → 自由文本猜测 → 用户可能看不懂。目标态：

```
模型发起特权动作 → 阶梯全灭 → PrivilegeError:<CODE> + fixHint
   → 模型经 AskUser 通道向用户转述（结构化：缺什么 / 去哪开 / 开了之后能干嘛）
   → 用户去系统设置授权 → 状态流回灌（ON_RESUME / binder 监听）
   → CapabilityReport 即刻反映 → 模型下一轮继续原任务（无需重试失败调用）
```

关键差异：引导话术由 fixHint 单源提供（zh/en 随语言管理器），
而不是各工具自由发挥；「开了之后能干嘛」引用 §D 的域描述。

## 4. 架构总览

```
┌──────────────── Agent / 模型 ────────────────────────────────┐
│ prompt 摘要: privilege: shell=root ui=accessibility tree=off  │
│ CapabilityIntrospection 工具 → §D 权限域分区                  │
│ 工具失败 → PrivilegeError:<CODE> + fixHint → AskUser 引导     │
├──────────────────────────────────────────────────────────────┤
│ core/tool-registry（元数据层）                                 │
│ PrivilegeLadder.kt（§A 声明）+ AgentTool.privilegeDomain（可选）│
│ ToolEnvironmentGate 不变（fail-open 哲学不动）                 │
├──────────────────────────────────────────────────────────────┤
│ platform/privilege（执行层）                                   │
│ ShellRouter（§B 单实现）+ SuAuthorizationCache（T99 §B 共享）  │
│ PrivilegeError.kt（§C 类型化）+ Ladders.kt 注册表              │
├──────────────────────────────────────────────────────────────┤
│ 既有地基（不动）                                                │
│ PrivilegeDetector 探测 / DefaultPrivilegeManager 状态流       │
│ PermissionDecider 模式语义 / onboarding 八步授权              │
└──────────────────────────────────────────────────────────────┘
```

## 5. 实施分解（Agent 任务包，对齐 `docs/agent-task-packages.md`）

依赖：Q1 → Q2 →（Q3 ∥ Q4）→ Q5。

### Q1 `ladder-registry` —— 阶梯声明与注册表（§A）
- **改**：新增 `core/tool-registry/.../PrivilegeLadder.kt` + `Ladders.kt`（六域注册）
- **验收**：`PrivilegeLadderTest`（六域阶梯顺序快照、highestAvailable 三态、availableTiers 过滤、廉价判定不 exec——用注入的布尔函数证明零副作用）；`:core:tool-registry:test` 全绿
- **规模**：~150 行 main + ~180 行 test

### Q2 `shell-router` —— 选路归一（§B）
- **改**：新增 `platform/privilege/shell/ShellRouter.kt`；Detector/Manager 两入口改薄壳委托
- **验收**：`ShellRouterTest`（两 FallbackPolicy × 三通道可用性矩阵 = 6 用例、审计日志恰好一条、TTL 缓存命中不打重复日志）；`PrivilegeDetectorAuditTest` 既有 3 项不红（日志格式兼容）
- **规模**：~200 行 main + ~250 行 test
- **风险**：行为漂移 → 两入口各留一条「行为不变」快照测试（同输入同输出逐字节比对，迁移期守卫）

### Q3 `privilege-error` —— 错误类型化迁移（§C，可与 Q4 并行）
- **改**：新增 `PrivilegeError.kt`；四处自由字符串替换（§C 迁移清单 1-4）
- **验收**：`PrivilegeErrorTest`（五码格式化快照含 fixHint）；既有依赖错误文本的测试同步更新并在 PR 列明（可 grep 审计）
- **规模**：~120 行 main + ~150 行 test

### Q4 `capability-rows` —— 能力面权限行（§D，可与 Q3 并行）
- **改**：CapabilityIntrospection 工具输出追加权限域分区；environment 摘要追加阶梯结论行
- **验收**：快照测试（六域 × 代表性旗标组合的渲染输出）；EnvironmentStateUpdater 测试更新
- **规模**：~150 行 main + ~180 行 test
- **依赖**：Q1（阶梯数据源）

### Q5 `guidance-copy` —— 引导话术单源（§E 收尾）
- **改**：fixHint 文案表（zh/en）+ AskUser 转述模板
- **验收**：文案表完整性测试（五码 × 双语非空）；无逻辑改动
- **规模**：~80 行 + ~60 行 test

## 6. 测试策略

| 层 | 手段 | 覆盖 |
|----|------|------|
| JVM 单测 | 注入布尔函数证零副作用 / 假钟 TTL / 快照比对 | Q1-Q5 全量（延续无 mock 框架纪律：手写 fake 状态流） |
| 行为不变守卫 | 迁移前后同输入同输出快照（Q2 两入口） | 选路归一无漂移 |
| 真机矩阵 | 无 root 无 shizuku（阶梯全灭引导）/ 仅 shizuku / 仅 root / a11y 关闭四态 | §E 闭环 + §D 能力行——**device-required 标注，NOT VERIFIED 兜底** |
| CI | 既有 `:platform:privilege:testDebugUnitTest` + `:core:tool-registry:test` 步骤 | 无新增 job |

## 7. 已知限制与路线图

1. **FILE_ACCESS 域的 SAF 桥层级**（工作区机制）暂不进阶梯表——SAF 授权
   是 per-tree 的，粒度与三级链不同构，硬塞会污染模型心智（后续单独
   设计，候选名 `FileAccessLadder v2`）；
2. **`PrivilegeError:TIER_DEGRADED` 是信息性标注**，不是失败——部分
   消费方（依赖退出码的批处理工具）需要适配「成功但降级」语义，
   迁移期 TIER_DEGRADED 仅在 ShellExecute 输出尾部标注，不改变返回
   结构（避免破坏性变更）；
3. **chroot 域的 SU_NOT_AUTHORIZED 与 T99 §B 的 ChrootError 同义并存**——
   按域区分（terminal 侧 ChrootError / privilege 侧 PrivilegeError），
   fixHint 互相指引；若后续统一命名空间，另立 spec（影响面大）；
4. **能力行的 zh/en 双语**：环境摘要当前是英文（模型消费），CapabilityReport
   工具输出同样英文；面向用户的引导话术走 fixHint 双语表——三层受众
   不混写。

## 8. 与三个相邻 spec 的边界

| 相邻 spec | 共享点 | 边界 |
|-----------|--------|------|
| T99（chroot 深化） | SuAuthorizationCache / su 候选表 / chroot 遥测旗标 | T99 管 terminal 侧后端可用性；本篇管 privilege 侧通用语义——cache 落 privilege 模块，双 spec 共同引用 |
| `docs/permission-auto-allow-and-stream-capsules.md` | 「权限」二字 | 那篇管**模型动作权限**（工具要不要问用户）；本篇管**系统权限**（App 有没有 root/shizuku/a11y）——两个正交维度，不合并 |
| `docs/onboarding-permissions.md` | 引导授权 | 那篇管首启引导页；本篇 §E 管运行时引导闭环——fixHint 话术表可复用那篇的文案资产 |
