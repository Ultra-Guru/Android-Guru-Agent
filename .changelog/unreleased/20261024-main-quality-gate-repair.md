<!-- pr: fix-main-quality-gate-repair -->
- 修复 main 分支 Quality Gate 红灯：#427 合并后 CodeViewModel 达 1205 行突破 1200 门禁（PR 侧门禁漏拦），现拆出会话快照三动作面（restore/build/persist，#152）至同包扩展 CodeSessionSnapshotOps.kt，VM 回落至 1130 行、余量 70
- 堵住 empty-catch 门禁正则漏洞：GATE 4 字符类补下划线——`catch (_: Throwable) {}` 弃名参数此前绕过 `[a-zA-Z. :]+` 匹配；弃名参数不豁免（要忽略须写明为何可安全吞）
- 三处真实空 catch 补防御式 IO 留痕（#260 纪律）：MoreCapabilitiesHost 通知渠道创建 / ApkInstaller 会话放弃 / ComposeFoundry UiRenderer 色值解析，均改为异常折叠 + warn 日志
