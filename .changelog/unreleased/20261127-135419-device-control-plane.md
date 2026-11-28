<!-- pr: device-control-plane -->

- DCP 设备控制面（S01–S10）：一句结构化意图（DeviceOps）卸载/停用应用、管理 /sdcard 文件、改系统设置，全程 dry-run 预演 + 确认令牌 + 审计回放
- 单一事实源 intents.json（31 意图：package×12 / file×9 / system×6 / probe×4），risk / argv / preflight / verify / rollback 全声明式；CI 新增 check_intents.py 结构门禁
- argv 直调 Shizuku（IShizukuService.newProcess，uid=2000）—— 高危意图不再 `sh -c` 拼串，参数白名单拒绝 shell 元字符
- 不可逆操作强制 dry_run → confirm_token（HMAC 绑定 diff 指纹 + 5 分钟窗口）→ 执行前重探比对（TOCTOU 防护）；IS_SELF / DEVICE_ADMIN / IS_LAUNCHER 三守卫硬拒不可 BYPASS
- 文件操作默认软删（回收区 + undo_token）可撤销；写前自动备份；路径白名单（/sdcard、/storage/emulated/0、/data/local/tmp、app 私有目录）
- DeviceCapability 能力自省（档位 / uid / supports / denies + 开通步骤）；Ubuntu guest 内 `apexctl device '<JSON>'` 与 Agent 侧同一执行面；PTY 里敲 pm 得到引导而非裸 not found
- 审计三链（preflight / approved / executed 同 auditId）+ DeviceAuditQuery 按 intent / decision / auditId 回放；旧 jsonl 完全兼容
- feature flag `dcpEnabled` 默认关（设置 → 扩展与服务 → 设备控制面）：关闭时行为与旧版逐字节一致；AppUninstall / AppForceStop 委托后返回文本向后兼容
- 测试矩阵新增 11 个 JVM 测试文件（manifest 不变量 / 风险门矩阵 / 令牌绑定 / argv 注入拒绝 / 路径白名单 / diff 解析 / 审计兼容 / 桥往返 / 通道契约）；真机 NOT VERIFIED 清单见 docs/TESTING.md §9.3
