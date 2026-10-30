# 机关（Jiguan）V3.0 — 全能系统级 Agent 终端

> 状态：已落地（五域引擎 + 17 工具 + 第八台内置 MCP 服务器，2026-10）
> 定位：把「云大脑 + 本地手」揉进一台 Android —— 进程与内存 / Java-ART /
> Native / 网络与系统四大操控维度统一成一套**可降级**的端口模型，云端 LLM
> 只掌握「能力域」，本地引擎负责择优通道与结构化降级。
> 姊妹仓：[Ultra-Guru/jiguan-native](https://github.com/Ultra-Guru/jiguan-native)
> （Rust 核心加速层 —— process_vm_readv / AOB 扫描 / arm64 跳板 / ptrace 硬件断点）

---

## 0. 一句话

**全能 ≠ 永远在线**：Android 碎片化（8→15 / MIUI→鸿蒙）意味着任何后端都可能
缺席 —— 机关的答案是把「能力可用性」做成一等公民：每次工具调用拿到的不是
异常而是**结构化降级指引**（装什么、怎么装、装完重试哪条工具）。

## 1. 架构总览

```
                 Agent / Coding 双模式 · PC 端 MCP 客户端（手机作为 MCP Server）
                        │ ToolRegistry 直连注册            │ mcp__jiguan__*（第八台 BUILTIN）
                        ▼                                  ▼
              ┌───────────────────────────────────────────────────┐
              │  core:jiguan-engine（纯 Kotlin，JVM 单测全覆盖）      │
              │  · 五域端口契约（JiguanPorts）                        │
              │  · MemoryScanEngine（精确/模糊/AOB 全类型）           │
              │  · FridaScriptLibrary（7 类脚本模板 + 注入安全）      │
              │  · SandboxValidator（fail-closed 上机前置关）        │
              │  · OmniHookEngine（FRIDA_SERVER>ZYGISK>GADGET>降级） │
              │  · PcapHttpExtractor（pcap→TCP 重组→HTTP 提取）      │
              │  · JiguanKnowledge（包名→Hook特征/内存地标/协议 RAG） │
              │  · JiguanOrchestrator（能力报告/配方/提示段）          │
              │  · 17 AgentTool（Jiguan 前缀族）                      │
              └───────────────┬───────────────────────────────────┘
                              │ 端口注入（core ← platform 单向）
              ┌───────────────▼───────────────────────────────────┐
              │  platform:jiguan-host（Android 适配 + Hilt 装配厂）  │
              │  · ProcFsMemoryAdapter（ps 快照/maps/dd 4096 窗口）  │
              │  · FridaBridgeAdapter（frida-server 生命周期 +       │
              │    PRoot frida-tools CLI 注入 + objdump 反汇编）      │
              │  · PacketCaptureAdapter（root tcpdump → 工作区 pcap） │
              │  · SystemDomainAdapter（SELinux/service/root input） │
              │  · KnowledgeFileStore（原子写 JSON 知识仓）           │
              │  · JiguanHostFactory + JiguanHostModule              │
              └───────────────┬───────────────────────────────────┘
                              │ ExecEngine / PrivilegeManager 注入
              ┌───────────────▼───────────────────────────────────┐
              │  app 装配（JiguanModule：PRoot 优先终端链，           │
              │  与 TerminalExec / REA 同一条执行链）                 │
              └───────────────────────────────────────────────────┘
```

## 2. 四大操控维度 → 通道矩阵

| 维度 | 纯净设备 | +Shizuku | +Root | +Root+PRoot 工具链 | +frida-server |
|---|---|---|---|---|---|
| 进程枚举 | 自身 UID | 全量（ps） | 全量（ps） | 全量 | 全量 |
| 内存读/扫 | ❌ | ❌ | ✅ dd 窗口 | ✅ | ✅ |
| 内存写 | ❌ | ❌ | ✅（确认门+快照） | ✅ | ✅ |
| Java 类/Hook/调用 | ❌ | ❌ | ❌ | ❌ | ✅ |
| 模块枚举 | 同 UID | ✅ | ✅ | ✅ | ✅ |
| 反汇编 | ❌ | ❌ | ✅ dump | ✅ objdump | ✅ |
| 硬件断点 | ❌ | ❌ | ❌ | ❌ | ✅ |
| 抓包 | ❌ | ❌ | ✅ tcpdump | ✅ | — |
| SSL Pinning 绕过 | ❌ | ❌ | ❌ | ❌ | ✅ |
| Root 输入注入 | ❌ | ✅（Shizuku input） | ✅ | ✅ | — |
| SELinux / Binder 巡检 | 部分 | ✅ | ✅ | ✅ | — |

（❌ = 工具返回结构化降级指引，不是异常。）

## 3. 工具清单（17 直连 + 19 MCP 面）

| 域 | 工具 | SPEC V3.0 对应 |
|---|---|---|
| 进程内存 | JiguanProcesses / JiguanMemoryScan / JiguanMemoryHexDump / JiguanMemoryWrite | get_running_processes / memory_scan / memory_hex_dump / memory_write |
| Java/ART | JiguanJavaClasses / JiguanHookJavaMethod / JiguanCallJavaMethod | list_java_classes / hook_java_method / call_java_method |
| Native | JiguanModules / JiguanHookNativeFunc / JiguanDisassemble / JiguanHardwareBreakpoint | list_modules / hook_native_func / disassemble / set_hardware_breakpoint |
| 网络 | JiguanPacketCapture / JiguanSslBypass | start_packet_capture / bypass_ssl_pinning |
| 系统 | JiguanUiTouch / JiguanSystemInspect | ui_inject_touch / （Binder+SELinux 面） |
| 元能力 | JiguanCapabilityReport / JiguanRecipes / JiguanKnowledgeQuery / JiguanKnowledgeRemember | （Tooling-as-Code 与 RAG 的落地） |

注：ui_dump_hierarchy 由既有 UiDump 承担（无障碍树，与机关的 root 物理
注入互补）；terminal_exec 由既有 TerminalExec 家族承担 —— 机关不重复造轮子，
直连注册与 MCP 面共用同一 JiguanRuntime。

## 4. 关键设计决策

### 4.1 端口收敛（core 不见 Android）

引擎的全部宿主依赖收敛为六个小接口（Memory/Java/Native/Network/System/
KnowledgeStore + CommandRunner），`ping()` 探测 + `channelName` 自述 ——
capability 报告、降级指引、通道择优全部可在 JVM 单测里用 fake 端口驱动
（`JiguanOrchestrationTest`）。

### 4.2 Omni-Hook：声明式意图 → 沙盒校验 → 通道执行

Agent 只表达 `HookSpec(kind, class, method, action)`；`FridaScriptLibrary`
生成脚本（`jsString` 转义杜绝注入面）；`SandboxValidator` fail-closed
（括号平衡/体量预算/高危 API 黑名单）；通道解析 FRIDA_SERVER > ZYGISK >
FRIDA_GADGET > UNAVAILABLE（降级文案含安装三步指引）。console.log 是脚本
与 Agent 之间的唯一通信面 —— 无 RPC 依赖，通道最鲁棒。

### 4.3 内存读取的 4096 对齐窗口

`dd bs=4096 skip=N count=M` 是 GNU/busybox/toybox 三家全兼容的唯一形态；
引擎侧对齐（`address & ~4095`）+ 裁剪。`iflag=skip_bytes` 只有新工具链有
—— 不赌。

### 4.4 pcap 解析在引擎内

tcpdump 只负责落盘；Ethernet/IPv4/TCP 分帧、流重组（seq 稳定排序）、HTTP
请求/响应抽取全部纯 Kotlin（`PcapHttpExtractorTest` 用手工构造的字节流全
链路回归）—— 网络域的分析面零外部依赖。

### 4.5 全场景记忆（RAG）

`AppKnowledge(package, category, content, keywords)` + `relevanceScore`
（包名精确 1.0 / 关键词命中率 / 内容包含加成）—— 去广告、解锁 VIP、协议
逆向的结论蒸馏进 `filesDir/jiguan/knowledge.json`，同类 App 直接复用。
`JiguanKnowledgeRemember` 在配方里是**最后一步**（纪律写进提示段）。

### 4.6 Rust 核心加速层（jiguan-native，姊妹仓）

`libjiguan.so`：process_vm_readv/writev + /proc/pid/mem 双通道读取、
memchr 优化 AOB 扫描、arm64 16 字节跳板编码（LDR X16,#8; BR X16）、
ptrace 硬件断点（aarch64 debug regs）—— JNI 边界 catch_unwind 全覆盖。
Kotlin 侧 dd/procfs 通道恒为可靠回落（native 缺席零影响），.so 经其仓库
CI 交叉构建发布 —— 主仓 Gradle 图不引入 Rust 工具链依赖（CI 稳定性优先）。

## 5. 工作流示例（配方 = 代码化的 SPEC §四）

「分析某金融 App 的登录加密逻辑」—— `JiguanRecipes(id=analyze-login-encryption)`：
抓包 → 定位密文字段 → 模块/类枚举 → Hook 出入参 → Python 复现 → 知识蒸馏。
配方不是文档装饰：`systemPromptFragment` 把「先 CapabilityReport、按配方
串联、结论必蒸馏、写前必读上下文」四条纪律注入每轮会话。

## 6. 安全与诚实性纪律

- **写前快照**：JiguanMemoryWrite 写入前回读旧字节并在结果里返回（回滚面）；
- **确认门**：memory_write / hook 族 / ssl_bypass 全部 ToolRisk.HIGH ——
  v2 执行门每会话首用确认；
- **非幂等不重试**：JIGUAN_TOOL_RUN_POLICIES 全族 maxRetries=0；
- **防御式 IO**：/proc 文本任何形状吞得下（坏行跳过），引擎永不因端口
  缺失抛异常 —— 降级是数据不是事故。

## 7. 验证

- `:core:jiguan-engine:test` —— 60+ 断言：解析器（maps/status/cmdline/ps）、
  扫描（精确/通配/模糊/编码/scanChunk）、脚本模板（注入安全/四层 SSL）、
  沙盒（fail-closed 全路径）、pcap（合成字节流分帧/重组/提取/畸形）、
  知识（相似度/幂等/蒸馏）、Omni-Hook（通道解析/降级/事件流）、编排器
  （能力报告/配方/提示段）、工具冒烟（fake 端口全链路）；
- CI 镜像编译新增 `core:jiguan-engine` 裸编译步骤（与 tool-registry 同款
  kotlinc 2.3.21 管线）。

## 8. 后续路线（不承诺时间）

- jiguan-native .so 接入主仓（gradle 属性开关 + 预编译产物消费）；
- Zygisk/LSPosed 通道适配器（frida-server 之外的第二通道）；
- Binder 事务级拦截（需 native 侧 svc hook —— 依赖 jiguan-native 成熟度）；
- VPN 面抓包通道（VpnService + 用户同意流，对齐 PCAPdroid 语义）。
