<!-- pr: extension-market-wizard -->

- 市场完全重构 PR-C 收官：**统一安装向导**上线聚合中心（「统一安装」入口）——粘贴任意 spec（npm / claude: / dsh: / mcp: / skill: / github: / apk:）→ resolve → inspect 预览（目标注册表/运行时域/权限/生命周期脚本）→ 确认安装，与 agent 侧 ExtensionResolve/Inspect/Install 三工具**同一闸门实例**（hash 门/确认门如实传递）；五页签旧安装流保留双轨
- **APK 扩展包适配器**（type=apk_package）：与 npm 系安装完全隔离 —— 安装语义 = 发起系统 PackageInstaller 会话（用户在系统 UI 确认为第一道确认，闸门 confirm 为第二道）；装完插件宿主经 PLUGIN intent-service 自动发现 + 签名门校验；卸载诚实拒绝（系统级卸载不归闸门）
- **extension-authoring 内置技能**（assets/skills 通道）：教 Agent 把脚本/配置/App 按目标生态包装成可安装产物（技能/带资源 Claude 包/MCP/OpenCode/功能插件/APK 六形态选择 + 通用纪律 + 交付口径）
- 配套：ApkInstallPort 端口（app 层接 AndroidApkInstaller 会话安装器）、MarketViewModel 注入向导状态域（ExtensionWizardController，独立 StateFlow 同 hub/mcpSo 惯例）、i18n 两语言、APK 适配器 JVM 测试（路径校验/双重确认/卸载诚实拒绝）
