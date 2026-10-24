# 记忆系统完全重做：拆库 apex-kit-memory + 宿主薄接线层

- 认知记忆域整体拆库为独立模块 `com.apex.memkit:memory-core`（仓库
  Ultra-Guru/apex-kit-memory，纯 Kotlin JVM 零 Android 依赖，63 项单测）：
  统一旧 `:platform:cs-mem`（UI 轨迹 / FSM 宏 / Room 图库）与旧
  `app/mcp/builtin/memory`（聊天账本 / 知识图谱 JSON）三套存储为同一套
  JSON 原子文档（`memory_kit/{nodes,episodes,macros,chat,graph,quarantine}.json`，
  单写者 mutex + dirty 合并落盘，杀 Room 及其 N+1 写路径）。
- P0 生产断链根治：宏匹配 `appPackage` 由捕获端口单源提供全程贯穿
  （旧链路引擎传 null → 宏存 null → 匹配查前台包 → 永远失配）；采集与
  匹配共用同一条「捕获 → 降维 → 指纹」库内路径（旧采集用 roots 嵌套树、
  匹配用 nodes 扁平剥离视图的指纹系统性不一致从结构上消灭）。
- 宏模型类型化：`MacroAction` sealed（Tap/Swipe/Input/Back/Home），
  引擎动作字符串仅在库内 ActionNotation 一处解析；宏匹配从单指纹改为
  入口指纹集合重合率（含别名桥回退）。旧 FSM 宏的 `actionParams` 裸
  字符串由一次性迁移器类型化搬迁，任何一条解析失败即整宏丢弃。
- 会话并发根治：SessionDirector 多会话键控；宿主 `MemoryObserverAdapter`
  按协程 Job 祖先链关联会话（Agent 与 Code 引擎共享观察者时动作归因
  不再互踩）；引擎侧 `ExecutionMemoryObserver` 契约零改动。
- 维护机制去隐喻：能量衰减 / 剪枝 / 保鲜 / 晶化并入 `MacroMaintenance`
  （WorkManager 约束沿用旧"梦境"口径：充电 + 电量不低 + 空闲 + WiFi，
  每 2h）；免疫 → `IngestGuard`（策略可配、隔离键用节点指纹、带 TTL）。
- appVersion 取**被采集目标 App** 版本（宿主经 PackageManager 解析），
  拓扑别名桥的触发信号恢复正确；Episode 统计字段全为真实值。
- 宿主侧新模块 `:platform:memory-host`（薄接线层）：平台端口
  （PrivilegeSceneCapture / PrivilegeActionPerformer / ModelRuntimeChatModel）
  + Hilt DI（MemoryKitModule）+ 旧存储一次性迁移（LegacyCsMemMigrator：
  Room cs_mem_graph.db / mcp_memory/memory.json / chat_memory/ledger.json
  三源搬迁，幂等 + meta 标志守门 + `.imported` 留档防重入）+ 维护调度
  （MaintenanceWorker）+ 三个记忆召回工具（id 与参数零变化，模型侧
  无感迁移）。
- 记忆页 / 认知市场 / MCP memory transport 全部改读库门面
  （MemoryViewModel / SkillAnalytics / BuiltinMemoryMcpTransport）；
  旧模块 `:platform:cs-mem`（32 文件 5031 行）与旧聊天管线
  （KnowledgeGraphStore / MemoryLedgerStore / MemoryDistiller /
  ChatMemoryPipeline）删除；对应宿主单测迁移或由库内 63 项测试接管
  （新宿主层测试：迁移器 transitions 类型化映射 6 项 + MCP transport
  JSON-RPC 适配 6 项）。
- CI 接线（与 courier/loom 同构）：settings.gradle.kts 条件 includeBuild
  （兄弟/嵌套目录）+ `gradle/memory-kit.lock` 钉版 + ci/apk/release
  pinned checkout 步骤 + sync-memory-kit.yml 只跟绿提交自动 bump。
