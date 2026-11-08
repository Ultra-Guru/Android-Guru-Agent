<!-- pr: extension-runtime -->

- 生产能力级别增强 PR-D（运行时生产化）：**ExtensionRun** 工具上线 —— 运行已安装的 ubuntu 域扩展（OpenCode/DSH 插件）执行桥不再只装不跑：账本寻址 → runtime=ubuntu 校验 → 读安装期 profile 元数据（entryHint/runtime=node）→ `node <entry> [args]` 白名单执行（CATALOG 渐进披露）
- **安全边界（诚实声明）**：entry 经 ExtensionDirLayout 新增 `profileFile`/`pkgFile` canonical 归属校验（防 bind 挂载/符号链接下的 startsWith 失效与 profile 篡改，单测覆盖逃逸拒绝）；argv 直透不经 shell，但 description 如实声明「下游脚本如何处理 argv 属于脚本自身行为」；超时强杀下沉在 ExecEngine 进程层（proot --kill-on-exit），非协程假超时
- OpenCode/DSH 适配器 install 的 profile 元数据补 `runtime: "node"` 并改走规范化归属校验写入
- **NotifySend** 工具：系统通知栏推送（长任务收尾提醒）—— Android 13+ POST_NOTIFICATIONS 运行时权限诚实上报（未授权返回 notifications_disabled 并引导开启，不假装投递成功；复用 ApexNotifications.canPost 既有判定）
- **CapabilityReport 集成统一扩展快照**：能力自省尾部新增扩展行（账本数/锁数/适配器面/已装样例，fail-silent 与 GitHub 行同款）
- 过时文案修正：市场 Plugins 页「agent tool bridge is under construction」→ 已建成语义（插件工具已注册进 Agent 工具面 + 死亡降级宿主直调），两语言
