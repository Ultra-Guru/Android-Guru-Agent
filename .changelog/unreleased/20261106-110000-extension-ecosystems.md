<!-- pr: extension-ecosystems -->

- 市场完全重构 PR-B：三生态适配器接入统一扩展闸门 —— **OpenCode**（npm/本地 .ts/.js/github → PRoot Ubuntu 沙箱扩展目录，注册元数据）、**Claude**（name@marketplace 真读 marketplace.json 定位实体仓库 → zip 下载防穿越解压 → 复用 ClaudePluginImporter 全套映射 → FusionBundle + 资源技能，纯 android 域零 rootfs 依赖）、**DSH**（DeepSeek Harness：dsh-* npm 前缀强制 / github 源 → 沙箱安装 + dsh plugin add 语义模拟 profile.json）
- Node 系安装器基座（NodeInstallPort 端口 + NodeFamilyInstaller）：ExecEngine + ProotCommandSpawner 同款接线（与 REA 命令链同 rootfs/home/能力门/PAT 语义）；**白名单命令构造**（npm 包名/仓库形态校验，用户输入永不进 shell 串）；tmp → 同父 rename 原子发布，失败零残留
- **锁文件**（lock.json）：ExtensionSync 安装成功即钉版；后续 sync 源指纹 ≠ 锁 → 版本漂移默认跳过并如实报告（防 Agent 后续会话悄悄换版本），update=true 才重钉；ExtensionUninstall 联动清锁
- ExtensionDoctor 增强：ubuntu 域 node/npm 就绪探针 + sync 锁计数；ExtensionInstall/Uninstall/Sync 输出同步域信息
- 旧入口内部转接：SkillInstall 非侵入改走统一闸门（账本/锁同源，id/schema/输出格式对模型零变化）；McpConnect 保持不动 —— 连接语义（#344 重连闭环）与安装职责不重叠，强行转接破坏重连语义
