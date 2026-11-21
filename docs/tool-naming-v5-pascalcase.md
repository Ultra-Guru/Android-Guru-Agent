# 工具 / 技能 / MCP 命名 v5 —— 无分隔符 PascalCase

> 用户规格（2026-10）：**agent 工具、skills、MCP 的命名里不允许出现 `-` 与 `_`**；
> 其中文件读写两件工具必须区分并用 `Write` 与 `Edit` 两个名字 —— `Write` 写新文件，
> 后续修改一律走 `Edit`。三个仓库同源落地：本仓库 + apex-skill-hub + apex-mcp-hub。

## 1. 规则

| 位置 | 旧 | 新 |
| --- | --- | --- |
| 内置工具 id | `read_file` / `write_file` / `edit_file` | `Read` / `Write` / `Edit` |
| 内置工具 id（多词） | `web_search` / `browser_get_cookies` | `WebSearch` / `BrowserGetCookies` |
| 终端工具 id | `terminal.exec` / `terminal.linux.bootstrap` | `TerminalExec` / `TerminalLinuxBootstrap` |
| 一等 MCP 工具 id | `mcp__{server}__{tool}` | `Mcp{Server}{Tool}`（如 `McpGithubCreateIssue`） |
| 合成子代理工具 | `task` | `Task` |
| 技能 id | `ab-test-verdict` | `AbTestVerdict` |
| MCP 服务器名 / 分类键 | `server-github` / `web-search` | `ServerGithub` / `WebSearch` |
| 旧 API 遗留别名 | `terminal_exec` / `get_time` | `TerminalExecLegacy` / `GetTimeLegacy` |

- 单词也首字母大写（`time` → `Time`、`fetch` → `Fetch`）：用户选定 PascalCase 后
  单复数风格统一，避免 `Read` 与 `time` 混排。
- 参数名（`path` / `content` / `run_in_sandbox` …）**不在改名范围**：它们是工具
  契约的一部分，且 MCP 远端 schema 由服务器定义（app 侧只做清洗，不改写）。
- provider 侧函数名文法 `^[a-zA-Z0-9_-]{1,64}$` 依然满足，且现在连 `_` 都不需要。

## 2. 兼容层（旧名字永远能跑）

`core/tool-registry/.../catalog/ToolIdAliases.kt` 收录 **193 条**「旧 id → 现役 id」，
是唯一的归一入口：

- `DefaultToolRegistry.getTool/unregister` 先过 `ToolIdAliases.canonical`，再退
  `equals(ignoreCase = true)` —— 老会话历史、老技能正文、用户收藏、插件宏里
  引用旧工具名照样命中现役工具；
- `ToolNameSanitizer.resolveRegistryId` 在 provider 名反查失败时同样回落别名表
  （模型回显 `read_file` 也能执行）；
- `StandardToolSurface.ALIAS_TO_REGISTRY` 保留业界惯用短名（`read` / `bash` /
  `grep` / `task` …）并新增 `read_file` 这类旧写法 → 归一后再比较；
- `ToolSuggester` 与 `StandardToolSurface.suggestToolIds` 的归一改为"小写 + 去掉
  一切非字母数字"，于是 `web_search` 的拼错也能建议出 `WebSearch`。

旧名字**不会**进入请求体：注册表里没有它们，只有查找时归一。

## 3. Write / Edit 职责分离（用户规格核心）

| 工具 | 语义 | 行为 |
| --- | --- | --- |
| `Write` | **只写新文件** | 目标已存在 → 直接失败并指向 `Edit`；确需整体重写才允许显式 `overwrite=true` |
| `Edit` | **既有文件的唯一修改入口** | search-replace 块（精确匹配 / 插入 / 删除），文件不存在时提示改用 `Write` |

旧 `write_file` 的 `mode: append` 参数已移除（追加属于"修改"，走 `Edit` 的插入模式）。
Code 模式的 `CodeWrite` / `CodeEdit` 保持既有语义（`CodeWrite` 本来就要求
`overwrite=true` 才覆盖）。

## 4. 技能 / MCP 命名归一

- 新增 `SkillNaming`：安装、ZIP 导入、磁盘加载、按 id 查询四处统一
  `canonicalId()`（`my-skill` → `MySkill`）。老设备上已装的 kebab 技能目录读取时
  归一到同一把键，**不会**出现新旧两份并存；`.disabled` sidecar 同步归一。
- 依赖校验两侧都归一：历史 manifest 里的 kebab 依赖名照样能匹配到已安装技能。
- MCP：`McpToolNaming.toolId` 生成 `Mcp{Server}{Tool}`；市场分类键兼容新
  PascalCase 与旧 kebab 两套；`McpServerCatalog.ID_REGEX` 放开大写、禁下划线；
  重名去重后缀不再拼 `-`。
- 官方仓库同步：apex-skill-hub 132 技能、apex-mcp-hub 101 服务器 + 19 分类全部
  PascalCase（两仓各自带 validate 脚本，CI 全绿）。

## 5. 影响面与验证

- 484 个文件、约 4.4k 处字符串替换；482 处旧 id 文字（文档/注释/prompt 文案）一并更新；
- 单测跟随新契约更新：`ToolSystemV4CatalogTest`（sanitize 不再折成 `_`）、
  `StandardToolSurface*`（`Task`）、`ToolV3InfraTest`（`Clipboard`）、
  mcp-host 三件套（`Calculate`）等；
- 验证走 CI（用户指定：本地不编译，GitHub Actions 为准）：静态分析 + app 编译 +
  9 步测试 + debug APK + 结构质量四门禁。
