<!-- source: feat/memory-experience-tools -->
<!-- target: minor -->

## 记忆系统升级：任务后经验整理 + 图谱连线显示具体关联

- 新增 agent 记忆写入工具三件套：「MemoryConsolidateExperience」在任务完成或失败后把目标/结果/教训/知识实体/关系三元组整包沉淀进长期记忆（失败教训与成功经验同等入库）；「MemorySaveFact」保存单条长期事实（画像/近况/里程碑）；「MemoryGraph」按 MCP memory 语义建实体、补观察、建关系、搜子图
- 引擎系统提示词新增「Memory: Learn & Remember」策略段：任务前先查记忆、任务后必整理经验、对话中随手存事实 —— agent 从「只用记忆」升级为「边干边学」
- 记忆图谱连线现在显示具体关联：六类关系边全部带标签（知识关系用 relationType，其余五类用类型语义名，重复关系合并计数 ×N）；点选节点时其全部关联标签即时点亮；节点详情 Sheet 底部新增「关联关系」清单，逐条列出该节点与相邻记忆的具体关联并支持点击漫游（apex-kit-memory 库侧配套变更，锁文件已 bump）
