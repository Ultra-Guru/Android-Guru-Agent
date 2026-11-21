# 修复：Agent 执行命令高错误率 + 流式输出只有思考没有回答

- **工具名幻觉兜底**：模型调用不存在的 `bash` / `sh` / `run_command` 等工具名
  （训练自 Claude Code / OpenAI 工具集的肌肉记忆）时，别名表静默路由到
  TerminalExec —— 不再连环 `Unknown tool` 404 后盲目换名重试；未知工具的
  错误文案同时补上「命令用 TerminalExec、找工具用 ToolSearch」的可执行
  指引，模型下一步有确定动作。
- **一次性命令改用真 bash**：TerminalExec 的 Ubuntu 沙箱通道从 `/bin/sh`
  （dash）切换为 `/bin/bash` —— pipefail / 数组 / 进程替换 / here-string
  等 bashism 不再 `not found`，与 PTY 交互会话同方言。
- **失败原因不再丢失**：宿主命令通道（root / Shizuku / 本地）在 stdout
  非空时不再静默丢弃 stderr —— 双流并存时以 `[stderr]` 标记追加，模型
  能看到真实报错，不再盲重试。
- **cd 记忆加固**：路径含空格 / 引号时安全包裹；工作目录记忆若指向
  Ubuntu 沙箱内路径（如 `/workspace`，宿主不存在）自动丢弃 —— 不再让
  `cd /workspace && cmd` 的语法错误污染命令结果。
- **提示词声明 Shell 方言地图**：明确告知模型两套 shell 的边界 —— 宿主
  mksh+toybox 仅 POSIX 子集、python3/gcc/git/npm 只存在于 Ubuntu 沙箱、
  不存在名为 bash 的工具 —— 从源头减少方言错配。
- **流式输出修复**：思考型模型把最终回答整体写进思考通道（或网关把正文
  挂在流式末帧 message 上）时，正文恒空被误判为「无效响应」—— 现在以
  思考文本兜底为最终回答（先补发 ResponseChunk 再收尾），并新增末帧
  message 正文/思考内容兜底解析；「只有思考过程、没有文字回答」不再出现。
