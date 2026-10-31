<!-- pr: fix/standard-kit-v5-tool-surface -->

- 修复「逻辑·标准」线在 v5 工具注册表（PascalCase id）下 shell / 终端 / 编码工具族整体缺失的问题：工具面优先序前缀失配导致 48 个工具的截断退化为字典序，会话只剩 App 控制 / 浏览器自动化 / 模型配置 + task（表现为 agent 无法执行 Ubuntu 命令，general 子代理同样无 shell 工具）—— 经 apex-kit-standard 工具 id 形态归一修复后锁定 bump @ aae8693
- 一并恢复被同一根因静默破坏的行为：规划师 / 探索 / 评审只读画像不再空面、DEFAULT 模式不再对 CodeRead 误弹权限询问、子代理读操作不再被 ASK 折叠拒绝、ACCEPT_EDITS 重新放行编辑类、bash 别名不再误判未知工具、read-before-edit 守卫与 Todo / 纪律提示词指引回归
