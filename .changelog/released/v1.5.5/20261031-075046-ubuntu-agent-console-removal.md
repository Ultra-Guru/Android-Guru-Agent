<!-- pr: ubuntu-agent-console-removal -->
- Ubuntu 大重构：终端控制台 UI 整体退役 —— Ubuntu 由 Agent 全权掌控（TerminalUbuntuEnsure / TerminalExec / TerminalLinuxPackages 等工具直连），用户不再手动输入命令
- 「终端」页改为「Ubuntu 环境」页：只保留 rootfs 生命周期卡（安装/修复/删除/进度/能力清单）+ 开发环境安装卡（版本自选）
- 开发环境版本自选：JDK 17/21/11/8 · Gradle 8.12/8.11/8.10 · Build-Tools 35/34/36/33 · NDK 27/26/25 · Node 22/20/18 · Go 1.24/1.23/1.22 · Rust stable/nightly
- 依赖目录修复：Gradle 改官方 zip 发行版（apt 源是 4.x 古董）；cmdline-tools 改直链 zip 落地（修复 sdkmanager 无本体可用的死锁）；移除 TUNA 伪代理镜像命令（100% 失败的 --proxy_host 用法）
- 真镜像支持：apt→TUNA（arm64 自动走 ubuntu-ports）、Gradle/AndroidSDK→腾讯、Node→npmmirror、Go→USTC、Rust→rsproxy.cn
- 已装状态徽章：一条复合命令批量探测全部条目（D <id> OK/MISSING 锚定解析），顶栏可手动刷新
- 修复 bootstrap 阻断 bug：BasePackageProfile 把虚拟包 awk 写进 essential（apt 报 "Package 'awk' has no installation candidate" exit 100 → BASE_PACKAGES 失败 → 降级就绪）——改为真实包 mawk（截图实测根因）
- 安装命令全面改纯 && 链并禁用 set -e：交互式 PTY 里 set -e 会把单步失败升级为整个 bash 会话退出（杀死后续所有安装）；失败现在如实返回非零退出码
- 清理 ~1700 行控制台代码（TerminalScreen/ViewModel/Syntax/CommandHistory/History/SettingsSheet/RealCommandExecutor/RawStreamSanitizer/SessionRegistry/EnvironmentCenterSheet 及配套测试）；设置中心 terminal/appearance 叶随控制台退役（字号/亮屏无消费者），环境页安装期间自主亮屏
