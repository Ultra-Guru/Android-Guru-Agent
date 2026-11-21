<!-- pr: extension-hardening -->

- 生产能力级别增强 PR-E（生产化加固）：**扩展声明式清单启动自动同步** —— 设置项 `extensionAutoSync`（AgentSettingSet 可改）开启且 `<filesDir>/extensions/extensions.json` 存在时，进程启动评估全部 spec：与锁文件比对版本漂移、失败收集 —— **只评估不安装**（防 Agent 后续会话悄悄换版本），漂移/失败经系统通知提醒（权限未授权静默，lastSync 可查）
- **最近同步报告持久化**（lastSync.json 原子写）：ExtensionSync 安装/评估后落最近一次结果（ok/漂移/失败清单）；ExtensionDoctor 输出追加 last sync 摘要 —— 生产状态随时可查
- ExtensionAutoSyncRunner 纯 JVM 评估核（core 层零 Android 依赖）：resolve → 锁漂移比对 → 三分类报告（ok/fresh/drift/failed）；app 层 ExtensionAutoSync 薄包装（设置门 + 清单读取 + 通知），@Provides 副作用启动同 SkillModule 惯例（失败只记日志不阻断启动）
- 向导安装失败后按钮转「重试」（hash 仍有效，一键重发）；JVM 测试（漂移/失败报告 + lastSync 落盘重载 + 空清单零报告）
