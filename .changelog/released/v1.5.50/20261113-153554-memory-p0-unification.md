<!-- pr: memory-p0-unification -->

- 记忆系统 P0 止血批（全景重构方案 v2）：修复 5 个公开缺陷 + 四线记忆统一 + 写回闭环 + 四因子召回 + 逐条纠正面
- #851 维护调度修正：去掉与充电互斥的 `requiresDeviceIdle` 与不必要的 WiFi 约束（维护纯本地工作），改为「充电 + 电量不低」；每次冷启动追加一次性兜底维护（Worker 内电量门自适应：充电中或电量 > 50% 才执行）—— CS-Mem 自动维护从「真机从未运行」变为可靠触达
- #822 冷启动解耦：MemKit 单例解析 + 全量旧存储迁移整体挪到 IO 协程（MemoryKitInitializer），Application.onCreate 主线程不再触发 runBlocking 链（冷启动卡顿 + StrictMode 磁盘违例根除）
- #821 迁移列错位修正：旧 cs-mem episodes 迁移改按列名取值（started_at 不再错读 finished_at），字段值契约测试锁死列名映射
- #841 写入面双层钳制：Agent 工具（MemoryConsolidateExperience / MemorySaveFact / MemoryGraph）与内置 memory MCP transport 同口径限流（教训 ≤32 / 实体 ≤64 / 关系 ≤128 / 观察 ≤32、单条 2KB 截断带标记），模型幻觉式刷写在两个写入面都被拦下且越限计数显式回报
- #836 备份排除补全：memory_kit（认知记忆库本体）与 anr（看门狗堆栈转储）加入云备份/换机迁移排除，Android 11- 的 legacy 备份通道补齐全部 file domain 排除（与 12+ 对齐）
- 四线记忆统一（MemoryAwareCodeEngine）：标准 / 织造 / 信使三条 kit 线补齐「入口召回注入 + 工具轨迹入账 + 任务终局沉淀」闭环（此前仅深潜线有），四个 kit 库零改动（extras 通道复用），换逻辑线不丢长期记忆
- 四因子召回：0.45 重要性 + 0.30 新近度（14 天半衰）+ 0.10 频率 + 0.15 关键词加权评分，1 小时访问冷却防「越召回越新鲜」正反馈，token 硬截断整行装配（不再把最后一条记忆切成半句）
- MemoryForget 第 8 个记忆工具：逐条纠正（按内容或 id 删事实 / 删实体级联关系 / 删指定观察），歧义匹配拒绝并列出候选 —— 「你记错了」不再只能覆盖式追加
- 写回闭环：四个记忆写入/纠正工具进 CORE 工具集（不再藏 ToolSearch 两跳之后）+ 幂等分类（崩溃恢复可安全重放）；任务终局引擎兜底直写摘要事实（模型已主动整理时自动跳过，不重复）
- 压缩捕获：上下文压缩裁剪掉的对话不再对记忆系统不可见 —— User/Assistant 对自动转进记忆蒸馏管道（后台执行不阻塞压缩）
