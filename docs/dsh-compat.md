# DSH 兼容性台账（docs/dsh-compat.md）

> DSH（DeepSeek Harness）处于 **developer preview**，官方明示会有破坏性变更。
> 本文件是宿主适配的**验证台账**：每个验证结论钉版本、钉日期、钉验证方式。
> 凡触碰 DSH 桥（`core/tool-registry/.../extension/dsh/`、`resources/dsh-host/*.js`）的 PR，
> 开头必须重跑下表 P0 验证项 —— fixture 跑一遍就知道 API 变没变，比人肉盯 changelog 可靠。

## 1. 验证状态矩阵

| 验证项 | 状态 | 验证环境 | 结论 |
|---|---|---|---|
| 插件模块加载（CJS 函数 / CJS 对象） | ✅ 已验证 | 本机 node v24.9.0 + CI（DshNativeHostTest） | loadPlugin 归一正确，插件名解析按形态分治 |
| 插件模块加载（ESM 对象 / ESM 函数） | ✅ 已验证 | 同上 | dynamic import 路径成立 |
| 类形态插件（Cordis Service） | ✅ 如实拒绝 | 同上 | `unsupported-class-form`，不虚报 |
| `ctx.tools.register` 捕获 | ✅ 已验证 | 同上 | defineTool 同形桩 + 裸对象双路捕获 |
| DSH parameters → JSON Schema | ✅ 已验证 | 同上 | 白名单转换 + 宽容降级（exact 标记） |
| `inject` 依赖就绪语义 | ✅ 已验证 | 同上 | 未支持服务 → 不执行 apply，工具面如实为空 |
| apply 中途服务缺失 | ✅ 已验证 | 同上 | 部分捕获 + service-unavailable 归因 |
| 运行时触碰（无 inject 声明） | ✅ 已验证 | 同上 | 触碰记录 + 结构化中止，绝不静默 mock |
| stdin JSON 参数通道 | ✅ 已验证 | ExecEngine + run-tool 双侧 | 中文/引号/换行载荷完整 |
| 有状态插件（闭包 counter） | ⚠️ 冷启动归零 | 同上 | **冷启动 = 无状态插件模式**；状态保留待 P2 持久 Worker |
| `dispose()` 生命周期 | ✅ 结构已就位 | 本机 | effect disposer 收集 + 卸载调用；真实插件级 dispose 待 P0 真机 |
| 真实 `@deepseek-ai/dsh-tools` defineTool | ⏳ 待真机 P0 | PRoot Ubuntu（真机） | **P0 硬门槛**：fixture 已备（fn-cjs），npm 装真实依赖后重跑 |
| 持久 Worker 双向帧 IO | ✅ JVM 侧 | DshBridgeTransportTest（CI 有 node 时端到端） | 长度前缀帧 + 破损 fail-fast + 终态恰好一次 |
| PRoot 内持久进程 | ⏳ 待真机 P0 | PRoot Ubuntu（真机） | P0 第二门槛：起停/取消/崩溃恢复 |
| 静态 AST 分析（acorn） | ⚠️ 结构就位 | analyze 脚本已部署，真机 npm bootstrap 后激活 | acorn 未就绪时诚实降级（analyzer=null），不冒充结论 |

## 2. 钉定版本

| 组件 | 钉定值 | 说明 |
|---|---|---|
| acorn（AST 分析器） | `8.12.1` | `DshHostScripts.ACORN_PINNED_VERSION`；升级须走本表验证 |
| 宿主 Node 最低 | `>= 18` | ESM dynamic import / structuredClone 依赖（`DshCompatibilityResolver.MIN_NODE_MAJOR`） |
| DSH 依赖族已验证范围 | `0.x` | 文献级预填 —— **真机 P0 通过前，MINIMAL 是诚实默认档**；通过后更新此处与 Resolver |
| shim 脚本版本 | `scripts-v1` | JS 语义变更即 bump（`DshHostScripts.SCRIPTS_VERSION`），目录名变 = 全量重部署 |

## 3. 服务支持层级（红线 11 的落点）

| Cordis 服务 | 宿主等级 | 说明 |
|---|---|---|
| `tools` | SUPPORTED | 注册面捕获 + 调用桥（冷启动 P0 / 持久 Worker P2） |
| `logger` / `config` | SUPPORTED | Worker 侧基础实现；config 插件独立命名空间 |
| `effect` | SUPPORTED | 可逆副作用收集 + dispose 链 |
| `storage` | PLANNED | 命名空间隔离宿主网关（P3） |
| `skills` | STATIC_ONLY | 静态技能文件转换（P3 DshSkillAdapter）；动态 Provider 不支持 |
| `llm` | PLANNED | 评估接入宿主统一模型路由（P3+） |
| `agents` / `sessions` | UNSUPPORTED | 后续实现；现触达 = 结构化不可用 |
| `slots` | UNSUPPORTED | DSH Client UI 专面，**永不支持**（不移植 Web Client，红线 1） |

## 4. 已知边界（诚实清单）

- **冷启动模式的插件状态不跨调用保留** —— 有状态插件需要 P2 持久 Worker；工具描述已如实注明。
- **UI 类 DSH 插件（dsh-web-ui / better-sidebar / genui 等热门）宿主不支持** —— 它们是 DSH Web Client
  的扩展面（ctx.slots / React 渲染器）；市场按 unavailable 如实展示，不套 WebView（红线 2）。
- **PRoot 不是强安全隔离** —— 沙箱防的是误操作与路径逃逸，第三方插件 JS 仍可触达 rootfs 内的
  文件/网络；安装确认门 + 来源钉定是主要防线，不宣称更多（红线 12）。
- **`dsh-` npm 前缀 ≠ 兼容承诺** —— 前缀只是来源约束（防借道装任意包）；真实兼容性以
  能力快照（capabilities.json）的 available 面为准。

## 5. 变更记录

| 日期 | 事件 |
|---|---|
| 2026-11-08 | P0+P1 首批落地：shim/探测/执行/分析四脚本 + JVM 契约锁；本机 node v24.9.0 八场景全绿 |
