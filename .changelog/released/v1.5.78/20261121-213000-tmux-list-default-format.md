<!-- pr: tmux-multi-session -->

- fix(tmux): `TmuxSessionManager` 会话列表改用 `tmux ls` 默认输出格式解析（`name: N windows (created X) (attached)`，畸形行跳过、attached 后缀可选、created 非数字诚实置 null），修复假接缝按默认格式回放时制表符解析器恒空导致的 list/switch/send/capture/kill 系列单测红叉
- fix(tmux): createSession 不传 workingDirectory 误报 InvalidInput（sanitizeDir 空值双义混淆）+ listSessions 忽略 ensureTmuxAvailable 错误载荷两处回归修复
