# T99：chroot 挂载收敛与存量 CA 修复

## 范围与设计取舍

本 PR 在 T98 的双后端结构上收敛两个真实缺口；不改变默认 PRoot，也不在只读后端查询时执行 guest/su。T99 原 spec 是参考，不是实现契约：完整 rootfs 构建清单已经包含 `ca-certificates`，因此不重打包、不把新环境证书缺失当作已证实问题。

### 挂载

- `ChrootMountScript` 基于规范化 rootfs 路径和 `/proc/self/mountinfo` 的挂载 ID 判断，仅清理本后端管理且 ID 未变化的目标；挂载守卫不可用就拒绝挂载，不在精简 ROM 上直挂造成堆叠。
- `flock` 使用一份操作锁和一份由 guest 继承的共享使用锁。最后会话另开描述符尝试独占清理；宿主 su 异常退出而 guest 存活时，不会卸载其正在使用的挂载。锁/标记位于 rootfs 外、guest 不可写；拒绝 bind 目标符号链接；挂载成功但标记失败时只对 ID 未变且没有外来覆盖的目标回滚。

### 证书

- 仅当 guest `/etc/ssl/certs/ca-certificates.crt` 缺失或为空时修复；已有打包或用户证书保持原样。
- 可信顺序：有效宿主 CA bundle（仅显式注入的非 Android 场景）→ Android 平台 `AndroidCAStore` 的已启用 `system:` 别名（只接受 X.509 CA，最多 512 张/8 MiB）；`user:` 别名绝不导入。Android 生产 DI 明确关闭普通 host bundle，避免绕过平台停用过滤。AndroidCAStore 的别名/取证书 API 反映平台的禁用系统锚点，不需让普通 app 读取私有 `cacerts-removed` 目录。原始系统目录仅作为显式注入且已知撤销目录时的保守回退，生产 DI 不使用它。
- `TerminalUbuntuEnsure` 以及 PRoot/chroot 的显式 `TerminalCreate` 均尝试修复；来源缺失则保持原状，异常记录日志，不阻断原本可用的终端，也不降级 HTTPS 到 HTTP。修复后的文件对非 Root guest 可读。

## 自动验收与待完成事项

- Actions 的单测/编译/结构门禁验证证书别名筛选、保持已有 bundle、修复失败的降级语义、PRoot/chroot 显式创建路径、mountinfo 守卫/并发/孤儿锁和回滚；真实 POSIX 挂载夹具不等于 Android Root 设备。
- **设备必需、尚未验证**：Magisk/KernelSU/APatch 授权/拒绝、OEM mount namespace 与 `flock`、两个后端的 `curl -sI https://deb.debian.org`、用户停用系统 CA 后的信任行为、ptrace 受限机型。特别是已修复的存量 bundle 不在后续系统证书变更时自动重建；本 PR 只修“缺失/为空”，不承诺持续同步信任锚。
- 下一步验收计划：在有 Root 的测试设备上依次检查授权弹窗、双会话并发/异常退出后的 `/proc/self/mountinfo`、两后端 HTTPS；记录设备 Android 版本与 Root 管理器版本、命令退出码和挂载表差异。无法取得设备时仅能交付 CI 可验证部分，不把 emulator 的 Media instrumentation 当成 Root 验收。
