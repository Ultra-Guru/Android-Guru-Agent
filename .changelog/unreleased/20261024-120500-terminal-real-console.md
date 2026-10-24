<!-- source: feat/terminal-real-console -->
<!-- target: minor -->

## 终端大重构：真实执行 + 入口统一 + 功能全量转移

- 终端命令改为**真实执行**：M3 控制台提交的命令在真实 PTY 会话（Ubuntu PRoot / Android toybox）中运行，输出逐行实时上屏，退出码来自 OSC 633 marker 协议（不再是 500ms 模拟）
- 删除旧交互终端入口与 feature:terminal-m3 模块：抽屉只剩一个「终端」，「M³」字样全部移除
- 设置中心「终端」分类随控制台收窄：配色方案与扩展键宏两叶随旧交互终端退役，单色 / 键栏 / 响铃振动等网格时代项一并移除
- 功能全量转移：多会话标签（多对话）、Ubuntu 环境中心（安装/进度/修复/删除/镜像/apt 依赖）、命令历史（记录/召回/Sheet）、黑白名单（与 Agent 运行时策略共键）、字号 8–24 与亮屏设置、崩溃恢复死标签 + 重启
- 新增真实执行管线纯件与测试：RawStreamSanitizer（回显/marker/提示符剥离）、RealCommandExecutor（流式 + 真实退出码）

### 构建修复轮（CI 实测驱动）

- 修复 strings_terminal 键重复（设置名单占位撞名 term_cmd_hint → term_policy_hint）
- 修复提示符尾正则：真实 PS1 形如 `user@host:cwd $ `（$ 前有空格），补 `\s*` 容忍
- 修复输出高亮字母循环括号缺失（行尾 token 越界）
- 修复历史载入侧不滤含换行条目；检索断言改为最新在前
