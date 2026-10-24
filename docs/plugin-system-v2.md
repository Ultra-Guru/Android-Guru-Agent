# Plugin System v2：双类型插件体系（功能性 × APK）

> 状态：已落地（v2，2026-10）
> 主题：统一插件架构——功能性插件（进程内声明式贡献点，类 coding 平台扩展）
> 与 APK 插件（独立 APK + AIDL 跨进程代码）双形态共存，统一目录 / 统一
> 生命周期 / 统一信任边界哲学。
> 前置：[hub-ecosystem.md](hub-ecosystem.md)（技能 / MCP 仓库生态）
> · `plugin-sdk/plugin-api`（AIDL 契约）· `plugin-sdk/plugin-host`（宿主运行时）

---

## 0. 一句话

插件分两种：**功能性插件是数据**（一个 plugin.json，声明「贡献点」——
工具宏 / 斜杠指令——宿主适配成进程内能力，零代码执行面）；**APK 插件是
代码**（独立 APK，AIDL 跨进程执行，同签名信任）。统一注册表
（`UnifiedPluginRegistry`）把两种形态合并成一份目录、一套启停装卸语义，
市场与斜杠菜单只认门面、不感知形态差异。

## 1. 背景：为什么需要第二种插件形态

v1 的插件只有一种形态：APK 插件（`:plugins:plugin-workflow` 为参考实现）。
它解决了「宿主能力跨进程扩展」，但对**轻量场景**成本过高：

| 场景 | APK 插件的问题 |
| --- | --- |
| 组合既有工具成工作流（导航→抽取→汇报） | 一个 JSON 就能描述的事，要起一个进程、走 AIDL、过签名门 |
| 仓库 / 市场分发（像技能那样一条 JSON） | APK 无法经市场 JSON 通道分发，用户装插件 = 装 App |
| 用户 / agent 自己生成插件 | agent 能写 JSON，不能安全地「安装一个 APK」 |
| 热更通道 | 热更管道搬 APK 的体积与签名约束不现实 |

而既有技能（skill）体系虽然轻，但只贡献**提示词注入**——没有工具贡献、
没有斜杠指令贡献、没有启停状态机，不构成「插件」。

**结论**：双形态并存，各就其位——功能性插件覆盖「组合既有能力的声明式
扩展」（VS Code 扩展的 contributes 段哲学），APK 插件覆盖「宿主外的原生
代码扩展」（重能力：独立进程、自有依赖、UI 之外的长时任务）。

## 2. 架构总览

```
                        市场 UI / 斜杠菜单 / ApexCoreService 启动接线
                                        │ （只认门面，不感知形态）
                    ┌───────────────────▼────────────────────┐
                    │        UnifiedPluginRegistry            │
                    │  catalog: StateFlow<List<UnifiedEntry>> │
                    │  installFunctional / setEnabled /       │
                    │  uninstall / functionalSlashCommands    │
                    └──────────┬──────────────────┬───────────┘
              FUNCTIONAL 形态   │                  │   APK 形态（既有链路不动）
        ┌──────────────────────▼─────┐   ┌────────▼──────────────────┐
        │ FunctionalPluginStore      │   │ PluginManager（v4 信任门）│
        │  filesDir/plugins/         │   │  queryIntentServices 发现  │
        │  functional/<id>/          │   │  bindService + AIDL        │
        │    plugin.json + .disabled │   │  签名门/版本门/钳制 → REPLACE │
        │ MacroToolAdapter           │   │  HostFallbackTool 降级     │
        │  变量插值/短路/钳制        │   │  IApexPluginHost 宿主桥    │
        └─────────────────┬──────────┘   └────────┬──────────────────┘
                          │                       │
                          ▼                       ▼
                   ToolRegistry（REJECT 注册）   ToolRegistry（REPLACE 注册）
```

模块落点：

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 契约 | `plugin-sdk/plugin-api/.../PluginKind.kt` | FUNCTIONAL / APK 枚举（含对照表 KDoc） |
| 契约 | `PluginContract.kt` | HOST_API_VERSION=2、manifest 文件名 / 目录 / 禁用标记 / 宏步数上限 / 变量钳制 / id 命名规范 |
| 宿主 | `plugin-host/.../functional/PluginManifest.kt` | 宽松 @Serializable manifest 模型（贡献点 schema） |
| 宿主 | `.../FunctionalPluginStore.kt` | 安装目录管理（校验门链 / 原子写 / 升级比较 / BROKEN 隔离） |
| 宿主 | `.../MacroToolAdapter.kt` | 宏工具 → AgentTool（执行引擎：插值 / 短路 / 钳制） |
| 宿主 | `.../UnifiedPluginRegistry.kt` + `UnifiedPluginTypes.kt` | 统一门面 + 状态机 + APK 侧纯数据接口 |
| 宿主 | `.../DefaultApkPluginSource.kt` | PluginManager.loadedPlugins → 纯数据流投影 |
| 装配 | `app/.../di/PluginModule.kt` | Hilt 接线（store 根目录 / @MacroStepExecutor / 后台 scope / 日志桥） |
| 接线 | `ApexCoreService` | 启动激活已启用功能插件（与 APK 自动加载同哲学） |
| UI | `MarketViewModel` + `MarketInstalledTabs` + `MarketDialogs` | 统一目录管理视图（双形态徽章 / 启停 / 卸载 / JSON 导入） |
| UI | `SlashMenuProvider` + `SlashCommandRouter` | 功能性指令进插件类目，`/plugin:<id>` 注入定制 prompt |

## 3. 统一 manifest（plugin.json）

两种形态共用同一字段词表（id / name / version / versionName /
minHostVersion / description / author / tags / scope），功能性插件多了
`contributions` 贡献点段：

```json
{
  "id": "functional.daily-briefing",
  "kind": "functional",
  "name": "每日简报",
  "version": 1,
  "versionName": "1.0.0",
  "minHostVersion": 1,
  "description": "组合工具生成每日简报",
  "scope": "all",
  "contributions": {
    "tools": [{
      "id": "briefing_run",
      "description": "生成今日简报",
      "parametersSchema": "{...JSON Schema...}",
      "steps": [
        { "tool": "BrowserNavigate", "args": { "url": "{{input.site}}" }, "output": "page" },
        { "tool": "BrowserExtractContent", "output": "body" },
        { "tool": "agent_notify_user", "args": { "message": "今日要闻：{{body}}" } }
      ]
    }],
    "slashCommands": [{ "id": "briefing", "title": "每日简报", "prompt": "..." }],
    "prompts": [{ "id": "guide", "text": "..." }]
  }
}
```

- **宽松解析**（`ignoreUnknownKeys` + 全字段缺省）：未来贡献点与字段向前
  兼容，旧宿主不炸；
- **prompts 为架构预留**：模型已定义、宿主暂不消费（接线路径复用技能
  promptInjection 的引擎注入通道，见 §8 路线图）；
- 完整样本见 `plugins/functional-samples/`（工具宏型 + 纯指令型）。

## 4. 宏语言（工具贡献的执行语义）

宏 = 对**既有宿主工具**的声明式组合调用：

- **变量域**：`{{input}}`（调用入参原始 JSON）/ `{{input.field}}`（顶层
  字段）/ `{{name}}`（前序步骤 `output` 捕获的完整输出）；
- **插值范围**：步骤 `args` 顶层字符串值；嵌套对象与非字符串值原样透传；
- **失败短路**：任一步输出以 `Error` 开头 → 宏立即失败，错误携带步号 +
  工具 id（模型可直接归因重试）；
- **未解析变量 = 诚实失败**：绝不静默空替换（半截参数发给宿主工具会让
  错误延迟且难归因）；
- **体积钳制**：变量 4KB 上限，保头 3KB + 尾 1KB（首部结论 + 尾部状态是
  现实工具输出信息密度最高的两段）；
- **步数上限 16**：安装期强校验 + 运行期兜底双防线。

## 5. 信任边界（双形态对照表——本设计的核心）

| 维度 | FUNCTIONAL（功能插件） | APK 插件 |
| --- | --- | --- |
| 载体 | plugin.json **数据** | 独立 APK **代码** |
| 代码执行面 | **零**（所有执行落在宿主既有工具上） | 插件进程原生代码 |
| 签名门 | 不适用（无代码可签名） | 必须（宿主同 keystore，v4 fail-closed） |
| 版本门 | minHostVersion ≤ HOST_API_VERSION（安装期拒绝） | 同门（注册期拒绝） |
| id 命名门 | 小写点分 + 宿主命名空间保留（防伪装 / 穿越） | 包名体系天然约束 |
| 工具 id 冲突 | **REJECT**（数据永不覆盖二进制能力） | **REPLACE**（同签名信任锚点，v4 钳制继承宿主 metadata） |
| 风险钳制 | max(MEDIUM, 步骤工具风险)；readOnly=false；openWorld=true | max(推断, MEDIUM, 宿主现有)；同款注解钳制 |
| 崩溃半径 | manifest 损坏 = 单插件 BROKEN 隔离 | 插件进程死亡 → 降级 HostFallbackTool |
| 启停 | `.disabled` 标记 + 工具实时注册/回收 | 绑定 / 解绑（APK 留存） |
| 分发 | 市场下载 / JSON 导入 / 热更通道（规划） | 系统 PackageInstaller |

**为什么功能性插件永不 REPLACE**：APK 插件可覆盖内置工具的前提是「其
代码与宿主同签名」（覆盖瞬间捕获宿主 metadata 作信任锚点）。功能性插件
是用户 / 市场导入的**无签名数据**——若允许 REPLACE，一个 JSON 就能把
`Read` 的声明换成恶意描述（模型被骗去传敏感参数）或把 HIGH 风险
工具的描述改向。REJECT 是数据形态下唯一安全的冲突策略；想「覆盖」内置
行为是 APK 插件（同签名代码）才有的权利。

**为什么宏步骤不重复过 UI 门控**：宏工具自身的 metadata 风险已按「步骤
风险最大值」钳制——模型调用宏时，外层执行管线的风险门弹的是宏的真实
风险（含 HIGH 步骤则按 HIGH 确认）。用户确认一次「危险宏」= 已确认其
全部高危步骤；内部步骤再逐个弹窗是把同一决定拆成 N 次打扰。门的位置
在边界（宏调用），不在内部（步骤）。

## 6. 生命周期状态机（统一）

```
            install(json)                setEnabled(true)
  ──▶ INSTALLED ──────────▶ ACTIVE ──────────▶ (回 ACTIVE，工具重放)
  ──▶ (重复装/降级) Unchanged      │ setEnabled(false) / 升级重放 / 卸载
  ──▶ Rejected（人话理由）        ▼
  磁盘损坏 / 身份复核失败 ──▶ BROKEN（如实展示原因，宿主不猜测加载）
```

- **安装即激活**（默认启用态）：与 APK 插件自动加载、MCP「配置即生效」
  同一产品哲学；`ApexCoreService` 启动时 `activateEnabledFunctionalPlugins()`
  幂等补齐（进程重启后恢复激活态）；
- **升级 = 回收 + 重放**：旧贡献物 unregister → 新 manifest 注册（防止旧
  工具残留冒充新版语义）；启停态（`.disabled`）跨升级保留；
- **去激活的回收精度**：按「注册快照」精确 unregister 自己注册的工具——
  REJECT 策略保证注册时就没覆盖过别人的工具位，回收时也就挖不空别人的。

## 7. UI 与接线

- **市场 · 已安装 · 插件**：统一目录（功能 N · APK M 徽章并列）；功能性
  插件启停 Switch（BROKEN 禁用）+ 真卸载；APK 插件保持解绑语义；头部
  「导入插件 JSON」（空态同样露出——冷启动路径不藏在非空态里）；冲突 /
  BROKEN 原因如实进卡片 detail 行；
- **插件 UI 页面（v2.1 双轨）**：带 UI 的插件在插件行出现「打开页面」——
  功能性插件走 `contributions.ui[]`（HTML 入口 + 宿主 `PluginUiPageDialog`
  WebView 渲染：file:// 白名单、无 JS 桥、入口 canonical 复核不逃逸插件
  目录、安装期 entry 路径校验 fail closed）；APK 插件走 service meta-data
  `ui_entries`（`[{title,action}]`，`PluginManager.openApkPluginUi` **签名
  门前置**后 startActivity 跳插件自有 Activity，插件内自由渲染
  Compose/WebView）。轻 UI = 数据（HTML，市场 JSON 通道可分发），重 UI =
  代码（同签名 APK）——与双形态信任边界哲学一致；
- **斜杠菜单 · 插件类目**：功能性指令（READY 态）+ APK 发现条目并列；
  `unified.changes` 流（300ms 去抖）驱动菜单重建——启停插件后菜单即时
  对账，无需重启或手动刷新；
- **`/plugin:<id>` 路由**：命中功能性指令 → 注入插件声明的定制 prompt +
  插件名徽章；未命中 / 旧调用方未携带快照 → 退化为既有通用插件路由文案
  （向后兼容，APK 插件指令零回归）。

## 8. 扩展路线图（按依赖顺序，均向后兼容）

| 阶段 | 扩展点 | 接线位置 |
| --- | --- | --- |
| P1 | `prompts` 贡献点消费（启用态注入系统提示词，对齐技能 promptInjection） | 引擎 buildSystemPrompt + UnifiedPluginRegistry 聚合 |
| P1 | 市场仓库分发（apex-plugin-hub：index.json + 按需拉取，与技能 Hub 同构） | MarketBrowseTabs 插件页 + HubSource 扩展 |
| P2 | `hooks` 贡献点（PreToolUse/PostToolUse 声明式钩子——插件声明关心的工具 id，宿主桥接 HookRegistry） | FunctionalPluginRuntime + HookRegistry 桥 |
| P2 | 宏步骤条件跳过（`when` 字段：`{{var}} == value`）与循环上限（bounded map over 数组变量） | MacroToolAdapter（安装期校验同步扩展） |
| P3 | agent 自生成插件（agent 写 plugin.json → 安装确认门 → installFunctionalPlugin） | 安装确认 gate 复用 MarketInstallManager 三件套 |
| P3 | 热更通道（HotContentStore overlay 重放，对齐技能热更管线） | SkillModule 同构接线 |

贡献点模型的可扩展性锚点：`PluginContributions` 每加一个字段 = 新贡献点
（宽松解析保证旧宿主不炸）；`UnifiedPluginRegistry` 是唯一宿主入口，新
贡献点的注册 / 回收逻辑集中在其 activate/deactivate 两条路径，不向外扩散。

## 9. 测试

| 测试 | 覆盖 |
| --- | --- |
| `PluginManifestTest`（plugin-host） | 宽松解析 / 未知字段兼容 / 非法 JSON → null / 字段回退链 / kind 归一 |
| `FunctionalPluginStoreTest`（plugin-host） | 装卸往返落盘 / 身份门 / 命名空间门 / 版本门 / 贡献点形状门 / 升级语义 / 启停幂等 / 路径穿越 / BROKEN 隔离 / 运行期复核 |
| `MacroToolAdapterTest`（plugin-host） | 步骤顺序与参数 / 变量插值全形态 / 失败短路 / 未解析变量 / 体积钳制 / 风险钳制 / 步数兜底 |
| `UnifiedPluginRegistryTest`（plugin-host） | 安装即激活 / REJECT 不覆盖 / 启停回收与恢复 / 升级重放 / 卸载 / 斜杠快照 / 目录合并 APK / 启动激活门 |

纯 JVM（无 mock 框架，手写 fake：ToolExecutor / ToolRegistry /
ApkPluginSource），对齐仓库测试纪律；51 项断言锁死诚实契约（含测试抓出
并修复的两个实现缺陷：启停幂等破坏、卸载路径穿越防护不严——这正是
「假成功桩无测试报警」的反面教材）。
