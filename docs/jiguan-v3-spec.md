# 机关（Jiguan）V3.0 — 全能系统级 Agent 终端规格

> 本文是 SPEC V3.0 原始规格的存档（由产品方钦定），实现映射见
> [jiguan-omni-agent.md](jiguan-omni-agent.md)。

---

## 一、重新定义"全能"：四大底层操控维度

为了让 Agent 无所不能，Rust 核心引擎必须覆盖 Android 系统的四大维度：

1. **Native 维度 (C/C++)**：全内存读写、汇编级 Hook (Inline/PLT)、硬件断点、Rust 注入 .so。
2. **Java/ART 维度 (Kotlin/Java)**：通过 JVMTI 或 Frida Bridge 实现 Java 反射调用、动态修改 ART 方法、Hook 系统 API（如拦截 getDeviceId() 伪造设备指纹）。
3. **系统/框架维度 (Binder/System)**：Binder IPC 拦截与伪造、SELinux 策略绕过、动态提权。
4. **网络与 UI 维度**：本地 VPN 抓包与 MITM (Man-in-the-Middle) 解密、无障碍级别的 UI 自动化控制。

## 二、核心架构升级 (System Architecture V3.0)

### 1. 统一的 Agent 中台 (Unified Orchestrator)

- **Tooling-as-Code**：云端 LLM 不需要记住所有基础 API，它只需要掌握"能力域"。
- **本地沙盒执行器 (Local Sandbox Executor)**：Agent 生成的代码（如一段 Python、Lua 或 Frida JS 脚本）首先在本地沙盒中试运行，校验无误后再挂载到目标 App 上。

### 2. 跨语言的 Hook 引擎 (Omni-Hook Engine)

- 放弃单一语言依赖，Rust 核心只负责底层桥接。
- 对外提供统一的 Hook 接口，底层自动适配 Frida Gadget（重打包注入）或 Zygisk/LSPosed（如果用户有环境）。
- **AI 辅助 Hook 生成**：用户说："把这个 App 的开屏广告去掉"，Agent 自动反编译 APK，找到开屏 Activity，生成对应的 Frida Hook 脚本（Java.perform(() => { ... })）并执行。

### 3. 全场景记忆系统 (Universal Memory RAG)

- 结构：应用包名 / 目标进程 -> Hook点特征 / 内存结构 / 网络协议特征。
- 不仅是游戏，任何 App 的破解过程（如去广告、解锁 VIP、提取数据）都会被向量化存储。下一次同类 App，Agent 可以直接复用知识。

## 三、全能型 MCP Tools 清单（Agent 的"武器库"）

### 📁 1. 进程与内存域 (Process & Memory)

- `get_running_processes()`：获取所有进程及 UID。
- `memory_scan(pid, value, type)`：全能内存搜索（支持精确、模糊、AOB）。
- `memory_hex_dump(pid, address, size)`：读取 Hex 结构，发给大模型分析数据结构（比如分析 JSON 字符串或 Protobuf 序列化数据）。
- `memory_write(pid, address, value)`：原子写入与快照。

### ☕ 2. Java/ART 域 (Java Runtime)

- `list_java_classes(pid)`：枚举已加载的 Class。
- `hook_java_method(class_name, method_name, js_code)`：注入 Frida JS 拦截 Java 方法。
- `call_java_method(class_name, method_name, args)`：动态调用 App 内部方法。

### 🔌 3. Native 域 (NDK & Assembler)

- `list_modules(pid)`：获取加载的 .so 列表。
- `hook_native_func(module_name, func_name, js_code)`：Native 函数 Hook。
- `disassemble(address)`：返回 ARM64 汇编代码。
- `set_hardware_breakpoint(address)`：设置硬件断点，捕获执行上下文。

### 🌐 4. 网络域 (Network & Traffic)

- `start_packet_capture(pid)`：启动抓包，提取 HTTP/WebSocket 数据。
- `bypass_ssl_pinning(pid)`：自动注入绕过 SSL Pinning 的脚本（如 JustTrustMe）。

### 🖱️ 5. 系统自动化域 (System & UI)

- `ui_inject_touch(x, y)`：Root 级别模拟真实物理点击（比无障碍更隐蔽）。
- `ui_dump_hierarchy()`：导出当前屏幕的视图树（UI XML）。
- `terminal_exec(cmd)`：执行 BusyBox 命令。

## 四、全能的"大脑"如何思考？(Agent 工作流示例)

场景：用户对"机关"说："帮我分析一下《某金融App》的登录加密逻辑，并尝试伪造登录。"

- **Thought 1**：需要先抓包看看请求结构。调用 start_packet_capture + bypass_ssl_pinning。
- **Observation 1**：抓到一个 POST 请求，其中 password 字段是加密的密文。
- **Thought 2**：去寻找加密代码。调用 ui_dump_hierarchy 找到密码输入框，或者直接调用 list_modules 搜索与加密相关的 so 库（如 libcrypto.so, libjni.so）。
- **Thought 3**：找到了可疑的 Java 类 com.xxx.EncryptUtil，调用 hook_java_method 打印出入参和出参。
- **Thought 4**：拿到密钥和算法了。让本地 Terminal 生成一段 Python 脚本，模拟该加密过程，并发送伪造的请求。

## 五、给顶级团队的核心挑战 (Critical Tasks)

1. **Android 碎片化适配**：从 Android 8 到 Android 15，从 MIUI 到鸿蒙，底层的内存映射、SELinux 策略、Binder 机制全在变。团队需要建立一个极庞大的自动化真机测试矩阵。
2. **LLM 的 Context 爆炸极限**：全能的代价是数据量庞大。ui_dump_hierarchy 返回的 XML 可能有几千行，list_java_classes 可能有上万个结果。Kotlin 本地层必须实现"智能数据过滤"，比如把 UI XML 精简为只有核心 ID 和 Text，再传给云端。
3. **无障碍与 Root 的无缝缝合**：有些操作 Root 方便（如输入、点击），有些操作无障碍更稳，Agent 必须能够根据情况自动切换执行通道。
4. **注入框架的通用性**：用户可能用 Root，可能没有。你们的 App 需要根据环境，动态选择 Frida 注入、Zygisk 模块、还是重打包。"一键重打包注入并重签"是一个非常硬核的基础功能。
