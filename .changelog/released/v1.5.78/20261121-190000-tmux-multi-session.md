<!-- pr: tmux-multi-session -->

- Terminal 新增 tmux 多会话能力：`platform:terminal` 增 `TmuxSessionManager`（纯 JVM，shell-exec 接缝注入 guest `ExecEngine`/`ProotCommandSpawner`，rootfs 就绪即在 Ubuntu 内驱动 tmux，Android 专有/未就绪回落宿主，tmux 缺失 → 结构化 TmuxError 而非崩溃）、`TmuxSessionStore`（JSON 元数据持久化，Mutex + 原子 temp+rename + `.corrupt` 隔离）、`TmuxException`（类型化 TmuxError code/recoverable）
- 对 Agent 暴露六个 `core/tool-registry` 工具：`tmux_new_session`（幂等创建/复用命名会话）、`tmux_switch_session`（切换活动会话）、`tmux_list_sessions`（`tmux ls` 权威列表）、`tmux_send_keys`、`tmux_capture_pane`、`tmux_kill_session`；经纯 JVM 接缝 `TmuxSessionGateway` 解耦，`core/tool-registry` 对 `platform:terminal` **零 Kotlin import**（守 TERMINAL_SDK_BOUNDARY.md 毕业前提）
- 诚实存活契约：登记 ≠ 存活，`tmux ls` 为唯一权威（对齐 TERMUX_CAPABILITY_MATRIX §10.4 PRoot tmux-server 生命周期）
- 补齐 Operit 对标缺口 docs/terminal/operit-rikkahub-comparison.md §4.7（Operit 有 tmux 多会话而 apex 缺失）