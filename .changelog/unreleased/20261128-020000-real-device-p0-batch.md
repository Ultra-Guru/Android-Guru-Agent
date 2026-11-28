<!-- pr: fix/real-device-p0-batch -->

- 工具预算（P0）：CORE 工具改为无条件进入请求，不再被 64 条/48KB 扩展预算静默丢弃 —— 此前 `Write / WebSearch / WebFetch / ToolSearch / ToolOpen / TerminalRun/Observe/Wait` 等 11 个核心工具每轮被丢，模型「不会写文件、不会联网、找不到工具」；预算截断现在落日志（30s 节流）
- 浏览器工具（P0）：41 个 `browser_*` 工具重新对模型可见 —— 计划层角色过滤此前读的是从未被 DI 填充的 `grantedRoles` 快照（恒空集），关限制 / 激活专家模板都无效；现每轮现取 BrowserRoleAuth 热值，子代理保持不继承
- 无障碍设备控制（P0）：开启无障碍后 UiSwipe / 方向滚动 / 长按不再 100% 失败（补 Swipe/PressKey 分支，手势失败自动回落 shell）；UiDump 改为递归真实嵌套树并剪掉装饰节点（此前只看得到前 20 个容器节点）；InputText 接入 ACTION_SET_TEXT 支持中文输入；手势/全局动作的系统拒绝不再假报成功，并在手势后静置 300ms 等页面稳定
- 命令确认门（P0）：高危模式改词边界匹配 —— `git add .`、`git commit -m "Refactor platform module"`、`git log --format=%H`、`npm run format` 不再弹「危险命令」（旧子串把它们分别判成 dd / rm / format）；`rm -rf`、`pm uninstall`、`git push --force` 等仍拦截，`fusermount` 补入
- 询问桥（P0）：并发提问串行化 —— 并行工具块的两个确认弹窗此前互相覆盖、双双挂满 5 分钟后按超时拒绝
- 图片输入（P0）：发图不再立即报「模型不具备该能力」—— 内置 Provider 只声明 vision 从未声明 imageInput，能力交集恒 false；手输模型 id 现与 /models 选择同款预填能力位；错误文案补充设置指引
- 失败分类（P1）：确定性错误（文件不存在 / 参数非法 / 未知工具 / JSON 畸形 / 命令不存在）不再因文案含 network/timeout/busy 被当瞬时失败盲重试 7 次
- 工具输出截断（P1）：列表型输出按字符预算整行填充首尾（此前固定 15+10 行，CodeRead / UiDump / ToolList / Grep 全被砍成 25 行）
- 终端（P1）：`make / node / pip` 词边界匹配，不再因「make sure / node_modules / pipeline」被注入「先装 Ubuntu」；rootfs 解压移除逐文件 fsync（5–8 万文件 × 5–50ms 把首装放大到十几分钟）；`proot --version` 探针记忆化（probeAll 冷缓存曾起 45 个子进程）；TerminalExec 选路改走 30s 档位缓存，不再每条命令 fork `su -c id`；权限刷新时失效 root 缓存
- 流式正文（P1）：Markdown 解析改「常驻 worker + 合流通道」—— 旧 produceState 每个 token 到达都取消重解析并清空，回答阶段正文整段不出、只剩光标闪
