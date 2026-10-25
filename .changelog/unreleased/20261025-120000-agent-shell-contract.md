<!-- source: fix/agent-shell-contract -->
<!-- target: minor -->

## Agent 终端工具修复：失败不再显绿 + 执行环境全程可见

- 终端命令失败不再误报成功：TerminalExec 返回的结构化结果（exit_code）现在是成败判定的权威信号 —— `command not found`（127）、命令被你拒绝（126）、超时强杀（-1）此前都显示绿色「完成」，现在如实显示红色「失败」
- 工具卡新增真实退出码徽标：终端工具完成后展开卡片可见 `exit 0` / `exit 127` 标记，非零退出码红色高亮，失败原因一眼可辨
- 工具卡新增执行环境标注：每次终端调用显示执行通道（Ubuntu 沙箱 / Android 宿主 Root/Shizuku/本地）、实际执行目录、关联会话号 —— 双模式下「这条命令跑在哪个根」不再靠猜
- 命令挂起可见：等待用户输入的终端命令（INPUT_REQUIRED，如 apt 交互确认）在工具卡上显示琥珀色「等待输入」标记
- Terminal* 工具族获得独立「终端」徽章（终端图标 + 终端绿）：此前全部显示「本地工具」徽章，无法与普通工具区分；折叠卡的智能摘要也会显示 TerminalExec / TerminalRun 正在执行的命令
- TerminalExec 的 cwd 参数统一为 guest 语义：`/workspace`（活跃工作区）、`/sdcard`（共享存储）、`/root`、`~/` 按描述正确映射进 Ubuntu 沙箱 —— 此前按宿主语义解析导致目录不存在报错，与 TerminalCreate 同名参数语义相反
- 危险命令审批弹窗显示执行环境：「将在：Ubuntu 沙箱（proot-ubuntu）· 目录 /workspace」—— 同一条 rm 在沙箱内与宿主上后果完全不同，授权决策现在能看到这个区别
- cd 目录记忆支持 guest 路径：Ubuntu 通道里 `cd /workspace/proj && make` 的目录切换会被记住，后续命令同目录执行（此前宿主侧验证失败被静默丢弃）
- TerminalObserve SCREEN 模式返回 guestCwd：交互会话里 shell 上报的当前目录（OSC 7）首次暴露给 Agent —— cd 到哪里、后续 TerminalExec 传什么 cwd 不再靠猜（JVM 回退引擎已生效；设备端 native VT 引擎的 OSC 7 桥接为后续工作，就绪前该字段不出现）
- 高危命令检测补漏：`> /etc/passwd` 这类重定向改写系统文件的变体纳入确认清单
