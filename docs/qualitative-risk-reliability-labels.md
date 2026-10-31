# 风险/可靠性定性档位 — 特性文档

> 2026-10 用户反馈（#365）：「除了下载进度这类必要百分比，其他评分
> 没啥用；风险等级应该用危险、平衡、无危险等等」。结论：百分比/
> 数字等级是**假精确**——「87% vs 91%」在用户决策上没有差别，
> 定性档位词可以直接读到「该不该放行 / 敢不敢交给 Agent」。

## 一、设计口径

### 两类数字的边界（刻意保留 vs 替换）

| 类别 | 判定 | 例子 | 处理 |
|---|---|---|---|
| **进度型** | 实时状态，随时间推进到终值 | 下载进度、Step x/y 任务进度、上下文水位（used/max） | **保留百分比**（ContextMeterBar / TaskStatusCard 原样不动） |
| **评分型** | 对历史聚合的「打分」 | 成功率 N%、质量/风险等级数字 | **替换为定性档位** |

### 风险三档（工具/命令/Spec/MCP 目录统一）

| 旧口径 | 新口径（定性） | 语义 |
|---|---|---|
| 低风险 / LOW | **无危险** | 只读或纯计算，永不询问 |
| 中风险 / MEDIUM | **平衡** | 会改数据/状态，有用但非破坏性 |
| 高风险 / HIGH | **危险** | 破坏性/不可逆/全局影响，首次执行必确认 |
| CRITICAL（仅 Spec 卡） | **极度危险** | 文案与图标区分，颜色仍走 error token |

### 可靠性三档（成功率投影，阈值与旧配色一致）

| 成功率 | 档位 | 语义色 |
|---|---|---|
| ≥90% | **稳定** | primary |
| ≥50%（且 <90%） | **一般** | tertiary |
| >0 且 <50%（有调用） | **不稳定** | error |
| 0 次调用 | **未使用** | onSurfaceVariant（「—」） |

数据层不动：`ToolUsageTracker.successRate`、`MacroHealth.successRate`、
`SkillDetailUiState.successRate` 等字段**保留精确数值**（供 LLM/日志/
导出用），只换 UI 展示层。

## 二、改动分层

### core 层（已过 JVM 编译 + 1216 测试）

1. **`ToolMetadata.kt`（tool-registry）**：`ToolRisk` 枚举 label
   低/中/高 → 无危险/平衡/危险。`summary()` 日志、目录工具
   （ToolCatalogTools）、执行门提示全部自动跟随。
2. **`ToolUsageTracker.kt`**：summary() 的「(成功率 N%)」→
   「(可靠性：稳定/一般/不稳定)」——与 UI 层同阈值（≥90/≥50）。
3. **`CommandPermissionGate.kt`（agent-engine）**：命令确认弹窗标题
   「高风险命令需要确认」→「危险命令需要确认」；同步更新
   CommandPermissionGateTest 断言。

### app 层（弹窗 + 六屏 UI）

4. **RiskAwareToolGate / GateDialogStrings（DefaultCn）**：风险门
   AgentQuestion 文案 ——「危险：破坏性或不可逆操作 / 平衡：会修改
   数据或状态 / 无危险」；标题「危险工具需要确认」。
5. **strings_core（zh/en 镜像）**：`gate_risk_*` 三条 + 新增
   `risk_label_dangerous/balanced/safe` 通用档位词。
6. **AgentChatPlanCards（Spec 卡）**：旧实现拼 `riskLevel.name`（英文
   枚举名，中文用户看到「风险 LOW」）；新增 `riskLevelLabel()`
   → 无危险/平衡/危险/极度危险（`chat_risk_*` 四条双语）。
7. **ToolkitRingButton（工具菜单）**：孤立「⚠」符号 → 「危险」文字
   徽标（error 12% 底 + 4dp 圆角），新用户可直读。
8. **MCP 市场**：目录风险徽标 High/Medium/Low risk → Dangerous/
   Balanced/No danger；env 表单警示「高风险条目」→「危险条目」；
   McpHost 分类警示「已启用高风险分类」→「已启用危险分类」。
9. **可靠性组件（新增 `ui/component/ReliabilityTier.kt`）**：
   `ReliabilityTier` 枚举 + `reliabilityTierOf()` 投影函数
   （Double/Float 双版本），@Composable label() 经 stringResource
   双语取词。市场/记忆/长任务三屏共用，阈值单一事实源。
10. **市场屏**：`MarketSuccessRateChip`（卡片角标）、技能详情
    StatChip（宏/总计）、`ToolStatRow`（逐工具行）——全部「N%」→
    档位词；指标名「成功率」→「可靠性」（en 同步 Reliability）。
11. **记忆屏 MacroCard**：宏技能卡新增可靠性徽标（保留成功/失败
    计数——事实数据不删，删的是评分）。
12. **长任务档位效能表**：`成功率 %1$d%%` → `可靠性 %1$s`
    （code_reliability_* 三档双语）。

## 三、验证

| 项 | 结果 |
|---|---|
| `compile_core_jvm.sh`（logging→llm→tools→engine） | ✅ 1301 classes |
| `run_core_tests_jvm.sh`（全量 JUnit4） | ✅ OK (1216 tests)（含更新后的 CommandPermissionGateTest 断言） |
| `check_string_mirror.py` | ✅ 23 文件 / 2208 键中英 1:1 |
| `kotlin_balance.py`（12 个改动/新增 kt） | ✅ 平衡 |
| `check_file_size.sh` / `check_code_quality.sh` / `check_comment_integrity.py` | ✅ 全过 |
| app 层 Compose 编译 | 交由 CI（本 PR 触发 pr workflow：静态分析 + :app:compileDebugKotlin + 9 步测试） |

## 四、刻意不动的数字（边界澄清）

- **下载进度**（app 下载安装工具、rootfs 构建脚本）：必要进度型；
- **Step x/y + LinearProgressIndicator**（TaskStatusCard）：任务实时进度；
- **上下文水位 used/max + 百分比**（ContextMeterBar）：实时预算余量，
  接近上限时要精确知道还剩多少——不是评分；
- **能量条 Energy（0.01-10.0）**：cs-mem 实时状态（记忆是否活跃/将折叠），
  已有 market_low_energy 定性警示兜底，保留。
