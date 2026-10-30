<!-- pr: jiguan-omni-agent -->
# 机关（Jiguan）V3.0 —— 全能系统级 Agent 终端

- 新增机关五域操控引擎：进程与内存 / Java-ART / Native / 网络与系统四大维度统一为可降级端口模型
- 新增 17 个 Jiguan 工具（Agent 与 Coding 双模式直连）：进程枚举 · 全类型内存扫描（精确/模糊/AOB）· Hex 视图与多类型解释 · 原子内存写入（写前快照）· Java 类枚举 · Java 方法 Hook（LOG/REPLACE）· Java 方法主动调用 · 模块枚举 · Native 函数 Hook · ARM64 反汇编 · 硬件断点 · 抓包与 HTTP 提取 · SSL Pinning 四层绕过 · Root 物理输入注入 · SELinux/Binder 系统巡检
- 新增能力面：JiguanCapabilityReport 五域可用性报告 + 结构化降级指引（通道缺席时给出可执行安装步骤）
- 新增配方系统：登录加密分析 / 去开屏广告 / 数值修改三大高频场景的步骤化工作流（JiguanRecipes）
- 新增全场景记忆 RAG：分析结论按包名+关键词蒸馏复用（JiguanKnowledgeQuery / Remember）
- 新增第八台内置 MCP 服务器（jiguan）：手机作为 MCP Server，PC 端 AI 经 mcp__jiguan__* 驱动同一套五域工具
- Omni-Hook 引擎：声明式 HookSpec → 脚本模板生成 → 沙盒 fail-closed 校验 → FRIDA_SERVER/ZYGISK/GADGET 通道择优
- 内存通道：Root dd 4096 对齐窗口（GNU/busybox/toybox 三家全兼容），姊妹仓 jiguan-native（Rust 核心加速层）独立发布
