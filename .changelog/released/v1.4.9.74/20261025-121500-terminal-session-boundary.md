<!-- source: fix/terminal-session-boundary -->
<!-- target: patch -->

## 终端会话边界与信号语义修复

- Ctrl+Z 不再造成会话假死：挂起控制此前错发 SIGSTOP（不可捕获的硬停止，作业被冻结、shell 不回前台），现在发送正确的 SIGTSTP（作业控制语义：前台作业挂起、shell 收回终端回提示符，SIGCONT 可恢复）；Agent 的 TerminalSignal 工具同步支持 SIGTSTP
- 敲 exit 的会话不再永久泄漏：shell 自然退出后此前只迁移状态不回收资源（native PTY / VT 引擎 / 环形缓冲区全部滞留，死标签一直挂在会话簿）；现在 60 秒宽限期（留给读最终屏幕和事件尾窗）后自动回收，会话标签随之消失
- 终端标识查询应答（XTVERSION）：tmux 的终端探测、nvim 的环境识别此前只能超时放弃，现在按 xterm 规范应答 `DCS>|xterm(377)ST`
- Ubuntu 会话色彩能力声明对齐：`COLORTERM=truecolor` 此前只在 Android 宿主会话注入，Ubuntu 会话里 ls / fzf / claude-cli 等按 256 色降级渲染；现在双模式同口径声明真彩色
- 清理零引用的设计稿代码约 350 行（process2 规格骨架、TerminalExecutionBackend 等四个并行抽象），减少「以为有两套实现」的误导
