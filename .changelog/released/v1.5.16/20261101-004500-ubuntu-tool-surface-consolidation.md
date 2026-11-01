<!-- pr: ubuntu-tool-surface-consolidation -->
- 终端环境工具收敛：Ubuntu 安装 / 引导 / 能力查询三代旧入口（TerminalUbuntuInstall、TerminalLinuxBootstrap、TerminalLinuxCapabilities）已合并进 TerminalUbuntuEnsure 一键入口 —— Agent 拉起 Ubuntu 不再需要在三个入口间编排决策
- 全部引导提示与错误信息统一改指新入口：rootfs 未就绪、bootstrap 失败、创建会话被拒等场景下，Agent 按提示一键恢复即可
- 超时策略表与工具目录同步收敛（移除已下线工具的执行器预算条目与旧 id 别名），按项目补装工具链走 TerminalWorkspaceEnvironment ensure
