# Functional Plugin Samples（功能性插件参考样本）

Plugin System v2 的功能性插件（`kind: functional`）分发形态就是**一个
plugin.json 文本**——本目录提供两个可直接导入的参考样本（不参与 Gradle
构建，纯文档资产）：

| 样本 | 形态 | 演示点 |
| --- | --- | --- |
| [daily-briefing](daily-briefing/plugin.json) | 工具宏 + 斜杠指令 | `{{input.field}}` / `output` 捕获 / 步间插值的完整宏语言；`minHostVersion: 1`（老宿主兼容） |
| [repo-pulse](repo-pulse/plugin.json) | 斜杠指令 only | 零工具宏贡献的最小插件形态；`scope: "coding"` 工位作用域；`minHostVersion: 2`（版本门演示） |

## 导入方式

市场 → 已安装管理 → 插件 → 「导入插件 JSON」，粘贴样本全文。或经
`UnifiedPluginRegistry.installFunctionalPlugin(json)` 程序化安装。

## 编写你的插件

完整规范（贡献点模型 / 宏语言 / 信任边界 / 分发路线）见仓库文档
[docs/plugin-system-v2.md](../../docs/plugin-system-v2.md)。速查：

- `id`：小写点分形式（如 `functional.your-name`），不得使用
  `com.apex.agent.` 前缀（宿主命名空间保留）；
- `minHostVersion`：对照宿主 `PluginContract.HOST_API_VERSION`（当前 2）；
- 工具宏：最多 16 步，每步 `tool` 引用**宿主已注册的工具 id**，`args`
  字符串值支持 `{{input}}` / `{{input.field}}` / `{{步骤输出变量}}` 插值；
- **功能性插件是纯数据**：manifest 里不存在任何代码或可执行引用——所有
  执行都落在宿主既有工具上，这是它与 APK 插件（进程外代码）的本质分界。
