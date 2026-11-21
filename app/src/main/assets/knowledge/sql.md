<!-- kb: id=sql | role=builtin_coding_sql | title=SQL 专家知识库 | version=1 -->
# SQL 专家知识库

## 必看内容

- **计划是唯一的事实**：`EXPLAIN (ANALYZE, BUFFERS)` 三开关缺一不可——没
  ANALYZE 只有规划器猜想，没 BUFFERS 看不见 I/O 真相；读计划先做 rows 估
  实对比，再算 rows×loops 倍乘（10、17 节）。
- **rows 误估是事故之源**：找树里第一个 estimate≠actual 的节点（通常在树
  底），它上面的误差全是被污染的放大；三类根因对应三种修法：统计缺失
  （32 节）、列相关（34 节）、倾斜参数×通用计划（36 节）。
- **random_page_cost=4.0 是机械盘遗产**：SSD/NVMe 降到 1.1，计划会从
  bitmap 翻回 index scan；effective_cache_size 是给规划器的提示、不是
  内存分配——两个参数都影响每个计划（8、11 节）。
- **索引不是免费的**：每行写入=每个索引一次页写；非 HOT 更新要在**所有**
  索引插新项（不只是被改列的）。热表健康指标是 n_tup_hot_upd/n_tup_upd，
  配方：fillfactor 80-90 + 更新别碰索引列（20、31 节）。
- **work_mem 是"每节点×每并发"**：200 连接×每查询 3 节点×64MB 就是 38GB
  的灾难数学；溢出证据链：Sort Method external merge / Hash Batches>1 /
  BUFFERS temp read+written（51 节）。
- **迁移三律**：DDL 前 `SET lock_timeout` + 重试包裹（42 节）；零停机走
  expand-migrate-contract，NOT VALID 后 VALIDATE（45 节）；
  CREATE INDEX CONCURRENTLY 不能进事务、失败留 INVALID 索引（46 节）。
- **transaction 池化是功能税**：SET/临时表/LISTEN/advisory 会话锁/带
  WITH HOLD 游标全废（47 节）；prepared statements 需要 PgBouncer ≥1.21
  的 max_prepared_statements，且 SQL 级 PREPARE 仍不支持（48 节）。
- **长事务是万恶之源**：最老快照卡死 vacuum→膨胀；idle in transaction
  杀无赦（55 节）；复制槽再把 WAL 堆到盘满——两者都是"无声事故"（58 节）。
- **PG 的 REPEATABLE READ 不是教科书 RR**：没有幻读（快照读），但允许
  写偏斜；只有 SERIALIZABLE（SSI）拦得住写偏斜。40001 重试=整事务回滚
  重来，重试逻辑只封装在一处（40、41 节）。
- **prepared statement 第 6 次执行翻车**：第 6 次起切换通用计划，倾斜
  参数×通用计划=秒变全表扫；17+ 用 EXPLAIN (GENERIC_PLAN) 自检（36 节）。
- **统计先行**：ANALYZE 采样 300×statistics_target 与表大小无关，10 亿行
  的表也只采 3 万行；大倾斜列上调 statistics_target、跨列相关上 CREATE
  STATISTICS；大 COPY 之后手动 ANALYZE（32-34 节）。
- **版本红利要主动吃**：18 的异步 I/O（顺序扫描 2-3x）/uuidv7()/skip
  scan/pg_upgrade 统计随迁；17 的 incremental backup 与 failover slots——
  升级预案的收益往往大于一整周调参（1-2 节）。

## 快速索引

| 想找什么 | 去哪一节 |
| --- | --- |
| PG18 有什么新东西、要不要升 | 1. PostgreSQL 18 新特性与升级决策 |
| PG17 有哪些值得吃的红利 | 2. PostgreSQL 17 生产特性清单 |
| MySQL 8.4 与 9.x 选哪条线 | 3. MySQL 8.4 LTS 与 9.x 创新版差异 |
| SQLite 默认参数能不能上生产 | 4. SQLite 嵌入式生产基线 |
| 托管库和自建的行为差异 | 5. 云托管 Postgres 差异矩阵 |
| 大版本升级选哪条路线 | 6. 大版本升级三路线 |
| 扩展（pgvector/Citus）能不能上生产 | 7. 扩展生态生产评估 |
| 参数怎么调才不背旧时代的债 | 8. 参数分层调优法 |
| 版本支持窗口与升级节奏 | 9. 版本支持与升级纪律 |
| 查询计划从哪一行读起 | 10. EXPLAIN (ANALYZE, BUFFERS) 阅读法 |
| cost 数字是怎么算出来的 | 11. cost 模型内幕 |
| rows 估不准怎么验尸 | 12. rows 低估与高估验尸法 |
| join 该走哪种算法 | 13. 三种 Join 算法选择内幕 |
| nested loop 为什么快/慢 | 14. Nested Loop 与参数化重扫 |
| bitmap scan 的 lossy 是什么 | 15. Bitmap Heap Scan 与 lossy 降级 |
| merge join 什么时候最划算 | 16. Merge Join 与排序代价 |
| BUFFERS 与 I/O Timing 怎么读 | 17. Buffers 与 I/O Timing 解读 |
| JIT 到底开不开 | 18. JIT 编译成本与去留 |
| 生产环境怎么抓慢计划 | 19. auto_explain 与计划留痕 |
| B-tree 内部结构怎么影响性能 | 20. B-tree 页面布局与 dedup |
| 复合索引列序怎么定 | 21. 复合索引列序决策 |
| INCLUDE 与复合索引怎么权衡 | 22. INCLUDE 覆盖索引权衡 |
| partial 索引用在什么场景 | 23. Partial 索引设计模式 |
| 表达式索引有什么坑 | 24. 表达式索引与 IMMUTABLE 陷阱 |
| 排他约束（范围不重叠）怎么写 | 25. 排他约束与 btree_gist |
| GIN 的真实代价在哪 | 26. GIN 代价模型与 fastupdate |
| 模糊搜索与近邻排序怎么走索引 | 27. GiST 与 KNN 近邻 |
| BRIN 什么时候能用 | 28. BRIN 与物理相关性 |
| hash 索引有存在意义吗 | 29. Hash 索引与唯一约束 |
| 索引膨胀怎么诊断与在线重建 | 30. 索引膨胀诊断与在线重建 |
| 索引让写入慢了多少 | 31. 索引写放大数学 |
| ANALYZE 采多少样才够 | 32. ANALYZE 采样内幕 |
| distinct 数估错怎么办 | 33. n_distinct 调优 |
| 列相关导致烂计划怎么救 | 34. 扩展统计与列相关性 |
| 怎么强制一个计划（hint 纪律） | 35. 计划锁定与 pg_hint_plan 纪律 |
| prepared statement 第 6 次变慢 | 36. generic 与 custom plan 陷阱 |
| CTE 是不是性能围栏 | 37. CTE 内联与 MATERIALIZED 围栏 |
| MVCC 版本链怎么读 | 38. MVCC 版本链解剖 |
| 快照到底是什么时候建立的 | 39. 快照可见性规则 |
| PG 的 RR 和教科书 RR 不一样 | 40. 隔离级别异常矩阵与 SSI |
| 40001 serialization failure 怎么重试 | 41. serialization failure 重试协议 |
| 为什么全部查询在排队等锁 | 42. 锁队列与 lock ordering |
| 外键怎么引发死锁 | 43. 行锁变体与外键死锁 |
| advisory lock 的正确用法 | 44. advisory locks 模式 |
| 零停机加列与回填怎么做 | 45. 零停机迁移：expand-migrate-contract |
| 迁移跑了一半失败怎么恢复 | 46. 幂等迁移与 DDL 并发纪律 |
| pgbouncer transaction 模式破坏什么 | 47. 连接池模式与 pgbouncer 陷阱 |
| 事务池化与 prepared statements 兼容 | 48. 事务池化与 prepared statements 兼容 |
| session/transaction/statement 池化怎么选 | 49. 三种池化模式选择 |
| cache 命中率能信吗 | 50. cache hit ratio 神话 |
| work_mem 到底多大合适 | 51. work_mem 溢出诊断 |
| N+1 在 SQL 层怎么治 | 52. N+1 在 SQL 层的形态 |
| LATERAL 和相关子查询谁好 | 53. LATERAL 与相关子查询 |
| 批量写入怎么最快 | 54. 批量写入：COPY 与 unnest |
| 长事务与 idle in transaction 的危害 | 55. 长事务与 idle in transaction |
| 慢查询排查从哪个视图开始 | 56. pg_stat_statements 与性能基线 |
| 物理还是逻辑复制 | 57. 物理 vs 逻辑复制选型 |
| 复制槽把磁盘吃满了 | 58. 逻辑复制槽与 WAL 膨胀 |
| 逻辑复制不复制什么 | 59. 逻辑复制限制清单 |
| synchronous_commit 开哪档 | 60. 同步复制与 synchronous_commit |
| failover 怎么防脑裂 | 61. 故障转移与脑裂防护 |
| 延迟副本与级联复制 | 62. 级联复制与延迟副本 |
| 17 的 failover slots 怎么用 | 63. 复制槽故障转移（17 failover slots） |
| CDC 架构（outbox）怎么搭 | 64. CDC 与 outbox 模式 |
| 多主的坑与冲突解决 | 65. 多主与冲突解决 |
| RPO/RTO 与演练纪律 | 66. RPO/RTO 与演练纪律 |
| 窗口 frame 选错有多贵 | 67. 窗口函数 frame 选择成本 |
| 连续区间断档（gaps-and-islands） | 68. gaps-and-islands 模式 |
| 深分页怎么救（keyset） | 69. keyset 分页与 OFFSET 退化 |
| 递归 CTE 跑飞了怎么防 | 70. 递归 CTE 与收敛控制 |
| 多维小计聚合（GROUPING SETS） | 71. GROUPING SETS 与聚合分析 |
| 每组取第一行怎么做 | 72. DISTINCT ON 方言 |
| JSONB 索引与查询选型 | 73. JSONB 深水与 GIN 选择 |
| 数组与 unnest 批处理 | 74. 数组批量与 unnest 进阶 |
| 物化视图怎么做增量 | 75. 物化视图与增量刷新 |
| 生成列 stored vs virtual（18） | 76. 生成列：stored 与 PG18 virtual |
| NOT VALID 约束的可信窗口 | 77. NOT VALID 约束与可信窗口 |
| 视图谓词为什么不下推 | 78. 视图与 security_barrier |
| 时区怎么存才不翻车 | 79. timestamptz 时区纪律 |
| text 还是 varchar | 80. text 与 varchar 神话破除 |
| UUID 主键怎么选（v4 vs v7） | 81. UUIDv7 与索引局部性 |
| numeric 还是 float8 | 82. numeric 与 float8 取舍 |
| enum 还是 lookup 表 | 83. enum 锁陷阱与 lookup 表 |
| 外键的隐藏写放大 | 84. 外键写放大与 ON DELETE |
| 分区表与裁剪 | 85. 分区策略与裁剪 |
| 加列/改类型要不要重写全表 | 86. 免 rewrite 表改造 |
| identity 和 serial 差在哪 | 87. identity 与 serial 之争 |
| 软删除怎么不拖垮查询 | 88. 软删除的查询污染 |
| autovacuum 触发公式与调优 | 89. autovacuum 调优公式 |
| 表膨胀诊断与修复路线 | 90. 表膨胀诊断与修复 |
| wraparound 急救与 freeze | 91. freeze 与 wraparound |
| 该监控哪些红灯指标 | 92. 红灯监控指标清单 |
| 备份与 PITR 设计 | 93. 备份与 PITR |
| pg_upgrade 与回滚窗口 | 94. pg_upgrade 与回滚窗口 |
| 连接数失控怎么治 | 95. 连接风暴治理 |
| 磁盘满与 WAL 堆积应急 | 96. 磁盘满与 WAL 堆积应急 |
| RLS 行级安全怎么分层 | 97. RLS 行级安全分层 |
| 最小权限与审计 | 98. 最小权限与审计 |
| 隐式转换怎么杀死索引 | 99. 隐式 cast 杀索引 |
| 谓词被函数包住走不了索引 | 100. 函数包裹谓词 |
| SELECT * 有多贵（TOAST） | 101. SELECT * 与 TOAST 成本 |
| COUNT(*) 精确与估算 | 102. COUNT(*) 成本与近似计数 |
| OR 条件的计划差异 | 103. OR 与 UNION ALL 计划差异 |
| 动态 SQL 防注入 | 104. 动态 SQL 与注入防线 |
| EAV 反模式怎么救 | 105. EAV 反模式 |
| 视图套视图计划失控 | 106. 视图滥用与计划穿透 |
| SQL 评审清单 | 107. SQL 评审清单 |
| 事故复盘怎么做 | 108. 事故复盘方法论 |
| MySQL 死锁与 gap lock 差异 | 109. MySQL 专家差异清单 |
| SQLite 生产深水区 | 110. SQLite 生产深水 |
| 跨库方言与 ORM 策略 | 111. 跨库方言策略 |
| 线上失火分钟级速查 | 112. 紧急处置速查表 |

## 1. PostgreSQL 18 新特性与升级决策

- 2025-09-25 发布。最大件是**异步 I/O**（`io_method`：sync/worker/
  io_uring）：顺序扫描 2-3x、批量导入约 2x；随机读路径不变。
- **原生 `uuidv7()`**：时间前缀毫秒有序、兼容既有 UUID 列，新表主键
  默认选它（81 节）。io_uring 需内核 ≥5.1，生产建议 ≥5.10。
- **B-tree skip scan**：未约束首列时按首列取值逐个探测，低基数开头的
  复合索引泛用性大增，21 节"最左前缀"老经验要按此打折。
- **virtual 生成列成默认**：读时计算写不占空间；重表达式高频读仍选
  STORED（76 节）。NOT NULL 约束可先 NOT VALID 再 VALIDATE（45 节）。
- **pg_upgrade 统计随迁**——"升级后必须全库 ANALYZE"的守则免掉大半；
  16→18 可直跳，扩展/备份工具逐个验证，保守派等首个 minor。

```sql
SELECT uuidv7();  -- 0193xxxx-... 时间前缀有序
CREATE TABLE orders (id uuid PRIMARY KEY DEFAULT uuidv7(),
  amount numeric GENERATED ALWAYS AS (price * qty));  -- 默认 VIRTUAL
```

- 陷阱：io_method 起不来不报错——容器 seccomp 可能静默回退 worker；
  用 `pg_stat_io` 与升级前同负载对照，别只看 uptime。

## 2. PostgreSQL 17 生产特性清单

- **incremental backup**：`pg_basebackup --incremental` + `pg_combinebackup`，
  大库从"每晚全量"变"周日全量+每日增量"（93 节）。
- **failover slots**：`sync_replication_slots=on` 让主备切换后逻辑订阅
  存活（63 节）；pg_stat_checkpointer 看 checkpoint 压力（92 节）。
- **VACUUM 内存重构**：内存降到约 1/20（`vacuum_buffer_usage_limit`
  可调），大表 vacuum 不再被 256KB 环形缓冲拖成反复全表扫（91 节）。
- **streaming I/O**：顺序读预取管线，大表扫描/拷贝提速可达 ~3x；
  `EXPLAIN (GENERIC_PLAN)` 不执行即可看通用计划（36 节）。
- 新 GUC `transaction_timeout` 兜底僵尸事务（55 节）；
  **pg_createsubscriber** 把物理备库原地转逻辑订阅者（6 节零停机）。
- dump 口径变化：17 起 sequence 权限导出格式调整，dump 回灌旧版本可能报错（94 节）。

```sql
ALTER SYSTEM SET sync_replication_slots = on;
ALTER SYSTEM SET hot_standby_feedback = on;  -- 主备都设；副作用见 63 节
```

- 陷阱：vacuum 新内存上限不低、会抢 shared_buffers——与
  autovacuum_vacuum_cost_limit 联动调（89 节），单独调大适得其反。

## 3. MySQL 8.4 LTS 与 9.x 创新版差异

- Oracle 双轨：**8.4 LTS（2024-04）**保守修 bug+安全；创新版 9.x 每季
  滚动、无 LTS 承诺——生产基线 8.4，创新版只进预发。
- 8.4 的"惊喜"是**默认值收紧**：innodb_change_buffering 默认 none、
  mysql_native_password 默认关（9.0 彻底移除）——先跑变量差集。
- **GTID Tags**：事务可带来源标注，复制拓扑排障与审计的正规武器；
  MySQL 跨大版本只支持逐级升级，没有跨代跳级。
- 纠偏：**DESC 索引 8.0 才真生效**（5.7 只解析不使用）——5.7 迁来的
  schema 里 DESC 是装饰品，排序方向负担要重审。
- 8.0→8.4 序列：先到最新 8.0 minor，用 `mysqlsh --util
  check-for-server-upgrade` 清废弃语法，再升 8.4。

```sql
-- 8.4 起 change buffer 默认关；写多读少且依赖它的库先评估
SELECT @@innodb_change_buffering, @@mysql_native_password;
SET PERSIST innodb_change_buffering = 'all';  -- 确有收益才保留
```

- 陷阱：utf8/utf8mb4 混用的校对规则差异会让 JOIN 走不上索引（99 节
  同族）；升级演练要带真实数据量的 JOIN 压测，不是空库冒烟。

## 4. SQLite 嵌入式生产基线

- 默认配置=事故：**busy_timeout 默认 0**（并发写把 SQLITE_BUSY 甩给应用）、
  journal 默认 DELETE。启动必设 busy_timeout 与 journal_mode=WAL（持久）。
- **WAL + synchronous=NORMAL**：掉电只丢最后事务不损坏库、吞吐数倍于
  FULL——移动/桌面/边缘标准组合，FULL 旧教条别带过来。
- **STRICT 表（3.37+）**：真类型检查，新表一律 STRICT；JSON 函数
  3.45 起内部换 JSONB 解析树，全家显著提速。
- 3.50（2025-05）：新增 blocking-lock 专用超时、修复 WAL 重置损坏 bug——旧长驻进程要升。
- **ANALYZE 不会自动跑**：大变更后手动 PRAGMA analyze（110 节）；
  SQLite 随应用打包，别信系统源（9 节）。

```sql
PRAGMA journal_mode = WAL;     -- 持久化，一次设置
PRAGMA synchronous = NORMAL;   -- WAL 下安全且快
PRAGMA busy_timeout = 5000;    -- 每连接都要设！
CREATE TABLE t(id INTEGER PRIMARY KEY, k TEXT) STRICT;
```

- 陷阱：busy_timeout 是**连接级** PRAGMA，连接池/新连接忘设就复发；
  VACUUM 与 checkpoint TRUNCATE 的 EXCLUSIVE 不受它保护。

## 5. 云托管 Postgres 差异矩阵

- 共同约束：**无 superuser**——扩展白名单制、部分系统 GUC 禁改、无
  本地 shell；pg_hint_plan 这类要预载+特权的扩展基本没戏（35 节）。
- Aurora：存储自管（自动扩、6 副本）failover 秒级；代价是参数面窄、
  大版本升级走蓝绿副本；RDS Proxy 做连接聚合（49 节定位）。
- AlloyDB 列存引擎对分析查询量级加成；Cloud SQL 是社区版直板——选型看查询形状。
- Neon/Supabase：**计算存储分离**。Neon 缩到零（冷启动延迟进 P99）、
  分支即复制；Supabase 6543 transaction 池改写"连接"语义——47-48 节
  的坑在这里是**默认状态**而非可选配置。
- 迁移前对差：ALTER SYSTEM 清单、扩展列表、超时/协议行为逐项与云侧
  允许列表对差；压测压在目标云，不是本机 docker。

```sql
-- 自建→云能力对差
SELECT name, setting FROM pg_settings WHERE source <> 'default';
SELECT name, installed_version FROM pg_available_extensions;
```

- 陷阱：云的"兼容"止于 SQL 层——cancel 语义、协议参数、超时行为
  各有私货；审计/防火墙接口按云重写，不是改个主机名。

## 6. 大版本升级三路线

- **pg_upgrade**：原地硬转换（拷目录或 `--link` 硬链接秒切），同机+
  分钟级停机适用；18 起统计随迁，17 前升级后必须立即 ANALYZE 全库（12 节）。
- **逻辑复制蓝绿**：新实例起来、发布/订阅、追平切流——零停机；
  DDL/序列手工同步（59 节）。17+ 用 pg_createsubscriber 把物理备库
  就地转订阅，省掉初始拷贝。
- **pg_dump/restore**：跨平台/跨引擎/顺便瘦身才选，时长线性放大；三
  路线都要在等量副本上**计时演练**，把时长写进停机窗口申请。
- 回滚窗口：`--link` 旧目录即回滚点，但新库一旦接写即失效；"能回滚
  多久"写进 runbook（94 节）。
- 扩展连坐：每个扩展的 .so 都要按新 major 重建；vector/timescaledb
  带 catalog 变更的要单独升级，顺序错了起不来（7 节）。

```bash
pg_upgrade -b /opt/pg17/bin -B /opt/pg18/bin \
  -d /data/old -D /data/new --check -j 8  # 检查模式先行；旧目录绝不删
```

- 陷阱：`--link` 后新旧目录文件互为硬链接——把旧目录当"第二份备份"
  会在删除时把新库一起删掉；失败清理也按文档顺序来。

## 7. 扩展生态生产评估

- pgvector：HNSW（查询快、构建慢、内存大）vs IVFFlat（构建快、依赖
  lists 与训练数据质量），生产默认 HNSW；**索引参数与查询参数
  （hnsw.ef_search）是两套旋钮**，召回率必须在真实分布上调。
- IVFFlat 的 lists 数决定桶质量：空表/未 ANALYZE 时建的桶极差，先灌
  数据再建；HNSW 索引常驻内存，先做内存预算再上生产。
- Citus/TimescaleDB：深度改写执行器/存储，**与 major 升级强耦合**
  （新 PG 出来数月后才可用）、部分云不提供——时间差要算进升级计划。
- pg_partman：分区维护事实标准，但依赖 pg_cron（RDS/Aurora 可用性各异）——创建/回收语义必须演练（85 节）。
- 上生产三道门：(1) 托管云/自建双支持？(2) 阻塞 major 升级吗？(3)
  故障时可否无损禁用？三问全过再上；扩展×PG major 兼容矩阵进 repo。

```sql
CREATE INDEX ON docs USING hnsw (embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 200);
SET hnsw.ef_search = 100;  -- 召回/延迟的交易旋钮
```

- 陷阱：IVFFlat 在空表/未 ANALYZE 时建的桶质量极差——先灌数据再建；
  HNSW 的 DROP 是慢操作，DDL 窗口按索引大小预留。

## 8. 参数分层调优法

- 拒绝速查表：shared_buffers=25% 是 HDD+小内存时代经验。唯一方法论：
  **先定瓶颈（wait_event/pg_stat_io）→ 只改一个参数 → 挂监控验证**。
- effective_cache_size：告诉规划器"缓存总盘子多大"，**只影响 cost、
  不是内存分配**——不调它规划器默认你只有 4GB（11 节）。
- random_page_cost：机械盘 4.0；SSD/NVMe 1.1；云网络盘 2-4 按压测
  定。改完逐条 EXPLAIN diff 主查询，观察 bitmap/index 翻转。
- checkpoint 数学：max_wal_size 默认 1GB 对写多库太小→checkpoint 频繁
  →full-page writes 与 I/O 尖刺；按"间隔 ≥15-30 分钟"倒推容量。
- huge_pages=try（大缓冲下 TLB 显著）；OLTP 库 jit=off（18 节）；
  checkpoint_completion_target=0.9；track_io_timing=on 为 17 节取证开。

```sql
-- 改前先量：checkpoint 频度与写压力
SELECT checkpoints_timed, checkpoints_req FROM pg_stat_bgwriter;
-- 17+ 另看 pg_stat_checkpointer
```

- 陷阱：shared_buffers 过大出现双缓冲（PG+OS 页缓存）反而更慢；
  autovacuum_vacuum_cost_limit 默认 200 对大库=永远追不上死元组。

## 9. 版本支持与升级纪律

- PostgreSQL：每年秋一个 major、每个 major 支持 5 年（13 已 EOL）；
  minor 约每季度一发，**含安全与数据损坏修复，必须跟**——minor 是
  唯一可流水线自动化的升级，做成 CI 常态。
- MySQL：8.0 已过主线，8.4 LTS 是长期驻地，9.x 创新版季度滚动；
  SQLite：公有领域、**随应用打包指定版本**——系统源的 libsqlite3
  可能老得没有 JSONB/STRICT（4 节）。
- CVE 响应：PG 安全修复通常进 minor；通告到手先评估暴露面，≤7 天内打上；"评估-打-验"写成 SOP。
- 升级节奏：major 每年固定月份（避开业务峰）做一次；N+1 落后不超过
  一个 major——落后两代要补跨版本扩展/备份兼容审计，风险非线性升。
- EOL 是 hard deadline 不是"还能跑"：老版本不收安全修复、新扩展/
  驱动逐步拒绝构建；到期日写进年度计划（6 节三路线常备）。

```sql
-- 版本水位巡检：低于"当前-1"即两级落后，升级优先级上调
SELECT version(), current_setting('server_version_num') AS vnum;
```

- 陷阱：云托管的"自动小版本升级"窗口与自己的维护窗口会打架——先
  读清 SLA 里谁管 minor、谁管 major，别假设对方兜底。

## 10. EXPLAIN (ANALYZE, BUFFERS) 阅读法

- 三开关缺一不可：EXPLAIN 只有规划器猜想；+ANALYZE 才有 actual——但
  它**真的执行**（DML 真改数据，包在 BEGIN 后 ROLLBACK）；+BUFFERS 看 I/O。
- 数据流自下而上读（子节点先产出），时间/成本自上而下归因（顶层
  total 含全部子节点，别重复累加）。
- **rows 是每次循环的平均值**：actual rows 要乘 loops 才是总量，时间
  同理；`(rows=1 loops=200000)` 的 inner 节点才是 NL 的真实成本。
- 先做全树估实对比：第一个 estimate 与 actual 差 10 倍以上的节点几乎
  总在树底——修它，上层误差全是传播（12 节验尸流程）。
- SETTINGS 选项（12+）：把非默认 GUC 打进输出——重放"当时为什么是这个计划"的必备证据。

```sql
EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT * FROM orders o JOIN users u ON u.id=o.uid WHERE o.status=2;
-- Nested Loop  (actual time=0.3..890 rows=200 loops=1)
--   -> Index Scan using users_pkey on u (rows=1 loops=2000)
```

- 陷阱：ANALYZE 的 instrumentation 本身有百分之几开销，别把输出里的
  时间当生产延迟基准；两计划对比要在同一开关状态下跑。

## 11. cost 模型内幕

- cost 单位=顺序读一页的开销：seq_page_cost=1.0、random_page_cost=4.0、
  cpu_tuple_cost=0.01、cpu_operator_cost=0.0025（函数/运算逐行计价）。
- cost 不预测时间，只用于**比较**：`cost=0.29..12345.67` 是
  startup..total——startup 高意味着首行出得慢（交互分页场景致命）。
- cpu_operator_cost 决定"函数包裹谓词"的可见代价：SELECT 里每行的
  函数调用都会进 cost（100 节是它的运维面）。
- random_page_cost=4.0 是机械盘世界观：SSD/NVMe 降到 1.1 计划会从
  bitmap 翻回 index scan；云网络盘按盘压测定，不搞一刀切（8 节）。
- effective_cache_size 决定"表被假定缓存的比例"→ index scan 按剩余
  页数算 random 页——大内存机器不调它等于自缚手脚。

```sql
SHOW seq_page_cost, random_page_cost, effective_cache_size;
-- 诊断用：给某算法定天价（别进生产配置）
SET enable_seqscan=off;  -- 等价 seq scan cost+1e10（disable_cost）
```

- 陷阱：enable_* 是诊断开关——写进 postgresql.conf 的 enable_*=off 是
  "用答案伪装问题"；两个 1e10 说明你把路径全关了。

## 12. rows 低估与高估验尸法

- 流程：从树底向上找**第一个**失真节点；上层误差全是它的传播，先修
  根，再看是否还有第二处失真。
- 根因 A：统计缺失/过期——大 COPY 后没 ANALYZE、表达式无统计（索引
  表达式能给规划器补上统计，24 节）→ 32 节先做。
- 根因 B：列相关——(country, city) 独立假设失真 → CREATE STATISTICS（34 节）。
- 根因 C：倾斜参数 × 通用计划——第 6 次执行起换 generic（36 节）；
  `EXPLAIN (GENERIC_PLAN)`（17+）可直接复现。
- 后果三层：join 算法翻转（13）、sort/hash 按低估行数给内存→溢出
  （51）、bitmap/index 档位选错（15）。
- 特殊形态：估 rows=1、actual 巨大——多见于 IN (子查询)/动态条件，
  规划器缺信息只能用默认选择率 0.005，把谓词改写成可统计的形状。

```sql
SELECT n_distinct, correlation, histogram_bounds
FROM pg_stats WHERE tablename='orders' AND attname='status';
```

- 陷阱：误估时"再加一个索引"常是把症状盖住的安慰剂——统计修好前
  建的索引，等计划翻转后可能全成写放大负债（31 节）。

## 13. 三种 Join 算法选择内幕

- **Nested Loop**：外行数×内单次查找成本；适合小外+索引内；内存≈0
  （inner 若物化则多一份 Materialize）。
- **Hash Join**：等值 join 专属；build 端建哈希表≈work_mem，超出分
  batch → temp 文件多轮回写；`Hash Batches: 4` 就是溢出标记。
- **Merge Join**：两边有序才能走；归并本身流式内存≈0，但输入排序要
  付 work_mem；非等值条件没资格入选。
- 规划器按 cost 选、误估会翻转：低估内表行数→NL 灾难；高估外层→
  Hash 白建白溢出（12 节）。
- 内存画像速记：NL 怕"外大×内慢"、Hash 吃 work_mem、Merge 吃排序；
  parallel hash join 共享 build，是大表 join 的现代默认形态。

```sql
-- 诊断用的算法隔离实验（跑完必须还原）
SET enable_hashjoin=off; SET enable_mergejoin=off;  -- 逼出 NL
SET enable_nestloop=off; SET enable_mergejoin=off;  -- 逼出 Hash
```

- 陷阱：enable_* 只加天价 cost 不是真禁用；实验结论要回默认配置
  复跑验证，否则你优化的是"实验环境"。

## 14. Nested Loop 与参数化重扫

- NL 成本=外行数×内节点单次成本；inner 是带 join 键的参数化扫描，
  EXPLAIN 里 inner 的 rows 是**单次平均**，总量=rows×loops。
- loops 是关键读数：`(Index Scan ... rows=1 loops=100000)` 没乘
  loops 就是自欺——10 万次索引点查可能是好事也可能是灾难，看总时长。
- Materialize 节点=inner 物化一次、重复扫描吃缓存；BUFFERS 里 shared
  hit 随 loops 涨而 read 不涨=物化缓存命中，健康。
- 外层顺序随机会打爆 buffer cache：inner 页反复换入换出（read 高企）；
  把外层按 inner 索引序排过再 join 可救回。
- 正确用法是"外小内精"：批量化点查用 LATERAL+数组一次往返（52 节），
  而不是 ORM 逐行发 N+1。

```sql
-- 批量点查形态：一次解析、逐行参数化（NL 的正确打开方式）
SELECT o.* FROM unnest($1::bigint[]) AS k(id)
JOIN LATERAL (SELECT * FROM users WHERE id=k.id) u ON true;
```

- 陷阱："出现 NL=缺索引"是误读——NL+inner Index Scan 常是最优解；
  红灯是 NL+inner **Seq Scan** 且 loops 上千：每外行一次全表扫。

## 15. Bitmap Heap Scan 与 lossy 降级

- 位图的价值：把随机读批量化——先攒页位图再按物理顺序扫堆；代价是
  丢掉索引序（ORDER BY 不能白吃，需再排一次）。
- **exact 模式**按 TID 存位图；内存超 work_mem 降级 **lossy**：只留
  页号、整页重查可见性——`Recheck Cond` 就是重查动作。
- `Heap Blocks: exact=1000 lossy=64`：lossy>0 即内存缺口，查询在读
  几乎整页；退化严重时位图≈伪顺序扫，优势清零。
- 三档选择：点查→Index Scan；中等匹配→Bitmap；大范围匹配→Seq Scan
  更便宜。规划器按 rows 估选档，误估会卡错档（12 节）。
- BitmapAnd/BitmapOr 是多索引组合的正规形态：两个单列索引也能 AND/
  OR 组合——先看位图组合再谈复合索引（21 节）。

```sql
-- lossy 出现的两手：放宽本查询 work_mem，或收窄谓词/分批
SET LOCAL work_mem='64MB';
-- 修复标志：Heap Blocks: exact=50000 lossy=0
```

- 陷阱：看到位图扫描就"加索引"是错向——位图恰说明索引在工作；
  lossy 才是缺口（51 节的分层配置法）。

## 16. Merge Join 与排序代价

- Merge Join 适用等值 join+两边有序（索引序或上游 Sort）；归并流式
  内存≈0，"大数据+有序"的最优解；非等值会退化成 NL。
- Sort 三分支：quicksort（内存内）、external merge Disk（溢出）、
  **top-N heapsort**（LIMIT k 只维护 k 行——大排序配 LIMIT 的救星）。
- 排序内存按"每 Sort 节点一份 work_mem"叠加：一个查询多个 Sort=
  多份配额，并发再乘上去（51 节的乘法）。
- Incremental Sort（13+）：已有前缀序时按小分组续排——计划长得怪，
  但常是"索引序与 ORDER BY 差一列"的最优解，别急着否定。
- 报表黄金组合：ORDER BY 与 join key 一致 → 一次排序供 join 与输出
  共用，Sort 节点只出现一次。

```sql
SET LOCAL work_mem='256MB';
-- Sort Method: external merge Disk: 25MB → 溢出实锤
-- Sort Method: top-N heapsort Memory: 25kB → LIMIT 路径，健康
```

- 陷阱："external merge" 常年出现在某报表上不是背景噪音——该给它
  work_mem、有序索引路径，或质疑这个排序本身是否必要。

## 17. Buffers 与 I/O Timing 解读

- shared hit=PG 缓存命中；read=绕过 shared_buffers（**未必是物理盘**，
  OS 页缓存可能已接住）；temp read/written=工作文件溢出（51 节）。
- written=本查询弄脏的页数——写多场景把 checkpoint 压力归因到单查询
  的抓手（8 节全局视角）。
- I/O Timings 需要 track_io_timing=on（16+ 开销已低）：read 时间按
  节点拆分，用来定位"计划没毛病但盘慢"的真相。
- 读法：顶层聚合看总量；按节点找"哪层在读"；hit/read 与 rows 对照，
  判断顺序预取（1 节 18 异步 I/O）有没有吃到红利。
- pg_stat_io（16+）按上下文（normal/vacuum/bulkread）拆全局 I/O——
  EXPLAIN 是单查询视角、它是实例视角，两者互补（50 节）。

```sql
EXPLAIN (ANALYZE, BUFFERS) SELECT ...;
-- Buffers: shared hit=1234 read=567 written=8
--          temp read=512 written=512  ← 溢出实锤，去 51 节
```

- 陷阱：把 read 直接当磁盘 I/O 会误判——先看 OS 指标（iostat）；把
  单查询Buffers 与全局命中率混谈是两个层次的错误（50 节）。

## 18. JIT 编译成本与去留

- 三个阈值：jit_above_cost=100000（默认）触发编译；inline 与
  optimize 各自 500000——计划 cost 越贵编得越狠。
- 编译开销在 "JIT:" 块单列（Generation/Inlining/Optimization 各一行
  毫秒数）——先读它分辨"编得久"还是"跑得久"。
- OLTP 短查询 cost 远低于阈值，JIT 根本不触发；混合负载生产库的
  常见决策是 jit=off，分析仓库保留 on。
- PG15/17 大幅提速 JIT 生成，但摊销逻辑不变：查询越短、越频繁，
  LLVM 的固定成本越亏。
- 判断法：同一查询 jit=on/off 各跑 EXPLAIN (ANALYZE)，看总时间差与
  JIT 块占比；差异<5% 就别折腾。

```sql
SHOW jit_above_cost;         -- 默认 100000
SET jit=off;                 -- OLTP 混合库的常见生产决策
-- EXPLAIN 里出现 "JIT:" 块=本次真的编译了
```

- 陷阱：升级后偶发变慢，JIT 是常被漏掉的嫌疑——成本模型变化可能
  把某查询首次推过阈值；diff 计划先看 JIT 块冒出来没有。

## 19. auto_explain 与计划留痕

- pg_stat_statements 只有聚合数字、没有计划文本——"计划漂移"的
  生产证据链要靠 auto_explain 采样留档（56 节的另一面）。
- 最小可用组合：log_min_duration='1s' + log_buffers=on +
  log_format=json——进日志平台可检索、可按 query 聚合。
- log_analyze=on 才有 actual 行数与时间，代价是 instrumentation
  开销放大；生产用 sample_rate 兜底（0.01=只采 1%）。
- log_nested_statements 会把 PL/pgSQL 函数体内语句全打出——定位
  函数慢有用，常开=日志洪水，按需开关。
- 与 pg_stat_statements 同载时，auto_explain 输出带 Query ID——从"聚合异常"一键跳到"当时计划"。

```sql
ALTER SYSTEM SET shared_preload_libraries =
  'auto_explain,pg_stat_statements';
ALTER SYSTEM SET auto_explain.sample_rate=0.01;      -- 生产安全
ALTER SYSTEM SET auto_explain.log_min_duration='2s';
```

- 陷阱：阈值/采样率调太敏感=每条慢查询都带完整计划，日志量爆炸；
  从宽起步逐周收紧，并给日志平台设保留期。

## 20. B-tree 页面布局与 dedup

- 页=8KB：头部+line pointers+元组；Lehman-Yao 变体带 high key；内部
  页存（键,子指针），叶子页存（键,TID）。
- fanout：~16B 键的内部页约 400-500 个子指针 → 十亿行树高约 4；上层
  常驻缓存，点查≈1-2 次真实页读——键越宽 fanout 越小、树越高。
- 分裂模式决定空间：单调键（serial/时间戳）最右分裂页填 ~90%；随机
  键（UUIDv4）中位分裂长期只 ~2/3 满，同行数索引大 30%+（81 节）。
- dedup（13+）：叶子重复键合并为 posting list，重复值多的索引省
  20-40% 空间；唯一索引不参与 dedup。
- fillfactor：默认 90；热更新表调 80-85 给 HOT 留位（38 节）；页分裂
  是 WAL 与写放大的放大器（31 节）。

```sql
-- 内部结构体检（contrib/pgstattuple）
SELECT * FROM pgstatindex('idx_orders_user');
-- avg_leaf_density ~90% 健康；显著偏低=膨胀，去 30 节
```

- 陷阱：宽键索引每层少一半指针——char(32) 存哈希当主键的表，点查
  比窄键多读整整一层页；键宽是设计期决策，不是"以后再优化"。

## 21. 复合索引列序决策

- 经验序：**等值列在前、排序列居中、范围列压轴**——范围列之后的列
  基本只剩过滤资格，享受不到有序性。
- (a,b) 服务 a-only；不服务 b-only——18 的 skip scan 让低基数首列的
  (a,b) 能给 b-only 兜底，但高频路径仍值得专用索引。
- 逐列方向：ORDER BY a ASC, b DESC 需要 (a ASC, b DESC)——混合排序
  用全升序索引躲不掉额外 Sort 节点（16 节）。
- 冗余裁剪：(a) 是 (a,b) 的前缀即冗余，删；写放大按索引个数计费
  （31 节），"以防万一"的索引是慢性毒药。
- 组合拳：热子集用 partial（23 节）、覆盖用 INCLUDE（22 节）；零扫描
  索引列进死刑候选名单，确认没有月报/年报在用再执行。

```sql
SELECT indexrelname, idx_scan, pg_size_pretty(
  pg_relation_size(indexrelid)) AS sz
FROM pg_stat_user_indexes ORDER BY idx_scan ASC, sz DESC;
```

- 陷阱：改列序别一把梭——先 CONCURRENTLY 建新序、观察一周计划流量、
  再 CONCURRENTLY 删旧的；两代并存期写放大翻倍要算进窗口。

## 22. INCLUDE 覆盖索引权衡

- INCLUDE 列只进叶子页：上层更瘦、不参与排序与唯一、不能被 WHERE
  使用——存在的意义只有 index-only scan 的"顺带带出"。
- 键长限额（8KB 页约 1/3，~2704B）只约束 key 列：超宽列只能走
  INCLUDE 进索引，但整条 leaf 元组仍受页大小硬约束。
- 唯一索引+INCLUDE=唯一性只看 key 列："唯一(email)、覆盖(created_at)"
  的正确表达；被带出的列改了也不断 HOT——是好事也是坑（下条）。
- 代价：叶子变大→页密度降→同行数更多页→树更高扫描贵；INCLUDE 列
  被 UPDATE 即打断 HOT（31 节）——读多写少的列才配 INCLUDE。
- index-only scan 还依赖 visibility map：表没 vacuum 干净照样回堆
  （89 节）——覆盖索引的收益与 vacuum 健康度强耦合。

```sql
CREATE UNIQUE INDEX ON users (email) INCLUDE (created_at);
-- 唯一性只看 email；email+created_at 查询免回表
```

- 陷阱："整行塞进索引"过犹不及——叶子膨胀+HOT 之死常把 index-only
  的那点收益吃光；用 EXPLAIN 的 Heap Fetches 数验证真实回表量。

## 23. Partial 索引设计模式

- 两大模板：队列热子集 `WHERE status='pending'`（热数据 100x 小索
  引、页缓存常驻）；软删除唯一 `UNIQUE ... WHERE deleted_at IS NULL`（88 节）。
- 匹配规则：规划器做"蕴含证明"——查询条件必须**蕴含**索引谓词，
  谓词里的表达式/操作符须 IMMUTABLE，写法要贴合查询形状。
- 形状漂移=静默失效：应用把 `status='pending'` 改成 `status IN
  ('pending','retry')` 或枚举改值，索引还在、不再命中，**没有任何
  报错**——idx_scan 突降是唯一信号。
- ON CONFLICT 与部分唯一必须精确对齐：`ON CONFLICT (email) WHERE
  deleted_at IS NULL DO ...` 对应同名谓词的部分唯一索引（54 节）。
- 谓词窄一点，但要给状态机的过渡态留余地，否则状态演进=反复建删
  索引的 DDL 噪音。

```sql
CREATE INDEX ON jobs (created_at) WHERE status='pending';
CREATE UNIQUE INDEX ON users (email) WHERE deleted_at IS NULL;
```

- 陷阱：partial 救不了谓词外的查询——后台全量报表仍走全表扫描，
  别把两个场景的预期混在一份数据上。

## 24. 表达式索引与 IMMUTABLE 陷阱

- 规划器按**表达式树**匹配：`WHERE lower(email)=...` 命中建立在
  `(lower(email))` 的索引；两边必须逐字同形——`email=lower($1)` 无效。
- 函数必须 IMMUTABLE：`timestamptz::date` 是 STABLE（依赖会话时区）
  建索引直接报错；正解是 `((created_at AT TIME ZONE 'UTC')::date)`
  固定时区再转 date（79 节）。
- 红利：表达式索引顺带给规划器补上该表达式的统计——否则表达式上的
  选择率只能用默认值，这是 12 节验尸最常见的出口之一。
- 自定义 IMMUTABLE 函数的地雷：函数行为变更后索引仍按旧结果存着，
  PG 不会提醒——改完函数必须手动 REINDEX 重建所有相关索引。
- citext 与 lower(email) 是两条路：citext 等值走自身 opclass，索引
  形状不同，选定一条别混用（80 节）。

```sql
CREATE INDEX ON users ((lower(email)));   -- 命中 lower(email)=?
CREATE INDEX ON events (((ts AT TIME ZONE 'UTC')::date));
```

- 陷阱：表达式索引是 dump/迁移最脆的资产——表达式里函数版本一变，
  恢复后语义漂移；跨版本演练清单必须包含表达式索引核对。

## 25. 排他约束与 btree_gist

- 唯一约束表达不了"区间不重叠"：预订/排班/计价区间用 EXCLUDE——
  `EXCLUDE USING gist (room_id WITH =, during WITH &&)`。
- 标量等值进 EXCLUDE 要 btree_gist 扩展（给 int/text 提供 GiST
  等值算子）；纯范围排他不需要它，混合条件必须装。
- 并发冲突语义：两个事务同时插入重叠区间，一个成功、另一个 23P01
  （exclusion_violation）——按唯一约束同款协议重试/报错（41 节）。
- 插入成本高于唯一 btree：GiST 插入要算 penalty/做分裂，热表每写
  一次都在付；索引体积也更大——换的是约束表达力。
- 存量表加排他约束=全表验证持锁：先自 join 清历史重叠，再挑窗口加，锁窗口压到分钟。

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE bookings ADD CONSTRAINT no_overlap
  EXCLUDE USING gist (room_id WITH =, during WITH &&);
-- 历史重叠体检：自 join during && during AND id < id
```

- 陷阱：把 EXCLUDE 当性能索引用是错位——它首先是**约束**；查询要
  走 GiST 还得让谓词形状与算子对齐（27 节近邻一节另述）。
## 26. GIN 代价模型与 fastupdate

- GIN 写入本质：一行展开 N 个键=N 次主树插入；fastupdate 先攒
  pending list（线性追加）换写吞吐——**每次查询都多扫一遍 pending**。
- gin_pending_list_limit 默认 4MB（可 per-index 覆盖）；flush 时机：
  超限后的下次插入、VACUUM/ANALYZE 顺带、手工 gin_clean_pending_list()
  ——集中还债即写延迟尖刺的来源。
- jsonb_ops vs jsonb_path_ops：path_ops 只支持 @> 但键存哈希路径链，
  体积小 2-3 倍、更新更便宜——纯包含语义（@> 查询）就选它。
- vacuum GIN 很贵（flush+posting tree 平衡一次做）；写多读少的 ETL
  中间表可 fastupdate=off：每行即时入树、读路径恒定。
- pending 常态堆积的信号：GIN 节点 buffers 缓慢爬升而结果集不变。

```sql
CREATE INDEX ON events USING gin (payload jsonb_path_ops);
SELECT gin_clean_pending_list('events_payload_idx');
-- 高频写热表：ALTER INDEX ... SET (fastupdate=off)
```

- 陷阱：pending 清零并非永远正确——ETL 分区攒批总吞吐更高；先分清
  在优化读路径还是写路径，再动 limit 与 fastupdate 两个参数。

## 27. GiST 与 KNN 近邻

- KNN 形态：`ORDER BY loc <-> $1 LIMIT 10` 让 GiST 按距离序吐行（免
  Sort 免全扫）；前提是**索引列 <-> 常量**且 opclass 支持排序算子。
- 右边非常量即失效：`<->` 撞上 join 列/子查询，索引序完全用不上，
  退化为全扫+Sort；正解是 LATERAL 把外层值当常量逐行注入（53 节）。
- pg_trgm 双引擎分工：GIN 适合 %/相似度过滤，GiST 额外支持 KNN——
  `ORDER BY word <-> 'missisipi'` 直接按相似度排序出最佳匹配。
- 原生 point 的 <-> 是平面欧氏距离：地理"5km 内"必须换 PostGIS
  geography（球/椭球），拿 point 硬算经纬是系统性错误。
- penalty 决定分裂质量：GiST 不保证平衡，乱插会碎片化；批量建索引
  用 buffering=on 抑制随机 I/O。

```sql
SELECT u.id, s.name, s.d FROM users u
CROSS JOIN LATERAL (SELECT name, loc <-> u.loc AS d FROM stores
  ORDER BY loc <-> u.loc LIMIT 3) s;   -- opclass: geography/gist_trgm
```

- 陷阱：GiST 无 dedup、膨胀只能 REINDEX（30 节）；热表 GiST 的体积
  与重建窗口要进容量规划，别当免维护索引用。

## 28. BRIN 与物理相关性

- 结构：每 pages_per_range（默认 128 页）存一对 min/max，十亿行表的
  索引仅几 MB；代价是只能"排除粗块"，命中 range 整段回堆过滤。
- 它押注物理局部性：建前先查 pg_stats.correlation——接近 ±1 才配
  BRIN；接近 0 的列建了等于随身带一个无效滤网。
- 主场=append-only 时间/自增列：按时间窗查询天然只碰相邻 range；
  表已按时间分区（85 节）时 BRIN 常可整个退场。
- 乱序 UPDATE 把 min/max 拉宽成"长条"→range 覆盖失效；定期 REINDEX
  或调小 pages_per_range 换精度（索引变大但仍远小于 btree）。
- 14+ 新 opclass：brin_minmax_multi（离散区间如 IP 段）与 brin_bloom
  （等值、低相关列的**概率命中**+Recheck）——适用面扩到非相关列。

```sql
CREATE INDEX ON readings USING brin (ts)
  WITH (pages_per_range=64);   -- 精度×2，体积仍微小
-- 前提体检：pg_stats.correlation 接近 ±1 才值得建
```

- 陷阱：BRIN 的便宜只在索引本身——回堆量随统计漂移忽大忽小，同一
  查询忽快忽慢；监控 correlation 与数据分布的联动态势。

## 29. Hash 索引与唯一约束

- 历史包袱：10 之前 hash 不写 WAL（崩溃即坏、不复制），老资料"别用
  hash"的告诫半数过时——10+ 起已是 WAL 完整日志的一等公民。
- 能力边界是结构性的：仅等值、仅单列、无唯一约束、无 INCLUDE、
  不支持排序——选它=接受整套取舍，不是待补的 TODO。
- 真正主场是**长随机键等值**：btree 键宽直接吃页空间（键元组上限
  ~2704B），hash 只存定长散列——长 token/URL/text 等值体积小一截。
- 同表混用是常态：范围列 btree + 等值长键 hash 各取所长；要唯一性
  老实回 btree/唯一约束。
- 碰撞处理是页内探查：负载上升性能平滑下降，无 btree 的分裂尖刺；
  索引不收缩，回收靠 REINDEX。

```sql
CREATE INDEX ON sessions USING hash (session_token);
-- 只服务 WHERE session_token=$1；范围/排序/唯一都别指望
```

- 陷阱：规划器对 hash 的代价估算偏保守，常被 btree 抢跑——上生产
  前用等值负载 A/B 一次 EXPLAIN ANALYZE，别信"hash 一定更快"。

## 30. 索引膨胀诊断与在线重建

- 双指标判读（pgstatindex）：avg_leaf_density（默认 fillfactor 90 →
  健康 ~90%，<60% 判膨胀）与 leaf_fragmentation（逻辑/物理序失配）。
- 表侧同诊（pgstattuple）：dead_tuple_percent/free_percent——索引
  膨胀常是**表侧 vacuum 失败的症状**（89 节），只修索引=治标。
- REINDEX CONCURRENTLY（12+）ShareUpdateExclusive 不挡 DML；失败/
  中断留下 *_ccnew 的 INVALID 尾巴，必须手工 DROP，否则写放大照付。
- CREATE INDEX CONCURRENTLY 同款残留；迁移守卫必跑 pg_index 的
  `NOT indisvalid` 查询，CI 挂红灯。
- 锁矩阵：VACUUM FULL/CLUSTER=ACCESS EXCLUSIVE 全程封表；pg_repack=
  触发器日志+全量重建（要 PK/唯一索引、双倍磁盘、交换期短锁）。

```sql
CREATE EXTENSION IF NOT EXISTS pgstattuple;
SELECT * FROM pgstatindex('idx_orders_status');
SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;
```

- 陷阱：重建两周又胖回去=根因在 autovacuum/长事务（55 节）；膨胀是
  结果不是病因，重建永远是流程的最后一步。

## 31. 索引写放大数学

- 记账法：非 HOT 的 UPDATE 在**每个**索引里各插一条新项——10 个索引
  的表每次更新付 11 份页写；这不是可调参数，是结构账单。
- HOT 两前提：未改任何被索引列 + 新版本挤进同页（fillfactor 留缝，
  20 节）；健康度看 n_tup_hot_upd/n_tup_upd——热表 <80% 就查因。
- "以防万一"的索引是复利负债：按写频×页数计费；批量导入先 DROP 再
  重建（排序构建更快更紧凑），带 6 个索引 COPY 亿行全程慢数倍。
- 备库连坐：回放按进程串行，主库每份索引写放大在备库**重演**——
  写放大直接兑换成复制延迟（58 节）。
- 归因工具：pg_stat_statements 的 wal_bytes（13+）按语句拆写放大账
  （56 节），"这接口改一行发 40KB WAL"就是这么抓的。

```sql
SELECT n_tup_upd, n_tup_hot_upd,
  round(100.0*n_tup_hot_upd/nullif(n_tup_upd,0),1) AS hot_pct
FROM pg_stat_user_tables WHERE relname='orders';
```

- 陷阱：热表加 INCLUDE 覆盖列（22 节）会亲手杀死 HOT——覆盖收益要
  乘上"每次更新多付 N 份索引写"再与查询节省对比。

## 32. ANALYZE 采样内幕

- 采样量=300×statistics_target（默认 100→3 万行），**与表大小无关**；
  倾斜/列相关数据违反外推假设时就是 12 节的验尸现场。
- 产出三件套：MCV（高频值精确点名）、histogram_bounds（target 档
  边界）、correlation（物理序 vs 逻辑序，BRIN/索引扫描代价的输入）。
- autoanalyze 触发：n_mod_since_analyze > 0.1×n_live_tup+50（默认
  0.1/50）；13+ 起插入主导的表另有插入触发，否则新表裸奔到首轮。
- 大 COPY/批量回填后**手动 ANALYZE**：reltuples 老化让 join 规划
  整体失真，别赌 autoanalyze 的滞后窗口。
- per-column 深挖：`ALTER COLUMN ... SET STATISTICS 500`（上限 10000）
  ——倾斜列单独加深，别全局抬 target（采样成本每列都付）。

```sql
ANALYZE VERBOSE big_table;          -- 300×100=3 万行样本
ALTER TABLE orders ALTER COLUMN status SET STATISTICS 500;
```

- 陷阱：CLUSTER/批量重排后 correlation 不自动刷新——拿旧值调 BRIN
  的 pages_per_range（28 节）会系统性误判，重排后必须再 ANALYZE。

## 33. n_distinct 调优

- 语义双轨（pg_stats 实测）：正数=绝对 distinct 数（3.0 就是 3 个）；
  负数=-(distinct/rows) 比例——全唯一列显示 -1.0，估算=rows×|v|。
- 负值形态的含义："distinct 会随表增长"——规划器据此外推；正值则
  是采样内可精确点数的"已知常数"。
- 采样对 distinct 天生悲观：3 万样本撞亿级基数必然低估——低估让
  GROUP BY 行数算小 → hash 聚合内存给少 → 溢出（51 节）。
- 高估同样致命：组数估大 → 放弃 hash agg 改 sort+group、窗口内存
  预算失真——两个方向的计划都会塌。
- 修正：`ALTER COLUMN ... SET (n_distinct=5000)`（绝对）或 `=-0.01`
  （1% 比例），下次 ANALYZE 起覆盖采样值——先真数一遍再写。

```sql
SELECT attname, n_distinct FROM pg_stats WHERE tablename='events';
SELECT count(DISTINCT user_id) FROM events;  -- 基准真值
```

- 陷阱：n_distinct 是全局覆盖——分区表（85 节）每分区各有统计，
  父表口径的硬编码会误导子表规划，只在确认不分区的表上设。

## 34. 扩展统计与列相关性

- 默认假设"列独立"是规划器最大的谎言：`state='CA' AND city='Berkeley'`
  按独立相乘把行数估低一个量级——city→state 的依赖只有扩展统计可见。
- 三口径各管一段：dependencies 只帮**等值**选择率；ndistinct 管
  GROUP BY/多列 join 组数；MCV（14+）抓组合倾斜。
- 实测 pg_stats_ext 形态：dependencies 出 `{"1 => 2": 1.0}`（1 号列
  完全决定 2 号列）、n_distinct 出组合组数——数字不贴预期=重建。
- ndistinct 是组合真值（(user_id,day) 实际 8 千万组）——修正 33 节
  采样低估的正道，跨列 join 行数估算也吃这口。
- 维度越多需要的样本越大——8 列以上的组合不如改数据模型（105 节）。

```sql
CREATE STATISTICS s_loc (dependencies, ndistinct, mcv)
  ON city, state FROM users;
ANALYZE users;  -- 不跑 ANALYZE=空统计；验收看 pg_stats_ext
```

- 陷阱：dependencies 只认"等值 AND 等值"——范围谓词、OR、不等式
  一律不触发；指望它救范围查询只会继续验尸（12 节）。

## 35. 计划锁定与 pg_hint_plan 纪律

- hint 定位是**最后手段**：统计/写法修复（32-34、37、100 节）穷尽后
  再上——它钉死今天的计划、挡住明天的数据演化，还会无声失效。
- pg_hint_plan：`/*+ Index(o idx_x) HashJoin(a b) Leading(a) */`；与大
  版本强耦合、托管云（RDS/Aurora/AlloyDB）普遍不带——先过云这一关。
- 可移植的"软锁定"三件：MATERIALIZED 围栏钉 join 顺序（37 节）、
  `OFFSET 0` 阻断谓词下推、统计修复让规划器自愿选对——都进版本控制。
- enable_ 族开关只做诊断：`SET enable_nestloop=off` 在会话内证明
  "哪个节点惹的祸"，跑完就撤，别常驻应用连接。
- MySQL 对照：FORCE INDEX/USE INDEX 内建于方言、optimizer_switch 是
  半全局开关——跨库团队别把 MySQL hint 习惯带进 PG（111 节）。

```sql
/*+ IndexScan(o idx_orders_status_created) */
EXPLAIN SELECT * FROM orders o WHERE status='pending';
-- 修完统计就删 hint；CI 对核心查询做无 hint 计划回归
```

- 陷阱：hint 必须配计划回归测试——auto_explain 留痕（19 节）+查询集
  归一化 diff，让 hint 失效至少在合入前发出响声。

## 36. generic 与 custom plan 陷阱

- 切换律（16.2 实测）：前 5 次执行走 custom（按真实参数查直方图），
  第 6 次起若 generic 代价不劣于五次均值即切换——此后对参数失明。
- 倾斜实测：10 万行 90% 'done'——custom 选 Seq Scan（正确），第 6
  次起 generic 按"平均选择率"改走 Bitmap Index Scan，热点参数变慢。
- partial 索引在 generic 下不可用；实测 auto 模式常因 generic 代价
  更高**永不切换**（正确行为），被 force_generic_plan 强制才是灾难。
- 17+：`EXPLAIN (GENERIC_PLAN) EXECUTE` 免真跑 5 次；止血带是
  plan_cache_mode=force_custom_plan（代价是每次重规划的 CPU）。
- 驱动层叠加：JDBC prepareThreshold=5、psycopg3 prepare_threshold=5
  ——客户端第 5 次起发命名语句，与服务端切换律前后脚咬人。

```sql
PREPARE q(text) AS SELECT * FROM jobs WHERE status=$1;
EXPLAIN (GENERIC_PLAN) EXECUTE q('done');  -- 看失明版计划
SET plan_cache_mode = force_custom_plan;   -- 倾斜参数止血带
```

- 陷阱：压测只跑 3 次的全在测 custom plan——上线脚本必须让热点语句
  各执行 10+ 次，把"第 6 次"纳入观测窗；ORM 默认参数就埋在这里。

## 37. CTE 内联与 MATERIALIZED 围栏

- 12 起默认内联：单引用、非递归、无副作用的 CTE 被当子查询拉平——
  谓词下推得进、统计穿得透；"CTE=优化栅栏"的 11 时代默认已成历史。
- 显式双向开关：`MATERIALIZED` 物化一次供多引用共享；`NOT
  MATERIALIZED` 强制内联——多引用会被**每个引用各算一遍**，重付 N 次。
- 围栏的工程用途：钉住 join 顺序、防谓词下推打乱分批逻辑、隔离易变
  函数、把一次性大结果算好供多段消费（35 节软锁定主力）。
- 带 DML 的 CTE（WITH ... INSERT ... RETURNING）永远物化；RETURNING
  只在同语句内可见——跨语句消费要么 CTE 链要么应用层。
- 内联 CTE 的统计仍按子查询独立估，复杂形状失真层层放大（12 节）；
  必要时物化落临时表+ANALYZE 比祈祷规划器可靠。

```sql
WITH top AS MATERIALIZED (
  SELECT user_id, sum(v) AS s FROM events GROUP BY 1)
SELECT * FROM top WHERE s > 100;  -- 全量聚合先算，谓词不进
```

- 陷阱：11→12+ 升级（6 节）计划静默变脸——原靠物化压住的查询突然
  内联；升级清单必须跑核心查询计划 diff（19 节）。

## 38. MVCC 版本链解剖

- 元组头三件套：xmin（插入事务）、xmax（删除/更新事务）、t_ctid
  （链上下一版本）；UPDATE=插入新版本+旧版本挂 xmax，就地追加。
- hint bits（infomask）：xmin/xmax 的"已提交"位是**顺手才写**的缓存
  ——提交后第一个读到它的人负责回写，即"提交后首个查询有写放大"。
- HOT 机制位：新版本同页+未动被索引列 → 索引仍指旧锚点、沿链跳；
  vacuum 与访问期 prune 收链——锚点死透索引项才能删。
- 链读成本：索引命中锚点后沿 t_ctid 找可见版本——同行高频更新的表
  点查逐渐变慢是膨胀前奏；页满则 HOT 断供、全索引插新项（31 节）。
- xmin horizon 顶住回收：活跃快照看得见的版本都不能删——长事务/
  复制槽把下限钉死（55/58 节），死元组开始堆山。

```sql
SELECT lp, t_xmin, t_xmax, t_ctid   -- pageinspect 剖物理真相
  FROM heap_page_items(get_raw_page('orders', 0));
-- t_ctid 自指 + xmin 极老 = 待回收的死元组
```

- 陷阱：pageinspect 看的是物理现场——把"待回收死元组扫描"固化进
  巡检，比事后 pgstattuple 汇总更能抓住 horizon 卡住的时刻。

## 39. 快照可见性规则

- 快照=三元组：xmin（最老活跃事务）、xmax（下一未分配 xid）、xip
  （进行中清单）；可见判据=xmin 已提交且早于快照且不在 xip。
- RC 逐语句新快照（事务内能看见别人的新提交）；RR 在**首条语句**
  取快照后全程固化——PG 的 RR 本质是快照隔离（40 节）。
- 16.2 实测：BEGIN 后别人插入并提交，自己的**第一条** SELECT 仍能
  看到——快照锚在首条语句不在 BEGIN；此后才冻结。
- now() 事务开始即恒定、clock_timestamp() 实时走：审计/时延推导用
  now() 在长事务里"提前"；逐语句边界再叠 statement_timestamp()。
- 判可见性要查 clog（提交日志），hint bits（38 节）是这条路径的旁路
  缓存；pg_export_snapshot 让并行 worker 共享一致视图（pg_dump -j）。

```sql
BEGIN ISOLATION LEVEL repeatable read;
SELECT count(*) FROM t;   -- 此刻取快照，不是 BEGIN 时！
SELECT txid_current_if_assigned();  -- 只读事务可能拿不到 xid
```

- 陷阱：把"BEGIN 即快照"写进心智模型解释不了观测——RR 首查前提交
  的数据看得见、首查后才提交的看不见，边界必须钉在首条语句。

## 40. 隔离级别异常矩阵与 SSI

- PG 实况矩阵：脏读任何级别不存在（READ UNCOMMITTED=RC 别名）；RC
  有不可重复读+幻读；RR 消掉两者但**写偏斜仍在**；只有 SSI 兜底。
- 写偏斜实测：两人都读"在岗≥1"、各改不同的行、都提交成功——RR 下
  零报错、不变量破；同脚本 SERIALIZABLE 下第二提交即 40001。
- SSI 原理：SIREAD 谓词锁记录"读过什么"（页/range 粒度会**误伤
  放大**），提交检查读写冲突环；拿 abort 率换正确性，事务要短。
- 修复武器分档：同行冲突→FOR UPDATE（43 节）；计数竞态→哨兵行或
  advisory（44 节）；约束可表达→EXCLUDE（25 节）；最后才 SERIALIZABLE。
- 队列竞态案例：读 count 后按余量插入——RR 下两事务同看余量=超卖；
  一行 FOR UPDATE 哨兵通常比 SSI 更便宜、abort 更少。

```sql
-- 写偏斜最小复现（RR 下 A、B 双双成功 → 无人在岗，零报错）
SELECT count(*) FROM oncall WHERE active;  -- 都读到 1
UPDATE oncall SET active=false WHERE name='A';  -- B 同款两步
```

- 陷阱：SERIALIZABLE 的 SIReadLock 在 pg_locks 堆积、大范围扫描升级
  粒度；abort 率 >1% 说明业务模式与 SSI 犯冲——改锁哨兵，别硬扛。

## 41. serialization failure 重试协议

- 可重试码：40001（serialization_failure）/40P01（deadlock_detected）
  ——服务器已**整体回滚**，重试=从 BEGIN 重放全部语句而非末句。
- 封装单点：重试循环只写在数据访问层同一处（executeTxn(fn)），业务
  函数保持纯事务体；散落的 try-catch 重试是重复提交事故温床。
- 退避：指数+抖动（50ms→100→200…封顶 1-2s）+重试预算（3-5 次）+
  熔断；高冲突下无脑重试 100 次等于把热点放大成风暴。
- 副作用纪律：外呼（邮件/扣款/MQ）要么提交后执行、要么带幂等键——
  重试对外部世界必须"看起来只发生一次"。
- 连接类 08xxx 与 40001 不同路：先重建连接再重放；57014 语句超时
  先查慢因再重试——别把慢查询重试成雪崩。

```sql
BEGIN; SAVEPOINT sp;  -- 客户端唯一封装点的服务端影子
-- run body → COMMIT；except 40001/40P01 → ROLLBACK TO sp
--   sleep(backoff+jitter) → 重放整个事务，预算耗尽上抛
```

- 陷阱：重试写在同一个连接对象上——池化归还后事务状态早已蒸发；
  每次重试必须从干净 BEGIN 开始，aborted 态里续跑只会 25P02。

## 42. 锁队列与 lock ordering

- 队列头放大：ACCESS EXCLUSIVE 到队首后，后续一切请求（含普通
  SELECT）都排在后面——ALTER 等 3 小时大查询=全站读熔断 3 小时。
- 铁律：DDL 一律 `SET lock_timeout='2s'`+重试循环——抢不到立刻让位
  重排，别占着队首按住全库；重试间隙查 pg_blocking_pids 找堵源。
- deadlock_timeout=1s 的语义：死锁检测器**延迟 1s 才扫描**（防误判），
  不是"锁 1 秒超时"；log_lock_waits=on 时超时即记 blocked 日志。
- 应用侧 lock ordering：固定行序更新（按主键排序后依次 UPDATE）、
  加锁路径唯一化——死锁率是纪律问题；40P01 重试只兜底不日常。
- 现场：pg_locks 关联 pg_stat_activity，pg_blocking_pids(pid) 一发
  入魂；"谁堵谁"查询直接固化进 runbook（108 节）。

```sql
SET lock_timeout='2s';
ALTER TABLE orders ADD COLUMN memo text;  -- 抢不到立刻失败重排
-- 取证：SELECT pid, pg_blocking_pids(pid) FROM pg_stat_activity
```

- 陷阱：lock_timeout 罩不住"拿到锁但跑不完"——ALTER 秒改元数据，
  长的是后面的 VALIDATE/回填/重建（45/46 节）；超时要分档设。

## 43. 行锁变体与外键死锁

- 四级家族（强度递减）：FOR UPDATE > FOR NO KEY UPDATE > FOR SHARE >
  FOR KEY SHARE；普通字段更新拿 NO KEY UPDATE 即可——不挡 FK。
- FK 隐藏行锁（16.2 实测）：子表写外键列对父行取 **KEY SHARE**；父行
  NO KEY UPDATE 下插入成功、FOR UPDATE 下阻塞——偶发死锁多在此。
- 经典死锁形：TX A 按序 FOR UPDATE 父行 1、2；TX B 插子表引用父行
  2、1——环形等待即成；修法=行序统一，或父侧降级 FOR NO KEY UPDATE。
- 失败语句的锁去留：报错后事务进 aborted 态，已有锁**保持到
  ROLLBACK**；ROLLBACK TO SAVEPOINT 只释放失败子事务新拿的锁
  （表/行/advisory 实测都放），此前拿的原样保留。
- 行锁不进 lock table：元组标记位+冲突时 MultiXact，pg_locks 看不到。

```sql
SELECT * FROM accounts WHERE id=$1
  FOR NO KEY UPDATE;  -- 实测：FK 子表插入不阻塞
UPDATE accounts SET ...; COMMIT;
```

- 陷阱："FOR UPDATE 防并发改"当默认键会把 FK 热父表串行化成瓶颈；
  按"改不改键"选强度是行锁体系的第一决策点（每秒级的差距）。

## 44. advisory locks 模式

- 两个命名空间：`pg_advisory_lock(bigint)` 与 `(int,int)` 双 int——
  多模块分位编码互不冲突；hashtext() 是惯用的字符串→key 折叠。
- 两种时长：会话级（session 结束才放）与 xact 级（**提交/回滚自动
  放**）——默认永远选 xact 级：没有孤儿、没有"忘了解锁"。
- try 变体：pg_try_advisory_xact_lock() 失败立即返回 false 不排队
  ——"抢不到就跳过"的任务分发/限流/去重用它，别用排队版串行长队。
- 与池化的死结：会话级锁在 transaction 池化（47 节）下语义破碎——
  锁落在 A 连接、下次业务在 B 连接；要么 session 池化要么改 xact 级。
- 它不锁任何数据：advisory 是**纯协作**协议——没拿锁的事务照样读写
  同一批行；强制互斥该用唯一/排他约束（25 节），advisory 只做协调。

```sql
SELECT pg_try_advisory_xact_lock(hashtext('migrate:'||$1));
-- 迁移互斥（46 节）：抢不到=有实例在跑，退出而非等待
```

- 陷阱：xact 锁贴着事务边界走——savepoint 回滚会释放子事务里拿的
  锁（实测），嵌套重试框架里锁的生命周期要按 savepoint 边界推演。

## 45. 零停机迁移：expand-migrate-contract

- 节奏三段：expand（加可空列/新结构）→ migrate（双写+分批回填+切
  读）→ contract（收 NOT NULL、删旧列）；每段独立发布独立回滚。
- 11+ 加带默认值列只改元数据（瞬间完成不重写）；volatile 默认
  （now()）才触发重写——默认值的选择决定锁窗口。
- 回填铁律：按主键区间分批 UPDATE+COMMIT，批间盯复制延迟（58 节）
  与死元组率（89 节）——一口气回填亿行，停的是整库不是迁移。
- NOT VALID+VALIDATE（16.2 实测并发写不受影响）：前者短暂锁不扫
  表；后者不挡写慢慢校验——窗口期存量数据责任在业务侧。
- 12+ 的 NOT NULL 捷径：`CHECK (col IS NOT NULL) NOT VALID` → VALIDATE
  → `SET NOT NULL`，借已验证 CHECK 免全表扫——零锁台阶。

```sql
ALTER TABLE orders ADD CONSTRAINT fk_user
  FOREIGN KEY (user_id) REFERENCES users(id) NOT VALID;
ALTER TABLE orders VALIDATE CONSTRAINT fk_user;  -- 不挡写
```

- 陷阱：contract 阶段删列至少隔一个发布周期——回滚窗口期旧代码
  仍在读写；删索引走 CONCURRENTLY（30 节），收缩当 expand 对待。

## 46. 幂等迁移与 DDL 并发纪律

- PG DDL 默认可事务，例外名单要背：CREATE/DROP INDEX CONCURRENTLY、
  REINDEX CONCURRENTLY、CREATE DATABASE、ALTER SYSTEM——进不了事务块。
- CIC 半尸：失败/超时/取消留下 *_ccnew 的 INVALID；之后 IF NOT
  EXISTS 看同名**静默跳过**——你以为建好了，规划器永远看不见。
- 迁移互斥：多流水线并发部署用 advisory 锁当 mutex（44 节）——
  抢不到就失败退出而非排队；两个迁移对同一表抢锁=42 节放大现场。
- 幂等写法分层：结构用 IF NOT EXISTS/IF EXISTS；约束别用它（遮蔽
  INVALID 状态），改 DO 块查 pg_constraint/pg_index 后显式建。
- 非事务模式跑法：Flyway/Liquibase 关事务包裹（或 psql -v
  ON_ERROR_STOP=1），每条 DDL 自负盈亏+失败后可重放补齐。

```sql
-- 迁移收尾守卫（幂等可重放）
SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;
SELECT conname FROM pg_constraint WHERE NOT convalidated;  -- 45 节
```

- 陷阱：全局 statement_timeout 会砍长 VALIDATE/回填——迁移会话单独
  SET statement_timeout=0 + lock_timeout 兜底（42 节），超时必须分级。

## 47. 连接池模式与 pgbouncer 陷阱

- 成本模型：每个 PG 连接=一个 backend 进程（fork+数 MB 常驻+调度
  开销）——max_connections 拉到几千是卖掉内存与延迟，池化是前提。
- 三态：session（全程独占、兼容 100%、收益最低）、transaction（按
  事务归还、吞吐主角）、statement（按语句归还、autocommit 专属）。
- transaction 模式四杀：会话 GUC 泄漏、临时表跨事务消失、LISTEN/
  NOTIFY 断线、WITH HOLD 游标解绑——一切"会话级状态"都与它犯冲。
- server_reset_query（DISCARD ALL）**只在 session 模式**归还时跑；
  transaction 模式不重置——隔离只靠事务边界，别指望隐式 DISCARD。
- 观测在 bouncer 侧：SHOW POOLS 的 cl_waiting=客户端在排队；PG 侧
  pg_stat_activity 只见 server 连接数，别当客户端并发读。

```sql
-- 连 pgbouncer 的 admin 库（专用命令面）
SHOW POOLS;   -- cl_waiting>0：池太小或后端堵
SHOW STATS;   -- avg_req/avg_query 用来定容
```

- 陷阱：transaction 模式下"事务外的 SET 偶尔好使"是因为恰好复用
  同一连接——同事务内的 SET 才安全；评审只认"SET 在 BEGIN 之后"。

## 48. 事务池化与 prepared statements 兼容

- 兼容断代史：1.21 起 max_prepared_statements 跟踪协议级命名语句、
  按需在 server 补 prepare；**1.24 起默认 200**，1.21-1.23 默认 0。
- 只认扩展协议：JDBC/psycopg3/libpq 的 Parse/Bind 命名语句可跟踪；
  SQL 文本的 PREPARE/EXECUTE 是会话状态，transaction 模式仍不支持。
- 超限 LRU 淘汰最老映射（server 侧 DEALLOCATE），再用时透明重
  prepare——偶发首查抖动；参数别设玩具值，并发语句名覆盖会炸。
- 驱动参数对齐：JDBC prepareThreshold=5、psycopg3 prepare_threshold=5
  ——叠加 36 节的 6 次切换律，压测跑满几十次才见真计划。
- 与 pg_stat_statements 联动照常按 queryid 聚合（56 节），但排查时
  要把 bouncer 日志与 PG 日志按时间线对齐。

```sql
SELECT name, from_sql FROM pg_prepared_statements;
-- from_sql=true 的是 SQL PREPARE（transaction 池化不支持）
-- 事务内命名语句安全；跨事务由 pgbouncer 跟踪重放
```

- 陷阱：升级 pgbouncer 1.20→1.24"莫名变快"是默认开启的礼——但
  max_prepared×pool_size 决定 server 侧 plan cache 规模，容量账要重算。

## 49. 三种池化模式选择

- 功能矩阵定选型：临时表/LISTEN/会话 GUC/会话 advisory 依赖 → 只剩
  session；无状态 OLTP → transaction；statement 只配 autocommit 微事务。
- 强制场景：serverless/边缘一律 transaction——Supabase 6543、RDS
  Proxy 默认、Neon 池化端点都替你做了决定，别逆着来。
- Little 定律定容：池 ≈ 吞吐×平均时延（5k TPS×4ms→20 条）+冗量；
  最优并发≈核心数×2-4，几百连接是纯损耗不是富裕。
- 产品光谱：pgbouncer（C、单线程数万连接、事实标准）；pgpool-II
  （复用+读写分离+watchdog，坑也多）；RDS Proxy/Supavisor（托管）。
- 双层池的账：Hikari/SQLAlchemy 之上再架 pgbouncer 时，上游总连接
  ≤ pool_size×实例数——上游池更大会把 cl_waiting 变成常态（47 节）。

```sql
-- 定容速算：5000 QPS × 平均 4ms → 池 20 + 突发冗量
-- 运行态验证（pgbouncer admin）：
SHOW POOLS;  -- cl_waiting 持续 >0 → 加池或查慢事务
```

- 陷阱：把 session 当"兼容性避风港"长期用——池化收益吃光还会制造
  连接风暴（95 节）；正道是清掉会话态依赖迁 transaction。

## 50. cache hit ratio 神话

- 全局命中率可被灌水：pg_stat_database 的 blks_hit 混着"全表扫但常驻
  缓存"的页——99% 命中与慢查询并存是常态；它回答不了"谁在等 IO"。
- shared miss ≠ 磁盘读：读出 shared_buffers 仍可能命中 OS page cache
  （毫秒级）——track_io_timing 之前"read"被一律当磁盘读高估痛点。
- 16+ 的 pg_stat_io 才是分账主账本：按 context 拆 hits/reads/
  evictions/writes+read_time——working set 是否常驻看 evictions。
- 语句级归因才可执行：pg_stat_statements 的 blk_read_time 按 queryid
  拆（56 节）——优化谁、省多少毫秒一目了然。
- 用法纪律：比率当**趋势基线**（环比跳变=新查询/新分布进来了），
  绝对值不当 KPI；告警设在突变，不设在"低于 95%"。

```sql
SELECT context, reads, read_time, hits, evictions, writes
  FROM pg_stat_io WHERE backend_type='client backend';
-- 语句级：SELECT query, blk_read_time FROM pg_stat_statements ...
```

- 陷阱：track_io_timing=on 有可测开销（17 起显著降低）——预发压测
  后再常开；比率报表与 pg_stat_statements.max 采样面一起管（56 节）。

## 51. work_mem 溢出诊断

- 乘法法则：work_mem 是**每个排序/哈希节点**的预算×并发查询×并行
  worker——全局 256MB、50 并发×3 节点的峰值是几十 GB，OOM 前没人算账。
- 溢出证据链：`Sort Method: external merge (Disk: N)`、`Hash Batches:
  >1`、17+ 的 temp read/written 列——任一出现即溢出，数字=损失规模。
- 分层赋值：全局保守（4-8MB）→ 重角色 ALTER ROLE SET → 会话内 SET
  LOCAL work_mem='512MB'（自动还原）——大预算只给那一条夜报表。
- temp_file_limit 按角色兜底：失控查询在写穿磁盘前被杀（53400）；
  无界 temp 文件+磁盘满=实例瘫痪（96 节）。
- bitmap 也吃 work_mem：超了降级 lossy 页级位图（15 节）——大表
  位图扫"偶尔慢 10 倍"先查这里，别急着换索引。

```sql
BEGIN; SET LOCAL work_mem='512MB';
SELECT ... ORDER BY ...;              -- 只给这条大查询
COMMIT;                               -- 自动还原，无泄漏
```

- 陷阱：并行时每个 worker 各一份预算——Gather Merge 下 leader+2
  worker 的排序开销×3；EXPLAIN 里 N 个 Sort 节点就是 N 份预算。

## 52. N+1 在 SQL 层的形态

- 服务器侧画像：pg_stat_statements 里 calls 巨大 + mean_exec_time 低
  + rows≈1 是 N+1 标准形（56 节）；对照源语句 rows 还能分清是 ORM
  lazy-load 还是循环里手写查询。
- 批量化两路：`=ANY($1)` 数组一次带回（54 节 unnest 同理）；JOIN 后
  行扇出再 DISTINCT/窗口去重——把网络放大换成服务器排序。
- SUM 双计陷阱：父行 JOIN 子行后 `SUM(parent.amount)` 被子行数放大
  ——先子侧聚合再 join；评审见"join 后直接 SUM 父列"必须拦。
- 往返成本数学：1000 次 0.3ms RTT≈300ms 纯延迟 vs 一次 ANY 5ms——
  差两个数量级；池化只省连接建立，救不了往返。
- 预防性纪律：接口设计期问"循环里有没有隐式查询"；calls×rows 比值
  做成 CI 环比报表，N+1 回归在合入前显形（107 节）。

```sql
SELECT * FROM orders
 WHERE user_id = ANY($1::bigint[]);   -- 1000 次点查 → 1 次往返
```

- 陷阱：批量化后计划走 generic 路径（36 节）——数组长度分布倾斜时
  第 6 次起计划翻车；超大批量走 unnest+JOIN 并配统计更可控（54 节）。

## 53. LATERAL 与相关子查询

- 三种子查询形态：InitPlan（不相关，一次）、hashed SubPlan（不相关
  IN 转 hash）、SubPlan（相关，**每外行重算**）——成本模型完全不同。
- NOT IN 的毒点：严格比较语义撞上 NULL——子查询含一个 NULL 整体
  返回空集；NOT EXISTS 无此毒且常转反连接，团队规范应直接禁 NOT IN。
- LATERAL 是显式的"相关"：外层行值注入内层、可带 ORDER BY+LIMIT
  ——top-N per group 标准解：`(group, sort)` 索引下每组只扫 N 行。
- 规划器会自动 decorrelate 部分形状，但带 LIMIT/聚合/窗口的拉不平
  ——拉不平的相关子查询=伪装的 nested loop，成本照单全收。
- 计划阅读法：SubPlan 挂在哪个节点下、estimated 与 actual rows 差
  多少——相关子查询失真是 12 节验尸常客。

```sql
SELECT u.*, o.* FROM users u LEFT JOIN LATERAL (
  SELECT * FROM orders WHERE user_id=u.id
  ORDER BY created_at DESC LIMIT 3) o ON true;  -- top-N per group
```

- 陷阱：LATERAL 每外行执行一次内层——外层 10 万行时内层 1ms 也是
  100 秒；先限制外层基数（过滤/分区裁剪），再谈 LATERAL 的优雅。

## 54. 批量写入：COPY 与 unnest

- COPY 是 ingest 王道：绕过逐行解析/计划直灌表；`FREEZE`（须同事务
  内建表/TRUNCATE 后）连 hint bits 都省——数仓分区装载标配（38 节）。
- 16+ 的 ON_ERROR=ignore：坏行跳过并记录（LOG_VERBOSITY=verbose 看
  明细）——ETL 从"全量重跑"变"坏行审计"；静默丢行必须配行数对账。
- 多行 VALUES 的天花板：单条 INSERT 数千行仍比 COPY 慢数倍（解析+
  计划+逐行 WAL 记录），万行以上别犹豫直接 COPY。
- unnest 批绑定：`FROM unnest($1::int8[], $2::text[])` 多列对齐、
  一次往返一个计划；应用绑数组比拼 SQL 快且安全。
- 批量 upsert 叠 ON CONFLICT；谓词必须与部分唯一索引逐字对齐——
  实测缺谓词直接 42P10（23 节）。

```sql
INSERT INTO tags (user_id, label)
SELECT * FROM unnest($1::int8[], $2::text[])
ON CONFLICT (user_id, label) DO NOTHING;   -- 谓词对齐部分索引
```

- 陷阱：单条多行 INSERT 的错误粒度=整条失败——要"好行存活"就分批
  unnest 或 COPY ON_ERROR，别把一批拆成 N 条自动重试（52 节）。

## 55. 长事务与 idle in transaction

- xmin horizon 机制：最老活跃快照决定死元组回收下限——一个 2 小时
  事务拖住全库 vacuum，dead tuple 堆成膨胀（89 节），它自己毫无感觉。
- 探针：pg_stat_activity 的 backend_xmin=各 backend 的快照水位；
  全库 horizon=min(所有 backend_xmin, 复制槽 xmin)（58 节合并监控）。
- idle in transaction 是头号惯犯：BEGIN 后思考/外呼 HTTP 再 COMMIT
  ——快照与锁全捏着；think time、重试等待、事件回调全部移出事务。
- 服务端三闸：idle_in_transaction_session_timeout（9.6+）、
  transaction_timeout（17+，罩总时长）、statement_timeout——按角色灰度。
- 告警基线：`now()-xact_start` 最长事务 >1 分钟告警、>5 分钟 page；
  idle in transaction 数量做水位——比锁等待更早暴露问题。

```sql
SELECT pid, state, now()-xact_start AS xact_age,
  backend_xmin FROM pg_stat_activity
  WHERE xact_start IS NOT NULL ORDER BY xact_age DESC;  -- horizon 榜单
```

- 陷阱：被杀的 idle 事务把错误抛给应用——timeout 必须与重试协议
  （41 节）联动发布；只设 timeout 不改事务边界=每天制造随机 25P02。

## 56. pg_stat_statements 与性能基线

- queryid 机理：对解析树做 Jenkins hash——同形不同字面量归并；14+
  compute_query_id=auto（加载即算），auto_explain/pg_stat_activity/
  日志同 id 联动（19 节）。
- normalize 的分裂面：IN 列表长度、字面量类型、schema 限定与否都会
  裂出新 id；跨 major 版本 id 不稳，别当持久关联键。
- 盲区用 auto_explain 补：只有 mean/stddev/min/max 无分位数，p99 长
  尾配 log_min_duration 采样；calls×mean 榜淹没低频慢查询。
- 写放大归因：wal_bytes/wal_records（13+）按语句拆账——31 节"改一行
  发 40KB"从这列抓；temp_blks_written 对应溢出（51 节）。
- 基线纪律：pg_stat_statements_max 默认 5000 LRU 淘汰——热查询别被
  挤出；周期快照差分落独立表，reset 前先落快照；升级后重置基线。

```sql
SELECT queryid, calls, round(mean_exec_time::numeric,1) AS ms,
  wal_bytes FROM pg_stat_statements
  ORDER BY total_exec_time DESC LIMIT 20;  -- 发布前后差分=回归报告
```

- 陷阱：拿 pgss 的 shared_blks_hit 汇总喂"命中率 KPI"就是 50 节神话
  重演——它的价值在差分定位（谁变慢/谁在写 WAL），不在全局比率。

## 57. 物理 vs 逻辑复制选型

- 物理复制是 WAL 字节流：主备严格一致、毫秒级延迟、DDL 随 WAL
  天然生效；代价是整库拷贝、备库只读、两端必须同 major 同平台。
- 逻辑复制是 pgoutput 的发布/订阅模型：表级粒度、可跨版本跨云
  （升级蓝绿主通道，6 节）、订阅端可写，15+ 行过滤、16+ 列清单；
  代价是 DDL 不复制、解码持续吃 CPU。
- 延迟量级差一位：物理=落盘+网络+回放；逻辑还要解码与事务重组，
  大事务分钟级滞后正常——别拿它对业务承诺热备切换窗口。
- 混用是常态：物理层做同机房 HA 与读扩展（61、62 节），逻辑层
  做分发/CDC/滚动升级；一层管故障域，一层管数据流。

```sql
-- 物理层：备库 primary_conninfo 指主；逻辑层：按表发布
CREATE PUBLICATION app_pub FOR TABLE orders, order_items;
CREATE SUBSCRIPTION app_sub CONNECTION 'host=olap-01 ...'
  PUBLICATION app_pub WITH (copy_data = true);
```

- 陷阱：拿逻辑复制当 HA——订阅端有延迟窗口且可写，切换后可能
  丢数据或分叉；HA 归物理、分发归逻辑，边界写进 runbook。

## 58. 逻辑复制槽与 WAL 膨胀

- 槽是一句承诺：restart_lsn 之前的 WAL 随便回收，之后全部保留；
  restart_lsn=槽仍需的最早事务起点，confirmed_flush_lsn=订阅者
  确认位——与 pg_current_wal_lsn() 的差就是槽欠的债。
- 订阅者停摆=磁盘定时炸弹：业务照常写 WAL，槽把每段都扣下不删，
  pg_wal 线性膨胀直至写满；低写入量也撑爆一个周末。
- 13+ 的 max_slot_wal_keep_size 是保命阀：超限后 checkpoint 不再
  为它保 WAL，wal_status 转 lost、重启即废弃——宁可丢订阅重同步，
  不能让主库先死。
- 红灯组合：retained WAL 持续增长 + active=false + confirmed_flush
  停更——订阅端已死但槽还活着，按分钟级事件处理。

```sql
SELECT slot_name, active, wal_status,
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn))
  FROM pg_replication_slots;   -- retained 一路涨：先问是死了还是弃了
```

- 陷阱：订阅端已弃但 DROP SUBSCRIPTION 卡住（要连发布端远程删槽）
  ——先 SET (slot_name = NONE) 解绑再 DROP，最后上主库删槽收尸。

## 59. 逻辑复制限制清单

- DDL 不复制是第一大边界：两边 schema 各自演进，先订阅端加列
  （可空/有默认）再发布端——顺序错了 apply 立刻断流。
- 序列不复制：初始同步只 COPY 数据，nextval 各自独立——切换前
  setval 对齐，否则订阅端首笔 INSERT 就撞主键。
- 无主键表不能复制 UPDATE/DELETE：REPLICA IDENTITY FULL 让订阅端
  逐行全列比对（宽表灾难）；大对象不复制；TRUNCATE 默认复制，
  publish 开关想清楚再开。
- 初始拷贝：每表一个 sync worker 走 COPY，max_sync_workers_per_
  subscription（默认 2）控并发；拷贝期还要回放追增量，带宽与
  WAL 水位留余量，别在大促当天开新订阅。
- ADD TABLE 触发新一轮初始同步；列对齐老版本按位置、新版本按
  列名——跨版本两端列序就是协议，改列名先排练。

```sql
ALTER SUBSCRIPTION olap_sub DISABLE;                 -- 停 apply 保槽
ALTER SUBSCRIPTION olap_sub SET (slot_name = NONE);  -- 弃用先解绑
```

- 陷阱：初始拷贝大表前不看槽水位——长拷贝窗口内主槽 WAL 保留被
  抬升，58 节的炸弹最容易在拷贝期间引爆；先设保命阀再开同步。

## 60. 同步复制与 synchronous_commit

- 四档语义、粒度是每笔提交：off=主库内存即回（丢最近约 3×wal_
  writer_delay）；local=本机刷盘；remote_write=备库收到未刷盘；
  remote_apply=备库回放完——只有这档保证备库读到已提交数据。
- 同步端与提交档正交：FIRST 1 (s1,s2) 按优先级，ANY 2 (s1,s2,s3)
  是 quorum——ANY 直译"至少 n 台落了"，多可用区部署的标配。
- 丢 0 vs 丢 1 的业务翻译：off 丢几百毫秒（埋点可接受）；local
  丢机器不丢机房；remote_apply 换读写分离一致性——延迟翻倍起步，
  逐业务选档而不是全局一刀切。
- 同步备库全体掉线：提交不报错而是无限挂起——没有"放弃等待"的
  超时开关，应急只能改 synchronous_standby_names='' 并 reload，
  这条操作预先写进 96 节 runbook。

```sql
SET LOCAL synchronous_commit = remote_apply;  -- 事务内逐笔选档
SHOW synchronous_standby_names;               -- 'ANY 2 (r1, r2, r3)'
```

- 陷阱：以为同步只贵在延迟——等待队列让提交串行等 ack，备库抖动
  时主库写吞吐被拖死；用 flush_lag/replay_lag 趋势定档，别拍脑袋。

## 61. 故障转移与脑裂防护

- 脑裂形态：旧主复活继续写、新主也在写——物理复制无法双向合并，
  WAL 链永久分叉，只能挑一边丢数据，没有事后修补。
- fencing 三件套缺一不可：网络隔离（VIP/LB/DNS 收口）+存储隔离
  （STONITH 断电或交换机 drop，比相信旧主自觉退出硬）+仲裁
  （Patroni+etcd 多数派，双节点挂见证机）。
- promote 不可逆：备库转主回不去；旧主回归必须 pg_rewind 回拉，
  前提 wal_log_hints=on 或 data checksums 开——没开等于宣布
  "旧机直接重建"。
- 应用侧防线：target_session_attrs=read-write 与只读探针挡住"连到
  旧主还在写"；重连风暴（95 节）在探针后加退避。

```sql
-- 手工最小防线：先隔离旧主，确认仲裁锁，再 promote
SELECT pg_is_in_recovery();   -- 应用探针挂这条，别靠端口号猜角色
```

- 陷阱：没 promote 过的集群不算有脑裂防护——演练时故意拉起旧主，
  验证 fencing 真挡住了写入；防线验收在演练，不在架构图（66 节）。

## 62. 级联复制与延迟副本

- 级联扇出：备库可继续当下游的上游，主库只维护一层 walsender
  ——读副本成群时，级联是把 CPU/带宽压力挪出主库的第一选择。
- recovery_min_apply_delay 人为回放延迟（如 30min）：误 UPDATE 后
  有半小时后悔药窗口——但副本上是旧数据，挂报表前想清楚口径。
- replay_lag 只反映管道，端到端延迟用心跳表打点最诚实（66 节）
  ——延迟值本身要进 92 节红灯清单。
- 重连风暴：切主后下游全部找新主——primary_conninfo 填多 host
  （10+）按序自动探测，别指望人改配置。
- slot 漂移：级联下游的逻辑槽建在中间备库上，切主后槽不在新
  上游——17 前要备好"promote 后重建槽"脚本（63 节），槽清单
  版本化进配置仓库。

```sql
# 延迟副本：备库 postgresql.conf
primary_conninfo = 'host=primary,standby-b port=5432 ...'  # 多 host 探测
recovery_min_apply_delay = '30min'   # 灾备缓冲窗；报表别直连这台
```

- 陷阱：把延迟副本当实时读副本——查询旧 30 分钟还不报错；副本
  用途与延迟参数要在主机命名和监控标签上一眼可见。

## 63. 复制槽故障转移（17 failover slots）

- 17 前的死结：逻辑槽只能活在主库，备库建不了——故障转移后订阅
  断流，主库 WAL 开始为一个"已经不在"的槽堆积（58 节连环剧本）。
- 17 解法：备库 sync_replication_slots=on 持续同步槽状态，成对的
  hot_standby_feedback=on 把备库 xmin 报回主库防 vacuum 抢走；
  promote 后槽即刻可用，订阅无缝续传。
- 同步语义是尽力而非精确：网络分区期间槽停在最后同步点，此时
  failover 丢槽进度——订阅端重同步还是跳位，决策提前写进
  runbook（66 节），别在现场发明。
- 备库硬前提：wal_level=logical、max_replication_slots 足够——配置
  不齐时 sync_replication_slots 静默不生效，没有报错替你把关。

```sql
# 备库 postgresql.conf（17）
sync_replication_slots = on      # 与下面成对出现
hot_standby_feedback = on
```

- 陷阱：以为开了 failover slots 万事大吉——槽同步有滞后窗口；演练
  故意断网再 promote，看订阅续上还是断，才是这功能的验收。

## 64. CDC 与 outbox 模式

- 双写死路：提交 DB 后再发 Kafka，进程两步之间崩=消息丢；先发再
  提交=幽灵消息——任何"两系统都写"都缺原子性。
- outbox 修法：业务行与事件行同一事务落库，投递器单独读 outbox
  发消息——原子性交给 DB 事务，投递退化成可重试问题。
- 投递取数两条路：轮询（简单、秒级延迟、空转查询常年跑）vs 逻辑
  解码（pgoutput/wal2json 抓 outbox 变更，毫秒级不加查询负载）；
  高吞吐选解码，lsn 断点存进度表。
- 至少一次投递是物理现实：消费者幂等是义务——事件 id 去重或
  (agg_id, version) 乐观检查；"恰好一次"是中间件话术，别当设计
  依据。
- 批量消费：peek_changes+get_changes 按 upto_lsn 截断成批——单条
  逐发的延迟就是 52 节往返放大在 CDC 侧重演。

```sql
INSERT INTO outbox(agg_id, event_type, payload, created_at)
VALUES ($1, 'OrderPaid', $2::jsonb, now());  -- 与业务行同一事务
SELECT lsn, data FROM pg_logical_slot_peek_changes('cdc', NULL, 200);
```

- 陷阱：outbox 表没人治理——事件流表必须分区或滚动归档（85 节），
  否则半年后它是全库最大的表，解码延迟失控。

## 65. 多主与冲突解决

- 异步多主（BDR 类）必然冲突：两节点同改一行、复制事后到达，
  "不冲突"只是概率幻觉——冲突策略在建模期定，出事后定就只能
  丢得没有章法。
- LWW 最便宜也最危险：时钟偏斜直接静默丢更新，NTP 漂移 200ms
  就够掀翻账务语义；上 LWW 前先回答"谁的钟说了算"。
- 业务合并才可靠：字段级 last-write、计数器加法、CRDT——逐字段
  想清"可交换"；想不清的字段禁止多写。
- 冲突审计留全：两边旧值新值、时间、节点——冲突现场是产品事故
  的取证库，只记"谁赢"等于销毁证据。
- 能单主就别多主：九成"想上多主"的真实诉求是跨地域写延迟——
  就近写+行归属分区（每行只有一个主 region）就解了，没冲突就没
  这层税。

```sql
CREATE TABLE conflict_log (          -- 多主环境强制件
  tbl text, pk text, local_val jsonb, remote_val jsonb,
  resolved_via text, site text, ts timestamptz DEFAULT now());
```

- 陷阱：把多主当可用性方案卖——脑裂期间两边照常写、事后合并，
  丢的是用户看得见的数据；多主买的是写延迟，不是可用性。

## 66. RPO/RTO 与演练纪律

- RPO 由架构决定：异步=秒到分钟窗口，归档节奏=归档间隔+
  archive_timeout，同步提交=零丢（60 节）；RTO=检测+决策+promote+
  应用重连，每环实测过才作数。
- 数字从演练来：文档写"RTO=5min"没有意义，上次真 promote 计时
  多少才是 RTO——每季度真切换一次，演练报告进容量计划。
- 演练覆盖三场景：计划内切换；主库宕机（kill -9+网络隔离，专验
  fencing 61 节）；备份恢复（PITR 到新机跑业务校验，93 节——没
  验证的备份等于没有备份）。
- 恢复脚本版本化：promote、pg_rewind、槽重建（63 节）与 schema
  同仓演进；"旧备份配旧脚本"的组合要在演练里跑过。
- 告警口径挂钩：RPO 超限=复制延迟/归档 lag 红灯（92 节），RTO
  恶化=演练耗时趋势——两个数字上管理面板，别只躺 wiki。

```sql
INSERT INTO replication_heartbeat (ts) VALUES (clock_timestamp());
SELECT now() - ts AS end_to_end_lag FROM replication_heartbeat;
                                          -- 各副本上读，进监控
```

- 陷阱：演练挑"好日子"做——真故障总在高峰+值班新人手上；至少
  一次演练放高峰期，验收标准写明"值班同学照 runbook 走"。

## 67. 窗口函数 frame 选择成本

- 默认 frame 是 RANGE UNBOUNDED PRECEDING...CURRENT ROW 而非
  ROWS——差别在 peer 行：RANGE 把排序值相同的行整组框进去，
  语义与成本都不是"逐行"。
- ROWS 通常更快：免 peer 判定；带偏移的 RANGE（11+）无法滑进
  滑出增量维护，常退化成每行重划范围——大表 CPU 差出数倍。
- WindowAgg 输入必须按 PARTITION BY+ORDER BY 排好——底下的
  Sort 才是成本大头；索引序与窗口定义逐字匹配就免排序（21 节
  列序原则的窗口版）。
- ROWS BETWEEN N PRECEDING AND N FOLLOWING 是真增量，但状态驻留
  约 2N 行——撑爆 work_mem 就是 51 节溢出现场，判读方法照用。
- 同一分区多窗口共享排序：计划里 Sort 出现次数=成本单元，
  WINDOW 子句声明多个 frame 比散写多个窗口函数便宜。

```sql
SELECT id, ts, amount, sum(amount) OVER w AS running_total
  FROM ledger
WINDOW w AS (PARTITION BY acct_id ORDER BY ts, id
             ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW);
```

- 陷阱：排序键没带唯一 tiebreaker，peer 成组——sum 同组同值，
  "逐行累计"变"逐组累计"；窗口 ORDER BY 永远以唯一键收尾（69 节）。

## 68. gaps-and-islands 模式

- 岛问题=连续行分组：经典解是 row_number 差值法——row_number()
  over (ORDER BY x) 减 row_number() over (PARTITION BY g ORDER BY
  x) 得常数，常数即岛 id；纯集合、无自引用。
- lag 标志法更贴业务：x - lag(x) > 阈值判"新岛"、标志累加成组
  ——对不规则间隔（业务阈值 30 分钟而非"连续"）唯一可选，也
  最好解释。
- 洞补齐：generate_series 生成骨架再 LEFT JOIN 业务表——洞在
  SQL 补，别在应用层补（每个图表各自实现一遍=各自一遍 bug）。
- 边界判读：岛起止=分组后 min/max；业务口径（开闭区间、首尾洞
  算不算）写进注释——口径漂移是这模式的最大事故源。
- 两种解法百万行级都便宜；贵在外层 join 回明细——先聚合出岛
  id 再 join，别把窗口嵌进 join 之后二次排序。

```sql
SELECT id, ts, sum(new_session) OVER (ORDER BY ts, id) AS session_id
  FROM (SELECT id, ts,
               CASE WHEN ts - lag(ts) OVER (ORDER BY ts, id)
                    > interval '30 min' THEN 1 ELSE 0 END AS new_session
          FROM clicks) s;
```

- 陷阱：差值法两个 row_number 的 ORDER BY 必须完全一致——差一点
  就是"看起来对"的静默错位；lag 法漏 tiebreaker 会切出鬼会话。

## 69. keyset 分页与 OFFSET 退化

- OFFSET 10000 的真实价格：扫 10020 行丢 10000 行——每页都付从头
  到当前的累积成本，深页延迟线性恶化；缓存救不了，浪费在引擎
  内部。
- keyset/seek：WHERE (created_at, id) > (:ts, :id) ORDER BY created_
  at, id LIMIT n——行值比较 PG 原生支持，B-tree 直接落游标位置，
  任何深度恒定成本。
- 稳定排序是前提：排序键必须以唯一列收尾做 tiebreaker——分页
  接口出现裸 ORDER BY created_at 就是 bug：行序不稳，翻页丢/重。
- 游标编码：把 (ts,id) base64 成不透明 cursor 给客户端——前端
  不该知道更不该拼内部键；解码侧校验类型与时区（79 节）。
- 深页跳转与 keyset 天然冲突：要么砍掉（无限滚动与 keyset 天作
  之合），要么用页首键稀疏映射表模拟页码。

```sql
SELECT order_id, created_at FROM orders
 WHERE (created_at, order_id) > (:last_ts, :last_id)
 ORDER BY created_at, order_id
 LIMIT 21;   -- 多取 1 行探"下一页"，比 COUNT 便宜一万倍（102 节）
```

- 陷阱：排序列可变（updated_at 分页）——行更新后跨页漂移，重复
  出现或消失；用不可变列分页，或接受重复由客户端幂等去重。

## 70. 递归 CTE 与收敛控制

- UNION（非 ALL）每轮去重防环，但去重状态整树驻留到递归结束
  ——大图的内存就是这么爆的；UNION ALL 不驻留、快，遇环=无限
  循环。
- 环检测正路：路径数组 NOT path @> ARRAY[next] 判定，或 14+ 的
  CYCLE 声明式写法；都要再配深度上限双保险——环闸防死循环，
  深度闸防病态数据。
- 收敛三件套：LIMIT 截断、深度上限（level <= :max）、迭代预算；
  "这数据不可能有环"是事故的标准开场白。
- 树 vs 图边界：邻接表+递归 CTE 搞定树；多路径、最短路、强连通
  是图引擎/应用层的活——SQL 硬写要么慢要么错，识别边界比优化
  错误路径值钱。
- 宽浅树另一条路：闭包表或物化路径让任意子树变等值/前缀查询；
  递归 CTE 只留给真递归场景。

```sql
WITH RECURSIVE tree AS (
  SELECT id, parent_id, 1 AS depth, ARRAY[id] AS path
    FROM nodes WHERE parent_id IS NULL
  UNION ALL
  SELECT n.id, n.parent_id, t.depth+1, t.path || n.id
    FROM nodes n JOIN tree t ON n.parent_id = t.id
   WHERE NOT t.path @> ARRAY[n.id] AND t.depth < 64)  -- 环闸+深度闸
SELECT * FROM tree;
```

- 陷阱：递归部分里塞聚合/窗口——每轮工作表上的语义与直觉不同
  （67 节）；聚合一律放外层，递归段只做图遍历。

## 71. GROUPING SETS 与聚合分析

- GROUPING SETS ((city,cat),(city),()) 精确列举；ROLLUP(a,b) 是
  (a,b),(a),() 的前缀层级；CUBE(a,b) 是 2^n 全组合——维度一多
  CUBE 组合数先爆。
- 超聚合行的 NULL 与数据 NULL 无法区分——GROUPING(col)=1 表示
  "此列被汇总掉了"，报表端拿它渲染合计行；别发明 'TOTAL' 哨兵
  值给后续 join 埋雷。
- 计划读法：一次排序/扫描服务多组聚合，节点是 MixedAggregate
  ——比 UNION ALL 手写三份便宜得多；"GROUPING SETS 慢"是没看
  计划的误传（10 节取证）。
- 与 CTE 组合纪律：聚合前过滤放 CTE 内（谓词可下推），聚合后
  筛选放外层——写反就是每组各扫一遍的三倍税。
- 组间顺序不保证：小计/合计的展示序用 GROUPING() 排序而不是赌
  计划；报表口径确定性靠显式 ORDER BY。

```sql
SELECT city, category, GROUPING(city) AS g_city, sum(amount) AS total
  FROM sales
 GROUP BY ROLLUP (city, category)
 ORDER BY GROUPING(city), city, GROUPING(category), category;
```

- 陷阱：ROLLUP 是前缀语义——ROLLUP(a,b,c) 不含 (b,c)、(a,c)；
  要任意组合回 GROUPING SETS 显式列举，口径写注释免得两年后
  没人认识这条 SQL。

## 72. DISTINCT ON 方言

- PG 独有语法：SELECT DISTINCT ON (a) ... ORDER BY a, x——按 a
  分组、组内按 x 序取第一行；外层 ORDER BY 必须以 DISTINCT ON
  表达式开头，语法级强绑定。
- ORDER BY 少写组内排序键不报错：取哪行未定义（取决于计划行序）
  ——升级、改索引后"每组第一行"悄悄变脸，这语法最大的坑没有
  之一。
- 等价窗口写法：row_number() OVER (PARTITION BY a ORDER BY x) 置
  1 再过滤——语义显式、跨库通用；代价是全量物化行号再丢弃。
- 性能取舍：DISTINCT ON 一次 Sort（或吃索引序）+分组取首；窗口
  写法多物化一层——大表差在 Sort 宽度，窗口列清单压窄（101 节）
  能救回一截。
- 其他库等价：MySQL/SQLite 窗口 row_number（8.0/3.25+），SQL
  Server 惯用 CROSS APPLY top-1——方言记账进 111 节能力表。

```sql
SELECT DISTINCT ON (user_id) user_id, order_id, created_at, amount
  FROM orders
 ORDER BY user_id, created_at DESC, order_id DESC;  -- 唯一键收尾防 ties
```

- 陷阱：把 DISTINCT ON 当"分组取任意一行"——"任意"是未定义行为，
  不是随机也不是最老；组内选行规则必须显式写进 ORDER BY。

## 73. JSONB 深水与 GIN 选择

- jsonb 是解析后的树：键排序去重、数字存 numeric、原始键序不
  保留、重复键留最后一个——从 jsonb 找"文档原顺序"是方向性
  错误，要顺序存 text。
- GIN 两 opclass：jsonb_ops 全操作符但大；jsonb_path_ops 只支持
  @>、键存路径哈希，小 2-3 倍——查询面只有包含语义就无脑
  path_ops（26 节 fastupdate 债同适用）。
- @> 走 GIN；->> 深层提取每次可能解压整行 TOAST——高频谓词建
  表达式索引 ((payload->>'status'))，再叠 partial 只保热值（23 节）。
- 表达式索引附带独立统计：payload->>'status' 的 MCV/直方图让
  规划器看见 jsonb 内部倾斜（32、34 节）——没有它选择性全靠猜。
- jsonb_path_query（12+）适合结构里捞数组再展开；path 查询不走
  @> 的 GIN——先 @> 粗筛再 path 细查才是组合拳。

```sql
CREATE INDEX ON events USING gin (payload jsonb_path_ops);
CREATE INDEX ON events ((payload->>'status'))
  WHERE payload->>'status' IN ('paid', 'refunded');
```

- 陷阱：jsonb 当万能列——外键/约束/统计该有的字段塞进 jsonb，
  报表要 join 时发现没一列能建索引；该毕业成列的按 105 节提升。

## 74. 数组批量与 unnest 进阶

- 多数组平行展开：SELECT * FROM unnest($1::bigint[], $2::text[])
  ——数组按下标配对成行，一次往返传整批（54 节批模式主力）。
- 等长纪律：10+ 不等长时短数组以 NULL 补齐到最长（10 前截断到
  最短）——都不报错；批量代码先 assert cardinality，不然"少写
  几行"查一晚。
- WITH ORDINALITY 拿回批内下标：保序更新、行号对齐靠它；
  array_position 反查是线性扫描的慢路，别当连接键。
- 数组谓词：@> 包含、&& 重叠走 GIN（array_ops）——"标签集包含
  查询"是数组+GIN 主场，比 EXISTS 仿真数组便宜一个量级。
- 批尺寸甜点几千行：十万行级大数组让 parse/plan 时间吞掉批收益，
  还占协议带宽；分批循环比一杆子传完稳。

```sql
INSERT INTO tags (user_id, label)
SELECT * FROM unnest($1::bigint[], $2::text[])
ON CONFLICT (user_id, label) DO UPDATE SET updated_at = now();
```

- 陷阱：数组列当关系表用（存 id 列表应用层展开）——无外键、无
  join、无统计三连失明；数组适合标签/只读快照，引用语义用
  junction 表。

## 75. 物化视图与增量刷新

- PG 无原生增量：REFRESH 全量重算——普通 REFRESH 拿 ACCESS
  EXCLUSIVE 连读都挡（离线报表才配用）；CONCURRENTLY 拿
  EXCLUSIVE 只挡写不挡读，靠新旧 diff 合并，前提唯一索引。
- CONCURRENTLY 隐形成本：两份物化结果+diff 的磁盘与 CPU，刷新期
  I/O 洪峰挤占业务——错峰调度比参数调优有效。
- ivm 扩展触发器维护增量（写时付费、读时免费）——适合读极多、
  写中等、容忍毛刺的中间层；上游大批量灌数时触发器风暴先实测。
- 失效策略比刷新策略重要：上游变更打标记、消费前懒刷新——"按
  需新鲜"比"每 5 分钟全刷"省一个量级，刷新次数与真实查询量成
  正比。
- REFRESH 要物化视图 owner 权限——运维账号与应用账号分离的团队
  这里必掉坑（98 节），调度任务执行身份要明确。

```sql
CREATE UNIQUE INDEX ON mv_daily_sales (stat_date, city); -- 前提
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_daily_sales;
```

- 陷阱：物化视图叠物化视图冒充数仓——血缘没人说得清、刷新顺序
  全凭 cron 赌；三层以上的物化链迁去正经调度+血缘工具（107 节
  设为评审门槛）。

## 76. 生成列：stored 与 PG18 virtual

- 生成列=从同表其他列派生、应用不可写；18 前只有 STORED：写入
  时算好落盘，读零成本，写路径每行多付一次表达式。
- 18 起 VIRTUAL 成默认：值不落盘、读时现算——写放大消失，代价
  是每次读都付表达式的钱；低频读的派生字段是主场。
- 索引边界定九成选型：STORED 可索引可进唯一约束；18.0 的
  VIRTUAL 不能建索引——要索引的派生值只能 STORED，记反了评审
  就翻车。
- 重表达式高频读选 STORED（一次算多次用）；迭代频繁选 VIRTUAL
  ——STORED 改表达式要 rewrite 表（86 节），VIRTUAL 只动元数据。
- 生成列表达式必须 IMMUTABLE（24 节纪律）：时区相关派生用固定
  时区写法（79 节）；now() 这类易变函数直接禁止入内。

```sql
CREATE TABLE invoices (
  amount   numeric,
  tax_rate numeric,
  total    numeric GENERATED ALWAYS AS (amount * tax_rate) STORED);
ALTER TABLE invoices ADD COLUMN total_r numeric
  GENERATED ALWAYS AS (round(amount * tax_rate, 2)) VIRTUAL;  -- 18
```

- 陷阱：virtual 列"省写入"就随便加——读它所在表的每个宽查询都
  多付一次求值；按需加、按读频评估，与 101 节列清单纪律同源。

## 77. NOT VALID 约束与可信窗口

- 大表加 CHECK/FK 正路：ADD CONSTRAINT ... NOT VALID——只校验新
  写入，秒级完成；存量数据责任转移到 VALIDATE 阶段，这就是
  "可信窗口"的由来。
- VALIDATE CONSTRAINT 用 SHARE UPDATE EXCLUSIVE：不挡读写，但
  全表扫描免不了——低峰跑、大表排队；失败则保持"未验证"，
  修完数再来，不是整段回滚。
- 窗口期纪律：NOT VALID 期间下游不能假设约束全程生效（新行过检、
  老行未必）；VALIDATE 完成时间登记进迁移台账。
- 与零停机迁移组合：45 节 contract 段=NOT VALID→回填修数→
  VALIDATE；三段间隔几个发布很正常，没有 convalidated 审计就
  永远停在中间态。
- 审计是固定件：pg_constraint.convalidated=false 清单进每日检查
  ——被遗忘的 NOT VALID FK 等于对外宣告了不存在的完整性。

```sql
ALTER TABLE orders ADD CONSTRAINT fk_c
  FOREIGN KEY (customer_id) REFERENCES customers (id) NOT VALID;
ALTER TABLE orders VALIDATE CONSTRAINT fk_c;
```

- 陷阱：以为 NOT VALID 期间老行怎么动都行——UPDATE 老行同样要
  过新约束，批量任务撞存量坏行当场失败；修数排在 VALIDATE 前，
  回填与校验用同一套过滤条件。

## 78. 视图与 security_barrier

- 普通视图默认以 owner 身份执行（definer 语义）：调用者无需基表
  权限也能查——授权收口的同时埋着"owner 权限回收、全链视图
  瘫痪"的雷（106 节）。
- security_barrier=on：视图谓词先于用户谓词执行、非 leakproof
  函数下推被禁——防错误信息/函数副作用把不可见行侧信道漏出去；
  是安全开关不是性能开关。
- 性能代价：谓词下推被栅栏掐断（leakproof 例外），计划变差是
  常态；barrier 视图查询要连视图谓词一起做计划验证（10 节）。
- security_invoker（15+）：视图按调用者权限执行——配 RLS 必需，
  否则 definer 直接绕过行级策略（97 节）；切换前补齐基表权限。
- 权限模型按泄露面分：公开报表 definer+基表无敏感列；租户数据
  invoker+RLS；对外 API 视图一律 barrier——不按写代码方便分。

```sql
CREATE VIEW tenant_orders WITH (security_barrier = true,
                                security_invoker = true) AS
SELECT * FROM orders
 WHERE tenant_id = current_setting('app.tenant_id')::bigint;
```

- 陷阱：barrier 视图的基表索引没按视图谓词建——谓词被栅栏保住
  后走 Filter 全扫；barrier+RLS 要在真实角色下 EXPLAIN，Filter
  就是证据（99 节读法）。

## 79. timestamptz 时区纪律

- 内部真相：timestamptz 存 UTC 微秒，时区只在渲染与解析——"存
  进去带没带时区"是伪问题；timestamp（无 tz）存墙上钟字面量，
  跨时区部署就是雷，新建 schema 一律 timestamptz。
- 会话 TimeZone 影响显示与字面量解析：'2025-03-01 00:00'::
  timestamptz 在不同会话时区下是不同时刻；连接池里会话时区不
  一致就是灵异 bug 之源（47 节）。
- AT TIME ZONE 双向背熟：timestamptz AT TIME ZONE 'Asia/
  Shanghai'→timestamp（当地墙钟）；timestamp AT TIME ZONE
  'UTC'→timestamptz——方向写反差 8 小时。
- DST 二义：回拨那小时墙上钟出现两次——墙上钟列存这时刻无法
  解释；跨时区调度用 timestamptz，"每天本地 9 点"在应用层展开
  成绝对时刻再入库。
- 三个一律：列一律 timestamptz、连接初始化一律 SET TimeZone=
  'UTC'、API 序列化一律 UTC ISO8601——例外写进架构决策记录。

```sql
SET TimeZone = 'UTC';                    -- 连接初始化写死（47 节）
SELECT count(*) FROM events              -- "哪里的天"要显式（100 节）
 WHERE ts >= '2025-03-01 00:00+08'::timestamptz
   AND ts <  '2025-03-02 00:00+08'::timestamptz;
```

- 陷阱：date(ts) 按会话时区切天——同一条 SQL 在 UTC 会话与上海
  会话切出不同日界；报表"按天"必须显式 AT TIME ZONE 后再切
  （24 节禁令一起执行）。

## 80. text 与 varchar 神话破除

- text、varchar、varchar(n) 存储与执行完全一致（同一 varlena
  表示）——没有"varchar 更快"没有"text 更省"；选型只看约束
  语义，性能差异是纯神话。
- varchar(n) 唯一价值是长度约束：PG 超长报错；MySQL 严格模式
  报错、非严格静默截断——同一条 DDL 跨库行为不同（111 节），
  迁移最容易"差一点"。
- char(n) 尾部空白填充：存储多付、比较忽略尾空格——语义与 text
  不同、跨库还不一致（MySQL 校对 PAD/NO PAD 又一套）；新 schema
  禁 char(n)。
- citext 做大小写不敏感等值（邮箱、slug）：唯一索引天然 CI——
  但 citext 是独立类型家族，索引按 citext 建，text 索引不服务
  citext 谓词（99 节变体）。
- 大字段纪律：超长值 TOAST 挤出主堆（101 节）——text 不设限没
  问题，业务上限没人管时一行 100MB 的"备注"打趴查询内存与
  复制带宽。

```sql
CREATE TABLE users (
  email citext UNIQUE,                     -- 大小写不敏感唯一
  bio   text CHECK (length(bio) <= 10000)  -- 上限交给 CHECK
);
```

- 陷阱：改 varchar 长度当小事——放宽是纯元数据变更（86 节免重
  写），收紧要全表校验重写，锁时长差一个灾难级；评审先问方向。

## 81. UUIDv7 与索引局部性

- v4 随机 UUID 是 B-tree 毒药：插入均匀散布全树——中分裂为主、
  页均填充约 2/3、缓冲局部性差（20 节对照）；大表插入 TPS 与
  有序键差一截，WAL 更碎。
- v7 毫秒时间戳前缀：同毫秒随机、跨毫秒单调——右分裂、高填充、
  热页友好；18 起内建 uuidv7()，客户端生成库功成身退。
- int8 identity 仍是吞吐之王：8 字节 vs 16 字节键、索引窄一半、
  比较快——单库无分布式诉求就 identity，别为"看着现代"付
  UUID 税。
- UUID 真正收益在分布式：分片、离线端、多源合并都不需要中心
  发号器；v7 保住收益同时把索引税降到最低。
- 隐私权衡：v7 前缀=公开创建时间——ID 外泄"这单多新"；把 ID
  当不透明凭据（分享链接、单号截图）的业务别用 v7，威胁模型
  里记账。

```sql
CREATE TABLE events (
  id uuid PRIMARY KEY DEFAULT uuidv7(),     -- 18+ 右分裂（20 节）
  tenant_id bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now());
```

- 陷阱：把"ID 排序"当"创建时间排序"——v7 同毫秒内仍随机；业务
  排序靠 created_at，id 只当 tiebreaker（69 节），用错就在毫秒
  内乱序。

## 82. numeric 与 float8 取舍

- numeric 任意精度十进制、精确但慢：变长结构+软件运算，混合算术
  比 float8 慢一个量级；float8 硬件 double、快但有二进制舍入
  误差——0.1+0.2 != 0.3 是物理不是 bug。
- 钱的纪律：金额 numeric(14,2) 或整型"分"，float 禁入——审计
  对不上账的 0.005 误差在金融场景就是事故报告，不是精度趣闻。
- float8 等值比较是禁区：聚合累加、GROUP BY、= 连接都因舍入次序
  不稳——join 键出现 float8 就是设计缺陷，改掉没有例外。
- numeric 除法 scale 是历史包袱：结果小数位由被除数推导、还附赠
  足量小数（新版本口径收紧过）——金融算式显式 round(x, n) 钉死
  精度。
- 聚合放大：sum(float8) 误差随行数累积且依赖行序——"大表统计
  每次跑差一点"就是它的签名；换 numeric 聚合或定点整型才稳。

```sql
ALTER TABLE invoices ALTER COLUMN amount TYPE numeric(14,2);
SELECT round(sum(amount), 2) FROM invoices  -- 聚合后再显式定标
 WHERE status = 'paid';
```

- 陷阱：numeric 列配 float8 参数混算把 numeric 隐式拉进 float8
  ——精度静默丢失，99 节索引杀手同案犯；驱动绑定发 numeric，
  别让 setDouble 进金额谓词。

## 83. enum 锁陷阱与 lookup 表

- enum 加值是 DDL：ALTER TYPE ... ADD VALUE——12 前不能进事务块
  （迁移工具默认包事务直接炸）；12+ 可进事务但同事务内不能引用
  新值，迁移框架的"半步"在这里翻车最多。
- enum 不可删值、改值文本受限：退役枚举值只能"新增新值+迁数据+
  永远背旧值"——演进成本三年后才看得清。
- 比较按定义序不是字典序：'high' < 'low' 取决于定义次序——
  ORDER BY 让人当场怀疑人生；定义位次（10+ BEFORE/AFTER）与
  业务序耦合就是暗雷。
- lookup 表正路：加值=INSERT（无 DDL 无锁、逻辑复制可带走）、可
  挂元数据、可软退役；代价是 join 一次+FK 写放大（84 节）+应用
  层缓存这张小表。
- 决策线：值域极稳定（支付通道）enum 也行；会增长、会细分、要
  挂属性（类目、渠道）一律 lookup——演进频率定选型，"少一张表"
  不构成理由。

```sql
CREATE TABLE channels (id smallint PRIMARY KEY, name text UNIQUE,
                       retired boolean NOT NULL DEFAULT false);
INSERT INTO channels VALUES (4, 'alipay');  -- 加值是 DML，复制可带走
```

- 陷阱：逻辑复制拓扑只改了发布端 enum——DDL 不复制（59 节），
  订阅端 INSERT 带新枚举值 apply 直接断流；enum 变更双端同步，
  进 DDL 清单管着。

## 84. 外键写放大与 ON DELETE

- 子表每写一行，父行吃一把 FOR KEY SHARE 行锁——父行 DELETE/键
  改要等所有子写事务结束；高并发"热点父行"（爆款商品、大租户）
  把子表写入串成队列，43 节里最常被无视的一档。
- FK 检查是隐式查询：父表键上必须有索引，否则每笔子写入全扫父
  表——"加 FK 后写吞吐腰斩"第一嫌疑是缺父键索引，第二才是行锁。
- ON DELETE CASCADE 是删除风暴引信：删一父行触发整树子删——锁
  面、WAL、复制延迟全失控（64 节订阅端被打爆的常见形态）；深层
  级联禁止，改应用层分批显式删+归档（88 节）。
- DEFERRABLE INITIALLY DEFERRED 把检查挪到提交时：两段式互引导入
  不再排序敏感；代价是提交时集中校验、失败整事务回滚（41 节）。
- 高并发扣减别用 FK 语义抢：UPDATE stock SET n = n - 1 WHERE
  n >= 1 一行抢占；FK 保完整性，业务不变量永远用谓词表达。

```sql
DELETE FROM order_items WHERE order_id IN   -- 分批显式删，每批有界
  (SELECT order_id FROM dead_orders LIMIT 5000);
DELETE FROM orders o USING dead_orders d WHERE o.order_id = d.order_id;
```

- 陷阱：NO ACTION 与 RESTRICT 不等价——NO ACTION 可 DEFERRABLE
  （提交时查），RESTRICT 永远立即；挑错约束类型，"有时能过有时
  不能过"的玄学多源于此。

## 85. 分区策略与裁剪

- range 管时间序列（冷热分层、detach 归档）、list 管枚举域（租户/
  地区）、hash 管均匀打散——先想查询谓词再想分区数，键选错后面
  全错。
- 分区键必须在主键/唯一约束里：全局唯一性不被支持——(id) 全局
  主键与 (tenant_id) 分区不可兼得，主键要变 (tenant_id, id)，
  ORM 与 FK 定义跟着改。
- 裁剪对谓词形状挑剔：分区键上要有可比较常量/参数——date(ts)
  函数包裹直接杀裁剪（100 节）；参数化走执行时裁剪，与 generic
  plan 组合在老版本有坑（36 节）。
- 跨分区 join：enable_partitionwise_join 打开后按对齐分区做逐分区
  小 join——前提两边分区边界完全一致，"对齐分区"本身是纪律。
- DETACH 拿全表锁：等长事务期间写入全堵；14+ DETACH CONCURRENTLY
  两阶段绕开，但存在"仍挂载、拒绝新行"的中间态——应用要能容忍
  这个窗口。

```sql
CREATE TABLE events (tenant_id bigint NOT NULL, ts timestamptz NOT NULL,
                     id bigint NOT NULL, PRIMARY KEY (tenant_id, ts, id))
  PARTITION BY RANGE (ts);
CREATE TABLE events_2025_07 PARTITION OF events
  FOR VALUES FROM ('2025-07-01') TO ('2025-08-01');  -- 到期 detach 归档
```

- 陷阱：分区数过万——pg_class 膨胀、规划时间变长、每分区独立
  加锁、备份工具变慢；粒度按保留策略定（月/季），"越细越好"是
  规划器税。

## 86. 免 rewrite 表改造

- ALTER 成本分两档：纯元数据变更（瞬时，但拿 ACCESS EXCLUSIVE）
  vs 全表 rewrite（锁+I/O+双倍磁盘+全部索引重建）——评审第一问
  永远是"落在哪一档"。
- 免重写白名单：加可空列；11+ 加常量 DEFAULT 列；varchar(n) 放宽
  长度；text↔varchar 互换（同一存储表示）；SET STATISTICS/存储
  参数调整。
- 触发 USING 重写：int4→int8（存储宽度变）、numeric 改 scale、
  timestamp→timestamptz（要时区解释，79 节）、收紧 varchar(n)
  ——千万行表上任何一个都是小时级工程。
- SET NOT NULL 要全表扫：12+ 先加 CHECK (col IS NOT NULL)（秒级）
  再 ADD NOT NULL，规划器借约束跳过扫描——45 节借道手法。
- 大表必须改型的正路：新表+分批回填+触发器同步+短锁重命名交换，
  或逻辑复制蓝绿（6 节）；"等 ALTER 跑完"在千万行表上是祈祷不是
  方案。

```sql
ALTER TABLE orders ADD COLUMN memo text;                   -- 可空：免
ALTER TABLE orders ADD COLUMN status text DEFAULT 'new';  -- 11+：免
ALTER TABLE users ALTER COLUMN name TYPE varchar(255);    -- 放宽：免
```

- 陷阱：免重写≠免锁——ALTER 仍要 ACCESS EXCLUSIVE，被长查询卡住
  时它在锁队列头把后面的 SELECT 全堵死（42 节放大）；DDL 一律
  lock_timeout+重试包裹。

## 87. identity 与 serial 之争

- serial 不是类型是宏：建 int 列+独立序列+DEFAULT nextval——序列
  与列只是默认绑定（OWNED BY），松散到可以解绑、错配、被别的表
  引用。
- identity 把序列内聚进列：DROP 列连坐序列、不可能 int8 列配
  int4 序列、LIKE INCLUDING IDENTITY 语义清晰——新 schema 没理由
  再写 serial。
- 误配经典：手工 INSERT 指定 id 后序列不前进——后续 nextval 撞
  主键；修数后 setval 对齐，序列名用 pg_get_serial_sequence()
  取，别硬编码进脚本。
- GENERATED ALWAYS 显式插 id 要 OVERRIDING SYSTEM VALUE——比 BY
  DEFAULT 更能挡"随手写 id"；迁移与导入工具对两种模式的支持
  差异先在测试库验过。
- 权限尾巴：GRANT 表不自动带序列的 USAGE/UPDATE——应用账号插入
  报 permission denied for sequence；新表授权脚本里序列是显式
  一行（98 节）。

```sql
CREATE TABLE invoices (id bigint GENERATED ALWAYS AS IDENTITY
                       PRIMARY KEY, amount numeric(14,2) NOT NULL);
INSERT INTO invoices (id, amount) OVERRIDING SYSTEM VALUE
  VALUES (1001, 99.99);                        -- 回填显式声明
```

- 陷阱：CREATE TABLE AS 不带走默认值与 identity——复制表后忘建
  identity、忘 RESTART，双段式陷阱一起爆；复制方案进迁移清单
  逐项核对。

## 88. 软删除的查询污染

- 软删除把"删除"变成数据形态：每条业务查询都要 WHERE deleted_at
  IS NULL——漏一处用户就看见已删订单；这不是风格问题，是把正确
  性押在程序员的记性上。
- 唯一约束打架：软删行占坑（同 email 注册被旧软删行挡）——部分
  唯一索引 WHERE deleted_at IS NULL 是标准解；"恢复软删"的路径
  也要设计，别让索引反过来挡恢复。
- FK 语义漂移：子表还引用"已删"父行——数据库层引用完整性完美、
  业务层引用已死对象；级联与约束对软删除一无所知，裂缝靠应用
  纪律补。
- 统计与膨胀税：软删行是常驻死重——n_live_tup 虚高、autovacuum
  阈值被稀释（89 节）、每查询多背一层过滤；"反正不真删"的表
  三年后就是膨胀展览馆。
- 归档分离是正路：DELETE+INSERT INTO archive 按保留策略滚动（或
  分区 detach 直接换库，85 节）——主表只留活数据，软删除降格为
  短窗回收站。

```sql
CREATE UNIQUE INDEX ON users (email) WHERE deleted_at IS NULL;
SELECT * FROM users
 WHERE deleted_at >= now() - interval '30 days';  -- 回收站有界
```

- 陷阱：ORM 全局 default scope 给了"自动带条件"的幻觉——原生
  SQL、BI 工具、运维脚本全绕过它；污染面永远比框架承诺大，按
  "绕过 ORM 的查询"清单审计。

## 89. autovacuum 调优公式

- 触发公式：threshold + scale_factor × n_live_tup——默认 50+0.2：
  千万行表要死 200 万行才轮到，期间索引脏页持续膨胀；大表
  per-table 压 scale（0.01~0.05），比全局改有效。
- insert-only 也逃不掉：13+ 专设 insert 阈值（1000+0.2）让
  append-only 表推进 freeze（91 节）；老版本纯插入表没人管，
  直到 anti-wraparound 兜底那天 I/O 洪峰。
- 预算制 I/O：cost_limit 是全部 worker 共享池（12+ delay 默认
  2ms）——worker 越多每个越慢；SSD 可压 delay 换追赶速度，前提
  是流量余量验证过。
- 长事务是公式外变量：xmin 被 55 节 idle in transaction 卡住，
  vacuum 照跑但死元组不能回收（只 freeze 不删）——先治事务再
  谈参数。
- 证据链：pg_stat_progress_vacuum 看在跑的，last_autovacuum/
  n_dead_tup 看没跑的——"没干活"多数是"干了没效果"，两个根因
  修法完全不同。

```sql
ALTER TABLE hot_events SET (autovacuum_vacuum_scale_factor = 0.01,
                            autovacuum_vacuum_threshold    = 1000,
                            autovacuum_vacuum_cost_delay   = 0);
```

- 陷阱：scale 压太狠+delay 归零=vacuum 常驻循环，I/O 洪峰打业务；
  调参后看整轮时长，目标是"周期性跑完"而不是"永远在跑"。

## 90. 表膨胀诊断与修复

- 判读看 pgstattuple：dead_tuple_percent（死元组）与 free_percent
  （页内空洞）分而治之——前者高是 vacuum 没追上（查长事务/89
  节阈值），后者高是历史膨胀尸体，只能重建回收。
- 阈值：dead >10% 或 free >40% 才值得动手；几百 GB 表重建要双倍
  磁盘+双倍 I/O——"看着不爽就 repack"不是运维是折腾。
- 修复矩阵按锁排：VACUUM FULL=ACCESS EXCLUSIVE 读写全停；CLUSTER
  同锁但按索引重排物理序（27 节 BRIN 相关性顺手修）；pg_repack
  在线（要主键、双倍空间、期间写放大）——生产首选 repack。
- 修因先于修果：不解决 55 节长事务与 31 节非 HOT，repack 完三周
  打回原形；fillfactor 留更新空间促 HOT+拆长事务才是治本。
- 预防性调度：批量任务后手动 VACUUM/ANALYZE（绕开阈值等待）、
  低峰 repack 日历、膨胀趋势进 92 节红灯——三件套齐了才算膨胀
  治理。

```sql
SELECT dead_tuple_percent, free_percent
  FROM pgstattuple('hot_events');          -- dead% 管因，free% 管尸体
VACUUM (VERBOSE, ANALYZE) hot_events;      -- 先试免费方案再谈锁方案
```

- 陷阱：pg_repack 半途失败留触发器与影子表残骸——重跑前清中间
  对象、确认磁盘余量（空间不够时死在 99% 最伤）；失败处置也是
  runbook 的一部分。

## 91. freeze 与 wraparound

- xmin 是 32 位：约 20 亿事务一圈——超圈回绕成"未来事务"不可见，
  所以 PG 在临界前强制 freeze；这是库里唯一"不做就停机"的维护
  项，没有商量余地。
- autovacuum_freeze_max_age（默认 2 亿）触发 anti-wraparound
  vacuum：无视死元组阈值、扫全表、不可跳过——大表上就是 I/O
  洪峰，大促前被它"突然"打挂不是传说。
- 化整为零：低峰手动 VACUUM (FREEZE) 分批把 relfrozenxid 年龄压
  下去，anti-wraparound 永不触发；新版把冻结摊进普通 vacuum（17
  pre-freeze 更进一步），年龄监控仍不可省。
- 监控口径：pg_database.datfrozenxid 与 pg_class.relfrozenxid 的
  age()——1.5 亿开始行动、2 亿红线；监控缺位时第一信号常是
  "autovacuum 疯狂扫一张很干净的表"。
- 真到现场：PG 拒绝分配 xid 进只读保护——恢复要单用户模式手动
  vacuum freeze、全程不可用；年龄必须是 P0 红灯（92 节），赌一次
  就是全网事故。

```sql
SELECT datname, age(datfrozenxid) AS db_age FROM pg_database
 ORDER BY db_age DESC LIMIT 5;
SELECT relname, age(relfrozenxid) FROM pg_class WHERE relkind = 'r'
 ORDER BY 2 DESC LIMIT 10;   -- 大年龄大表=anti-wraparound 候选
```

- 陷阱：低流量的测试/归档库也中招——wraparound 看 xid 计数不看
  业务量，"没人用"恰恰最容易三年没人 vacuum；监控必须覆盖所有
  "不重要"的库。

## 92. 红灯监控指标清单

- 红灯定义：亮了就要人动。七个核心：最老事务年龄（55 节）、复制
  延迟（字节+时间双口径）、slot 保留量（58 节）、WAL 增速突增
  （96 节前置信号）、dead 比例、连接水位、磁盘余量——之外都是
  黄绿灯，别把面板堆成圣诞树。
- 分级可执行：P0 打电话（磁盘 <15%、主备断、只读降级）；P1 工单
  24h（dead>20%、年龄>1.5 亿）；P2 趋势周报——每条告警必须挂
  runbook（96、112 节），没 runbook 的告警等于噪音执照。
- 最老事务是性价比之王：pg_stat_activity 按 xact_start 取最老
  ——一条 SQL 同时抓 idle in transaction 泄漏、遗忘的 psql、
  卡死事务三个事故源。
- 采集自身别成事故：全库聚合每秒打点在千表库有成本，15-60s 采样
  起步；EXPLAIN 级取证留给事件时刻再做。
- 口径统一 UTC（79 节）：监控与日志错 8 小时的团队，复盘第一
  小时全花在"对表"上（108 节）——轴没对齐谈不上根因。

```sql
SELECT (SELECT max(now() - xact_start) FROM pg_stat_activity
         WHERE xact_start IS NOT NULL)            AS oldest_xact,
       (SELECT max(pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn))
          FROM pg_stat_replication)               AS bytes_behind,
       (SELECT count(*) FROM pg_replication_slots
          WHERE NOT active)                       AS stale_slots;
```

- 陷阱：指标齐全但库 hang 时全绿——多数指标依赖 stats 快照，库
  卡住时采集也卡住；外部探针（真连接+超时+简单 SELECT）是最后
  防线，监控自己也要被监控。

## 93. 备份与 PITR

- PITR 组成：基础备份（pg_basebackup）+连续 WAL 归档=恢复到任意
  时刻；归档断一段恢复就停在那——archive_command 的退出码语义
  与幂等重试是可靠性核心（16+ archive_library 同一套契约）。
- 17 增量备份：pg_basebackup --incremental 只备变化块，
  pg_combinebackup 合并增量链成可恢复全量——TB 级库备份窗口从
  小时压到分钟；增量与 manifest 一起版本化，丢一节全链报废。
- 归档节奏=RPO 上限：archive_timeout 不设时空闲库几小时才归档
  一段；设 5-15min、别设秒级（小文件淹没归档存储）——归档 lag
  本身就是红灯（92 节）。
- 恢复目标三件套：recovery_target_time/lsn/xid + inclusive 边界
  + recovery_target_action=pause——pause 挂起先验证一致性再
  promote；直接 promote 恢复过头只能整段重来。
- 演练校验：恢复出的实例跑业务校验查询（关键表对账、抽样）——
  没恢复验证过的备份清单=愿望清单；66 节 RTO 数字从演练计时来。

```sql
restore_command = 'cp /archive/%f %p'
recovery_target_time = '2025-07-15 14:30:00+00'
recovery_target_action = 'pause'   -- 验证后 SELECT pg_wal_replay_resume()
```

- 陷阱：基础备份在从库做、归档在主库——两边 LSN 与时钟对齐没
  验证过，演练时目标点落在备份开始前直接失败；混搭拓扑的恢复
  流程必须专项演练。

## 94. pg_upgrade 与回滚窗口

- 两种模式：拷贝模式（耗时≈数据量）与 --link（新旧集群硬链接
  同一批数据文件，分钟级完成）——大库必选 link，但要求同机同
  文件系统、窗口纪律更严。
- 回滚窗口真实含义：旧集群目录+旧二进制原封不动，切换失败把
  流量打回旧端口；窗口关闭（新集群已有写入）后旧集群作废——
  升级要当项目排期，不是当命令跑。
- --link 窗口铁律：两集群共享同一批 inode——误启动旧集群写入=
  两边同时污染、回滚位报废；"停新集群→起旧集群"的回滚顺序写死
  runbook，窗口期禁止两集群同跑。
- 统计分水岭：18 起统计随 pg_upgrade 一起迁，切流即正常性能；
  18 前升级后统计全空，头半小时全是灾难计划——vacuumdb
  --analyze-in-stages 在切流前跑完是硬步骤。
- --check 干跑先验硬伤（扩展两集群版本齐、locale 兼容、磁盘
  够）；--jobs 并行重建索引数倍提速但吃 I/O——备机演练计时，
  分钟数写进变更单（66 节）。

```sql
pg_upgrade --link --jobs 8 --check    -- 干跑全绿才继续
pg_upgrade --link --jobs 8            -- 旧集群目录原样=回滚位
vacuumdb --all --analyze-in-stages    -- 18 前的必做项
```

- 陷阱：升级失败现场乱删新集群目录——link 模式半途失败时清理
  要按官方流程，误删会连旧集群一起带走；失败处置也是演练内容。

## 95. 连接风暴治理

- max_connections 高位的真实代价：每连接一个后端进程（数 MB RSS+
  内核开销）、上下文切换、内存峰值=连接数×每进程峰值——1000
  连接的库，光进程基数就挤爆突发查询的 work_mem 预算（51 节）。
- 风暴叠加结构：抖动→客户端超时重试→池扩容+新建连接齐发→库更
  慢→更多重试——指数退避+jitter+重试预算（41 节）必须在客户端
  层就位，运维层救不了。
- 治理三板斧：max_connections 压到两位数~一百出头；池化前移到
  应用侧（47、49 节）；per-role CONNECTION LIMIT 给租户级硬顶
  ——上限语义进架构文档，不是 DBA 单方面"调小试试"。
- 保命通道：superuser_reserved_connections 专留运维——风暴时
  应用吃光连接，DBA 连不上就只能重启；这个参数是"你还能力挽
  狂澜"的保险，永远别为省内存砍它。
- 先分诊再用药：pg_stat_activity 按 state/wait_event 分组——
  几千 idle in transaction 是事务泄漏（55 节）不是连接不够；扩池
  只会让死得更体面。

```sql
SELECT state, wait_event_type, count(*)
  FROM pg_stat_activity WHERE backend_type = 'client backend'
 GROUP BY 1, 2 ORDER BY 3 DESC;
ALTER ROLE app_ro CONNECTION LIMIT 50;   -- 租户硬顶
```

- 陷阱：K8s/网关的 Liveness 探针每秒新建连接——探针本身就是小型
  连接风暴；探针走长连接池或本地探活，把它从连接预算里赶出去。

## 96. 磁盘满与 WAL 堆积应急

- 优先级铁律：磁盘将满时第一动作是"止血保库"不是"恢复业务"
  ——PG 写 WAL 失败宁可停机保护也不冒险，走到那步 RTO 直接
  翻倍；剩 15% 就该行动（92 节），剩 5% 已经在救火。
- WAL 堆积归因两选一：归档卡（archive_command 目标端满/网络断，
  pg_stat_archiver.failed_count）或槽卡（订阅停摆，58 节）——
  先归因再动手，两种处置完全不同。
- 槽卡决策树：业务确认已弃→按 58 节次序解绑删槽；订阅还活着→
  max_slot_wal_keep_size 兜底、腾归档旧文件、抢修订阅端；"先删
  了再说"在业务确认前是禁区——删槽=数据断流重同步。
- 临时文件也是嫌疑人：大查询溢出（51 节）瞬间吃几十 GB——
  temp_file_limit 设上限、log_temp_files 开着；定位到语句用
  pg_terminate_backend 现杀止血。
- 只读降级三件套按序：default_transaction_read_only=on 保查询
  业务→清可删文件（归档副本/临时文件）→pg_switch_wal() 买时间
  ——顺序与回滚路径要演练过（66 节）。

```sql
SELECT last_failed_wal, last_failed_time FROM pg_stat_archiver;
SELECT slot_name, wal_status FROM pg_replication_slots;
SELECT pg_size_pretty(sum(size)) FROM pg_ls_waldir();  -- pg_wal 现场
```

- 陷阱：手删 pg_wal 里的文件腾空间——PG 可能正持有它，删完库
  崩；WAL 手工删除只有"库已停止且不再启动"一种合法场景，腾
  空间走归档副本/槽/临时文件三条正路。

## 97. RLS 行级安全分层

- 语义基线：ENABLE ROW LEVEL SECURITY 后无策略=全拒绝；policy
  分 USING（读过滤）与 WITH CHECK（写校验）；表 owner 与
  BYPASSRLS 默认绕过——要连 owner 一起管就 FORCE RLS。
- 组合规则是事故高发区：多条 PERMISSIVE 策略 OR 合并、
  RESTRICTIVE 对全体 AND 收紧——"加一条结果全都能看/全都不能
  看"都是没画真值表；上 RESTRICTIVE 前先在测试库把组合跑一遍。
- 租户注入模式：current_setting('app.tenant_id')——每个事务开头
  SET LOCAL（transaction 池化下会话级 SET 会串户，47 节）；漏
  注入时策略对 NULL 显式拒绝并配告警，宁全拒不漏数据。
- 性能真相：策略谓词就是查询条件——它需要索引；current_setting
  非规划期常量，裁剪不狠；EXPLAIN 里 policy 谓词落在 Filter 逐行
  算就是事故现场（10 节取证）。
- 视图叠加（78 节）：definer 视图以 owner 身份绕过调用者的 RLS
  ——多层视图+RLS 必须用 security_invoker 重新过权限与策略，
  15+ 的强依赖不是可选优化。

```sql
CREATE POLICY tenant_rw ON orders
  USING (tenant_id = current_setting('app.tenant_id')::bigint)
  WITH CHECK (tenant_id = current_setting('app.tenant_id')::bigint);
```

- 陷阱：WITH CHECK 缺省回退 USING——"读得到≠许写入"的场景
  （管理端读全租户、只写本租户）被悄悄合并；显式写两份，给未来
  的自己留注释。

## 98. 最小权限与审计

- 角色分层是地基：app（DML 限 schema）、migration（DDL 跑完
  收回）、只读报表、superuser 只留两人+堡垒机——日常拿
  superuser 连库的组织，审计与最小权限都免谈。
- default privileges 堵漏：ALTER DEFAULT PRIVILEGES IN SCHEMA
  app GRANT ...——新表自动授权；不设这条每张新表都要手工
  grant，漏一张报表就 403 一晚。
- PUBLIC 权限历史坑：15 起 public schema 不再对 PUBLIC 开放
  CREATE——旧版升级的应用忽然"建不了表"；public 角色与函数
  PUBLIC EXECUTE 季度巡检，历史遗留不会自己消失。
- pgaudit 开销与采样：全量审计让日志与 I/O 翻倍；生产策略=
  敏感表全审计+其余采样（慢查询+DDL）+pg_stat_statements 兜底
  ——平衡点按合规要求定，并写进合规文档。
- 提权面巡检：SECURITY DEFINER 函数（owner 是超级用户的逐个过
  目）、dblink/COPY PROGRAM/文件类扩展、schema CREATE 权限——
  每个都是 app 账号翻墙的梯子（107 节季度清单）。

```sql
GRANT USAGE ON SCHEMA app TO app_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA app
  GRANT SELECT ON TABLES TO app_ro;      -- 新表自动跟
REVOKE ALL ON SCHEMA app FROM PUBLIC;   -- 15+ 已收紧，显式再钉
```

- 陷阱：密码与权限直接绑"人"角色——角色按服务/用途建模，人的
  身份用组成员表达；离职回收=移出组，而不是挨表撤权、挨库改
  密码。

## 99. 隐式 cast 杀索引

- 诊断特征一眼定案：索引列的比较从 Index Cond 退化成 Filter
  （旁边多一个 cast）——索引还在就是用不上；性能当场掉一个
  数量级，而且不报错。
- PG 的幸运与不幸：text 列 = 数字直接报错（响亮的失败是幸运）；
  numeric 列配 float8 参数、timestamp 配错时区类型走静默跨型
  路径——numeric 被拉进 float8 那一刻索引绝缘（82 节同源）。
- 驱动层是高发源头：JDBC/ORM 参数类型与 schema 声明对不上
  （setFloat 发给 numeric、setString 发给 int）——prepared
  statement 参数类型逐列核对是巡检项不是运气项。
- MySQL 暗雷对照：字符串列 = 整数把两边转 double 全表扫、连报警
  都没有；不同校对的列 join 还叠加索引失效（109 节）——MySQL
  查询迁 PG 先过一遍这层语义差异。
- collation 错配在 PG 同样致命：不同 collation 的表达式相等比较
  与函数索引/唯一约束对不上号——同库多 collation 只该出现在
  明确设计过的场景，否则统一到一种。

```sql
EXPLAIN SELECT * FROM ledger
 WHERE account_no = $1::numeric;  -- 列是 text：cast 后 Filter 全扫
EXPLAIN SELECT * FROM ledger
 WHERE account_no = $1::text;     -- 类型对齐：Index Cond 直达
```

- 陷阱：EXPLAIN 正常、生产爆慢——参数化语句走 generic plan（36
  节），"字面量能折叠的 cast"留在运行时；EXPLAIN (GENERIC_PLAN)
  （17+）或带真参数的 auto_explain 取证才作数。

## 100. 函数包裹谓词

- 第一家族：date(ts)='2025-07-01'、year(ts)、extract(...)——函数
  压在列上，索引序被破坏，全扫起步；等价半开区间改写保留索引
  序，还顺手消灭 23:59:59 边界遗漏。
- 第二家族：lower(email)=...、regexp_replace(...)=...——改
  citext/写入归一化（80 节），或配表达式索引；索引谓词与查询
  逐字匹配（24 节），写法漂移就静默失效。
- now()/current_date 是安全的：不包裹列、语句早期求值一次就能
  进索引条件——危险永远是"列在函数里"，判断标准就这一条，别
  把时间函数一棍子打死。
- STABLE 函数裹列的隐性税：即使配了表达式索引，统计传递也断
  ——泛型 STABLE 函数选择性全靠猜，误估连锁（12 节）从这里起。
- 评审纪律：对每个 WHERE 里的"列侧函数"问一句能不能移到常量侧
  或半开区间化——这条 checklist 一分钟，全扫到索引的差距经常
  就是它（107 节清单第 1 条）。

```sql
SELECT * FROM events WHERE date(created_at) = '2025-07-01';  -- 全扫
SELECT * FROM events                                       -- 半开区间
 WHERE created_at >= '2025-07-01 00:00+08'::timestamptz
   AND created_at <  '2025-07-02 00:00+08'::timestamptz;
```

- 陷阱：表达式索引"配对了但没用上"——查询写 lower(email)、索引
  建在 lower(email::citext)（80 节类型家族不同）：外表一致、内部
  类型不同照样全扫；计划取证（99 节）是唯一验收。

## 101. SELECT * 与 TOAST 成本

- 宽表 SELECT * 的隐性 I/O：TOAST 列挤在外部表——只要 status 也
  要把大 JSON 整段解压进缓冲再传输；Buffer 读数是窄查询的几十
  倍（17 节读法），这不是 ORM 能自动修的。
- index-only scan 的死敌：SELECT * 迫使每行回表（可见性映射救不
  了全列选择）；指定列后窄覆盖索引（22 节）才有意义——同一个
  查询只差列清单，计划差一个量级是常态。
- 视图叠加的边界：简单视图链的列裁剪通常仍生效（规划器按需取
  列）；链上一旦出现聚合/窗口/DISTINCT（67、72 节），剪裁与
  下推一起断——代价按"断点数"估，不是按层数。
- ORM 默认全列：findMany 默认映射全部列——宽表+高频列表接口里，
  显式 select 是能排进前三的性能改动；再叠 69 节深分页，TOAST
  放大成双倍灾难。
- 网络与序列化税：列越宽，协议编码、反序列化、GC 三处同时膨胀
  ——DBA 看到的慢只是税链第一段，接口 P99 才是全貌（102 节
  口径别只看库内时间）。

```sql
SELECT * FROM orders WHERE status = 'paid' LIMIT 100;
SELECT order_id, amount, created_at            -- 列清单是设计产物
  FROM orders WHERE status = 'paid' LIMIT 100;
```

- 陷阱：把"子查询里的 SELECT * 也全扫"当教条——EXISTS 只判存在
  性、优化器不取列；要管的是对外输出的列清单；教条与证据（17
  节 BUFFERS）冲突时听证据。

## 102. COUNT(*) 成本与近似计数

- 精确计数的物理价格：COUNT(*) 要对每行做可见性判断——全表扫或
  index-only（窄索引+干净可见性映射），千万行表秒级起步；没有
  优化能把精确数变免费，只有换口径。
- reltuples 近似：pg_class 的规划器估值，误差随统计新鲜度漂移
  （±10% 常见）——管理后台"约 12.3 万条"这类展示位够用；分页
  控件、审计对账不配用它。
- 计数表：触发器维护聚合行（每租户一行）——O(1) 读换写放大+
  热点行串行化；高并发计数分桶（拆 N 行读时 sum）是老 trick，
  84 节热点问题的反向应用。
- 分页总数是需求错误：深页用户不需要精确总数（69 节下"第 87 页"
  不该存在）——"下一页"用 LIMIT n+1 探测、首屏总数用近似值；
  这两个改动通常消掉库上一半的 COUNT。
- 真要精确+高频：物化计数（75 节）+定时校准兜漂移；校准任务跑
  只读副本/低峰，别和业务查询抢 I/O。

```sql
SELECT reltuples::bigint FROM pg_class WHERE relname = 'orders';
SELECT order_id FROM orders
 WHERE (created_at, order_id) > (:last_ts, :last_id)
 ORDER BY created_at, order_id LIMIT 21;  -- 21=20+1 探下一页
```

- 陷阱：把 COUNT(*) 写进 API 契约承诺毫秒级——"精确+实时+快"
  三角物理上注定违约；这场谈判发生在需求评审，不在 EXPLAIN
  现场。

## 103. OR 与 UNION ALL 计划差异

- 同列 OR 自带救星：a IN (1,2,3)、= ANY(数组)、a=1 OR a=2 在
  规划器里是同一形状（ScalarArrayOp）——照样走索引，别急着手
  改 IN；真正的退化发生在跨列 OR。
- 跨列 OR：a=1 OR b=2 两列各有索引→BitmapOr 两路位图合并；任一
  侧无索引→整条退化 Filter 全扫——OR 的性能取决于最弱的那半
  边，先补弱侧索引再谈改写。
- UNION ALL 拆分术：两个子查询各走各的索引再拼——代价是两次
  扫描+语义差异：UNION ALL 不去重（两支都命中的行出现两次）、
  UNION 去重要 sort/hash 消掉差异。
- 拆分必须补互斥：第二支加 AND status <> 'paid'——把"OR 语义=
  不重复"显式编码；漏了这半句，报表虚高且错得悄无声息。
- 现场判读：计划里 BitmapOr=两路索引都在干活；Filter+全扫的 OR
  先查 cast（99 节）再补弱侧索引，最后才轮到 UNION ALL 改写。

```sql
EXPLAIN SELECT * FROM orders
 WHERE status = 'paid' OR amount > 1000;  -- 看 BitmapOr 还是全扫
SELECT * FROM orders WHERE status = 'paid'
UNION ALL
SELECT * FROM orders WHERE amount > 1000 AND status <> 'paid';
```

- 陷阱：IN 子查询与 OR 枚举混为一谈——"值在另一表里"走 EXISTS/
  IN 的 semi-join 优化远好于 OR 枚举（53 节）；值清单一长就是
  量级差。

## 104. 动态 SQL 与注入防线

- 字面量防线只有一条正路：format %L / quote_literal——但 quote_
  literal(NULL) 返回 NULL，拼接后整个条件静默消失；format %L 对
  NULL 输出裸 NULL 关键字，语义安全——手写 COALESCE 版全部替换。
- 标识符注入更隐蔽：%I/quote_ident 防语法破坏，防不了语义注入
  ——用户传 "orders" 就查了别的表：动态表名/列名必须白名单映射，
  用户输入永不直通标识符位置。
- 应用侧高危位巡检：ORM 的 order()/orderBy 参数（用户排序字段
  直通 ORDER BY 是经典事故）、动态 GROUP BY、报表列选择器——
  注入不止在 WHERE；拼接点 grep 是季度审计项（107 节）。
- 注入的第二灾难是资源耗尽：恒真条件后的全表输出、巨大 IN 列表、
  LIKE '%x%' 前导通配——参数化+输入长度/格式校验把两段都堵上，
  别只防"改库"不防"拖死库"。
- 高危函数面：dblink/exec/COPY PROGRAM/文件读写扩展——注入借
  它们直接升级 RCE；应用账号函数执行权限按白名单收（98 节），
  高危函数默认 REVOKE。

```sql
SELECT CASE :sort_input WHEN 'created' THEN 'created_at'
                        WHEN 'amount'  THEN 'amount'
                        ELSE 'created_at' END AS sort_col;  -- 白名单
-- 映射结果再进 format(%I, sort_col)：两道闸缺一不可
```

- 陷阱：信了"用 %I 就安全"——引号只是语法疫苗；标识符合法但
  越权（传 system 表名）照样穿透；白名单是唯一完备解，引号是
  第二层防御不是第一层。

## 105. EAV 反模式

- EAV 结构税：取一个实体的 N 个属性=N 路自 join 或聚合旋转，
  实体查询从千行膨胀成千万行属性扫描；类型伪装 text 后 CHECK、
  外键、统计全部失效——"灵活"在按月付息。
- 查询税具象：多属性 AND 过滤=每属性一个子查询+去重；报表旋转
  要 GROUP BY 重塑——每次报表需求都重付"把关系拼回来"的钱，
  这税随属性数复利增长。
- jsonb 行模型是现代答案：单行整对象+GIN（73 节）——插入一次
  成型、@> 包含查询、关键位 CHECK 表达校验；代价是无列级外键、
  统计靠表达式索引养。
- 混合模型是工程解：热门属性提升为真列（类型/约束/索引），长尾
  留 jsonb——毕业判据=它开始出现在 WHERE/JOIN/索引需求里；
  毕业动作=86 节免重写改造+回填。
- EAV/jsonb 何时是对的：属性真稀疏、查询模式真未知（医疗记录、
  商品动态属性）、消费端是文档/搜索型——但要写进架构文档"这是
  选择不是堕落"，否则三年后没人记得边界。

```sql
CREATE TABLE products (
  sku text PRIMARY KEY,
  price numeric(14,2) NOT NULL,          -- 毕业列：约束+索引
  category_id int REFERENCES categories(id),
  attrs jsonb NOT NULL DEFAULT '{}');    -- 长尾：GIN jsonb_path_ops
```

- 陷阱：把 EAV 迁成 jsonb 只换存储没换查询——应用层仍在循环拼
  属性；迁移完成标志是查询模式一起重写（@> 过滤+表达式索引），
  只搬数据是最贵的原地踏步。

## 106. 视图滥用与计划穿透

- 视图金字塔的病变：v5 引 v4 引 v3，EXPLAIN 打出几百行没人能读
  ——计划穿透失能不是玄学：每层都可能是下推断点，MATERIALIZED
  CTE（37 节）、窗口（67 节）、DISTINCT ON（72 节）都挡外层谓词。
- 解剖法：从最外层向下逐层 inline 对比 EXPLAIN，二分定位丢下推
  的那层——"展开后快 100 倍"的结论常落在某个中间层的 GROUP BY
  边界上，证据说话（10 节）。
- ownership 陷阱：视图按 owner 权限执行（78 节）——建视图的人
  离职/权限回收，整条报表链瘫痪；owner 建模成服务角色（98 节），
  人只是角色的成员。
- security_invoker（15+）修权限语义，顺带修 RLS 联动（97 节）
  ——切换前给调用者补基表权限；"视图能查"和"调用者能查"之间
  的豁口逐表核过。
- 视图是 API 就按 API 管：列名、类型、顺序是对外契约——改视图
  走评审（107 节），给下游留缓冲（新列可空、版本号），比每次
  "顺手改一下"便宜一个量级。

```sql
EXPLAIN ANALYZE SELECT * FROM v_orders WHERE tenant_id = 42;
EXPLAIN ANALYZE
  SELECT ... FROM t1 JOIN t2 ... WHERE tenant_id = 42;  -- 展开体对比
```

- 陷阱：热视图叠三层没人敢动——建"冻结基线"（计划快照+关键查询
  回归集）之前，视图层的每次顺手改都是生产事故彩票；基线是
  视图治理的前置投资。

## 107. SQL 评审清单

- 索引形状三问：谓词形状与索引列序一致吗（21 节）？写入放大预算
  过了吗（31 节每索引每行一写）？有没有"为一个查询建一个索引"
  的零散堆积——第三问的裁剪结论写进评审记录。
- 锁与超时必填项：DDL 有 lock_timeout+重试包裹（42 节）；长 DML
  拆批（行数上限+批间提交，54 节）；statement_timeout 按事务
  显式设，不靠全局默认赌运气。
- 事务边界三查：think-time 出事务了吗（55 节）；循环逐条提交还是
  整块巨事务——两个极端都要给出理由；失败路径的外部副作用
  （消息、缓存、锁）谁负责回滚。
- 取证附件制：新查询带 EXPLAIN (ANALYZE, BUFFERS)（10 节）、
  改写带前后计划对比——没有计划的评审是品味讨论；附件不齐直接
  打回，不口头补。
- 回滚路径是硬要求：迁移有 down 且在本环境真实执行过（77 节
  中间态也要能停住）；发布顺序（先 DB 后代码）与回滚顺序写进
  变更单——不能回滚的变更要升级审批层级。

```sql
-- 评审模板落成 CI 断言比落成 wiki 有效
-- [ ] EXPLAIN (ANALYZE, BUFFERS) 附件：rows 估实偏差 < 10 倍
-- [ ] 锁面：新语句/DDL 的锁等级与持有时长过 42 节矩阵
-- [ ] 回滚：down 迁移与回滚顺序在本环境执行过（66 节）
```

- 陷阱：清单越加越长最后没人读——每条必须可执行可验收（能贴
  命令输出）；"注意性能"这类条目要么改成断言要么删掉，清单的
  质量=它的可判定量。

## 108. 事故复盘方法论

- 四步骨架：症状（告警原文+影响面）→取证（动手前抓 EXPLAIN、
  pg_stat_*、日志、锁快照）→根因（5 Why 追到流程层，停在"人为
  失误"等于没做）→固化（参数/告警/清单/KB 至少产出一条，否则
  这次疼白疼）。
- 现场保留纪律：第一动作 pg_stat_activity 快照、auto_explain
  日志打包——restart、VACUUM FULL、kill 都会毁证据；止血与取证
  争夺同一分钟时，先抓快照再止血，一条 SQL 的事。
- 时间线重建：日志、WAL 归档、监控、变更单全对到 UTC 一轴（79
  节）——"告警 14:02 但变更单写 22:00 完成"的轴几乎都是时区
  混用；先校轴再谈因果。
- 症状≠根因的判据：修完"那个慢查询"后同类事故还在长出来=修的
  是症状；根因层在"为什么没告警"（92 节缺位）、"为什么评审没
  拦住"（107 节漏项）这类流程位点上。
- 复盘产出进 KB：每个事故沉淀一条可引用的 KB 节、一条告警或
  一条 checklist——本库很多"陷阱"就是真实事故的缩写；复盘结束
  的标志是产出物合并，不是散会。

```sql
SELECT now(), pid, state, wait_event_type, wait_event,
       now() - xact_start AS xact_age, query
  FROM pg_stat_activity ORDER BY xact_age DESC NULLS LAST
\g incident_snapshot.txt   -- 止血前抓快照留档，别只在屏幕上看过
```

- 陷阱：复盘变追责会——一旦"谁的锅"成为议题，下次事故的第一
  动作就从"保留现场"变成"毁灭现场"；blameless 不是温柔，是
  保证取证链完整的工程纪律。

## 109. MySQL 专家差异清单

- 隔离层第一差异：InnoDB 默认 RR+next-key/gap lock——范围扫描连
  "间隙"一起锁，并发插入的死锁形态与 PG 完全不同；高并发主动降
  RC 消 gap lock 是 MySQL 侧常规武器而非异端。
- 8.4 默认值转向：innodb_change_buffering=none（SSD 上从优化变
  负担）、mysql_native_password 默认关闭（老驱动连不上是升级
  第一事故）——LTS 不等于"无变化"。
- 校对规则深水：utf8mb4_0900_ai_ci 默认大小写+口音双不敏感——
  与 PG collation 行为对不齐；emoji 必须 mb4；不同校对的列 join
  还隐式转换杀索引（99 节暗雷的 MySQL 原产地）。
- 取证工具对应表：EXPLAIN FORMAT=TREE≈PG 文本计划（8.0.18+ 才能
  看清 hash join）；performance_schema 的 digest 聚合≈pg_stat_
  statements（56 节方法论可平移）；sys schema 是快捷方式不是
  真相源。
- 复制与拓扑：GTID 让切换可推演（创新版 GTID Tags 给来源打标）；
  半同步与 PG 同步档（60 节）语义近似但参数面完全不同——对照表
  维护成文档，凭记忆操作必串味。

```sql
SELECT SCHEMA_NAME, DIGEST_TEXT, COUNT_STAR,
       SUM_TIMER_WAIT/1e12 AS total_sec
  FROM performance_schema.events_statements_summary_by_digest
 ORDER BY SUM_TIMER_WAIT DESC LIMIT 20;
```

- 陷阱：双栈团队互换调优经验——random_page_cost=1.1 在 MySQL 没有
  对应物，innodb_buffer_pool_size 不能映射成 shared_buffers 的
  百分比；对照按"问题域"对齐（111 节），不按参数名对齐。

## 110. SQLite 生产深水

- 锁模型：单写者——busy_timeout 默认 0，写冲突立即 SQLITE_BUSY；
  生产三件套：busy_timeout=5000、journal_mode=WAL（读写不互斥）、
  synchronous=NORMAL——NORMAL 断电只可能丢最后几个未 checkpoint
  的提交、不损坏库，金融级再回 FULL，明码标价再选。
- checkpoint 饥饿：WAL 要等所有读者越过才能 checkpoint——一条长
  读事务（连接泄漏、忘 finalize）能让 -wal 无限膨胀；WAL 尺寸
  就是读事务泄漏的温度计，超几十 MB 先查连接生命周期。
- 网络盘禁忌：锁建立在 POSIX advisory lock+本地 fsync 契约上，
  NFS/SMB 语义不达标=静默损坏；官方明文只担保本地文件系统
  ——"库文件放网盘共享"是损坏事故的标准开场白。
- 空间回收：DELETE 只把页放 freelist 文件不缩小；VACUUM 重建整
  库（临时双倍空间）；auto_vacuum=INCREMENTAL+incremental_vacuum
  是温和在线选项——大批清理后空间要么计划回收要么计划性忽略。
- 统计不自动：优化器只用 sqlite_stat1/4（ANALYZE 手跑的产物）
  ——schema 变更或分布大变后不重跑，计划就漂移；发版流水线把
  PRAGMA optimize 固化在迁移末尾。

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA busy_timeout = 5000;      -- 生产三连，缺一就等 BUSY 报错
PRAGMA wal_checkpoint(PASSIVE);  -- WAL 尺寸=读事务泄漏温度计
```

- 陷阱：多进程高并发写拿 SQLite 当服务库——写串行决定它撑不起
  多写者；判据不是"能不能跑"而是写锁队列 P99，超边界就换 PG
  （111 节选型表），别堆 retry 硬扛。

## 111. 跨库方言策略

- 抽象层定价规律：ORM 的"数据库无关"只覆盖最大公约数——窗口、
  CTE 优化、RETURNING、partial index 都在公约数之外，躲开它们的
  查询自动变慢变笨；真实成本是查询能力降级，不是多写适配类。
- 取舍骨架：SQL 为主——迁移与复杂查询手写原生 SQL 按库分文件，
  ORM 只做行映射不做查询构造；"一套代码处处跑"在性能敏感系统
  里是负债不是资产，早点承认早点省钱。
- 能力差异表驱动选型：upsert（ON CONFLICT vs ON DUPLICATE KEY）、
  RETURNING（MySQL 无，要 last_insert_id 补一跳）、窗口/CTE 版本
  矩阵、SKIP LOCKED——表格维护在仓库里，建模期决策不是开发
  中期才发现。
- 集成测试按库分层：每支持一个库就上真库容器（testcontainers）；
  用 H2"模拟 PG"是自欺——锁、类型、collation、99 节隐式 cast
  的差异全缺席，上线后才补课。
- 迁移期双跑与基线：方言分叉后各自跑慢查询基线（56 节 pgss 与
  109 节 digest 互为镜像）——方言间性能漂移要靠各自基线抓，
  统一抽象层只会把它遮住。

```sql
-- db/pg/upsert.sql：带 RETURNING 一次往返
INSERT INTO counters(k, n) VALUES ($1, 1)
ON CONFLICT (k) DO UPDATE SET n = counters.n + 1 RETURNING n;
-- db/mysql/upsert.sql：无 RETURNING，要 last_insert_id 补一跳
```

- 陷阱：方言分叉后"顺手对齐"——把 PG 独有能力改写成通用慢写法
  求跨库一致，等于给所有库降级；正解是能力表登记+按库最优路径，
  差异是记录在案的设计，不是要消灭的 bug。

## 112. 紧急处置速查表

- 顺序铁律：先保监控与接入通道（92 节外部探针、保命连接）→止血
  →取证（108 节，一条 SQL 快照先抓）→根因——顺序错了就是二次
  事故：restart 毁证据、盲操作造新故障，"救援动作伤人"的历史
  比例高得惊人。
- 只读锁死（磁盘将满/关键表损坏）：SET default_transaction_read_
  only=on 并确认生效路径，按角色保留运维写通道；长事务按
  xact_age 排队逐个 pg_terminate_backend，一批一记录。
- 连接耗尽：pgbouncer admin 看 clients 队列→idle in transaction
  先杀事务（55 节）→per-role limit 兜底（95 节）；应用侧熔断比
  扩池有效——扩池是给库加压，这个方向别搞反。
- WAL 满与磁盘告急：96 节决策树——pg_stat_archiver 判归档卡、
  pg_replication_slots 判槽卡；槽的处置要业务点头，删 pg_wal
  文件永远禁手；pg_switch_wal() 只买时间不治病。
- 本表是索引不是剧本：每条红线挂 runbook（92 节告警、96 节决策
  树、66 节演练输出）；演练没跑过的 runbook 等于没有——夜里
  三点走得下去的处置面是演练出来的不是写出来的。

```sql
SELECT state, wait_event_type, wait_event, count(*)
  FROM pg_stat_activity GROUP BY 1, 2, 3 ORDER BY 4 DESC;
SELECT slot_name, wal_status FROM pg_replication_slots;   -- 58 节
SELECT pg_size_pretty(sum(size)) FROM pg_ls_waldir();      -- 96 节
```

- 陷阱：应急工具本身没演练——保命连接被砍、只读开关参数写错、
  监控探针先死：处置面的每一件工具都进 66 节演练清单；事故现场
  不是验证工具的实验室。
