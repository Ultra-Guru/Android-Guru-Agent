<!-- pr: extension-model-gate -->

- 市场完全重构 PR-A：统一扩展模型闸门落地（schema `apex-extension-v1`）—— OpenCode/Claude/DSH/Skill/MCP/APK 各生态安装语义收口到一条 **resolve（认路）→ inspect（看清）→ install（装）** 链，用户/模型只学一种 spec 语法（`mcp:{…}` / `mcp:import:<文件>` / `skill:<url>` / `claude:<name>@<mp>` / `npm:<pkg>` / `github:` / `apk:`）
- **先看后装硬闸**：ExtensionInstall 必须回传 ExtensionResolve/Inspect 产出的 `manifest_hash`（sha-256 指纹）——跳过 inspect 直接装被 SecurityException 拒绝；显式传 manifest 原文时重算指纹比对（防中途换包）；指纹即凭证，模型无需回传大体积 manifest（resolve 缓存按指纹取回）
- **确认门**：声明权限或含 postinstall 脚本的扩展需 `confirm="confirm"` 显式令牌（模型代表用户确认过 inspect 报告）；BYPASS 权限模式不豁免；MCP register/import 语义分离（新增 vs 导入桌面 Claude/Cursor 配置，防重复覆盖）
- 七个新工具：ExtensionResolve / ExtensionInspect / ExtensionInstall（三步入 CORE 集）+ ExtensionList / ExtensionUninstall / ExtensionSync（声明式批量，默认 dry-run）/ ExtensionDoctor（账本一致性体检）；内置技能 extension-install / extension-troubleshoot（assets/skills 通道随包释放）
- 聚而不同：扩展实体落原生注册表（SkillRegistry 复用全管道+热激活闭环 / McpManager 经端口接入），新账本只记来源与指纹；runtime 双域声明（android/ubuntu，MCP runInSandbox→PRoot）为 PR-B 的 Node 系安装器铺路；旧入口 SkillInstall/McpConnect 双轨保留零破坏
