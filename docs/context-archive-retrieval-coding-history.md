# 上下文自主检索与归档（#396）+ 工具卡降噪 + Coding 历史对话

> 2026-10 · 三项用户反馈驱动的改动：①工具调用卡片「眼花缭乱」；②上下文
> 无论多长 AI 都能自主查看（不需要时不浪费 token）；③抽屉右滑误触 +
> Coding 历史对话不可找回。

## 1. 工具调用卡降噪（Agent 聊天流）

**用户诉求**：折叠时只显示名字，不要多余信息；agent 调用工具时自动展开
详情，使用完自动合并。

- **折叠态（v2.1 胶囊）**：源图标 + 工具名 + 状态点（仅运行中脉冲 /
  失败红点；成功不渲染——常态即噪声）。摘要 / 时长 / 徽章 / 箭头全部
  退场；胶囊自适应内容宽度（`widthIn(max=220dp)`），不再占满整行。
- **自动展开/合并**（既有行为收口）：`ToolCallStart` → RunningToolCallCard
  默认展开（参数块 + 流式时间线 + 进度）；`ToolCallComplete` → 落地为
  折叠胶囊。每个新工具调用重置展开态。
- **展开态去重**：参数块与时间线 START 步（全量参数 JSON）不再双份渲染；
  输出区与 COMPLETE/OUTPUT 尾窗摘要不再双份。完成卡时间线只保留
  PROGRESS 步（唯一不重复的信息源；输出为空时保留收尾步兜底）。

## 2. 上下文自主检索（#396）

**痛点**：压缩器把消息裁出活跃上下文后，`memory.save(compressed)` 覆写
持久化记忆——被裁剪的消息**永久消失**，#172 回顾三件套的数据源只剩
压缩残影，模型没有任何自救手段。

**方案：压缩 ≠ 销毁（归档 + 检索 + 取原文三层闭环）**

```
ApexAgentEngine ──compress──▶ ArchivingCompressor（装饰任意 ContextCompressor）
   │                              ① 压缩前快照 before
   │                              ② 差集归档：before 中不再完整存在的消息
   │                                 （被移除 / 被就地截断）→ SessionContextArchive
   │                                 （保留原始全文，同 toolCallId 留长版）
   │                              ③ upsert 一条 [CONTEXT ARCHIVE] System 注记
   │                                 （~50 token，告诉模型去哪找回来）
   ▼
ContextSearch（CORE）── 双源检索：归档 a#N + 活跃 #N ──▶ ContextFetch（CORE）
                                                              │ 按 ref 取单条全文
                                                              │ offset/limit 分窗
```

- **SessionContextArchive**（core:tool-registry 接口，app 层
  FileContextArchive 落盘 `context_archive/session_archive.json`）：归档
  记录全文不截断、稳定序号、`clear()` 随会话生命周期（引擎
  clearHistory/restoreHistory + 存储页清空历史三处接线）。
- **ContextFetch**（新工具，CORE）：按定位符 `#N`（活跃，绕过 2000 截断
  护栏直读 LlmMessage）/ `a#N`（归档）取单条记录全文，超长记录
  `offset`/`limit` 分窗（默认 4000，上限 24000），带续读提示。
- **ContextSearch**（升级）：候选集 = 归档（前）+ 活跃（后），归档命中以
  `a#N` 定位；未接线归档时行为与旧版完全一致（默认参数向后兼容）。
- **ContextRecap**（升级）：Overview 新增归档条数与检索提示行。
- **压缩质量**：LLM 摘要输入的用户原文截断 500 → 1000 字符（任务/约束
  常在长 prompt 里，500 会腰斩）。

**Token 经济性**：归档与检索单不占常驻上下文——模型需要时经工具按需
拉取，不需要时只付两个 CORE 工具的 schema 成本。

## 3. 抽屉手势 + Coding 历史对话

- **抽屉**：`ModalNavigationDrawer.gesturesEnabled = false`——右滑误触
  拉出抽屉的入口整体关闭，只保留左上角三条杠；关闭通道不受影响（scrim
  点击 / BackHandler / 选中自动收起）。
- **Coding 历史对话**（旧实现：新会话 = 物理删除，历史永久丢失）：
  - `CodeHistoryStore`（filesDir/code_history）：新会话前自动归档
    当前对话 = UI 快照（ui.json）+ **双思考逻辑引擎记忆原文件**
    （deep.json / standard.json）；会话 id = 归档时刻毫秒（假钟注入）。
  - `CodeHistorySheet`：右上角新增「对话历史」入口（Chat 图标，与
    History 图标的长任务中心语义区分）；列表点按恢复、删除带二次确认。
  - 恢复语义：当前对话先归档（替换不丢现场）→ 双线引擎记忆文件回写 →
    会话快照写回 CodeSessionStore → `bindWorkspace` 重绑（引擎重放 +
    UI 恢复）；工作区删除时历史一并清理。

## 验证

| 项 | 结果 |
| --- | --- |
| core 模块 kotlinc 镜像编译（CI 同款链） | logging→llm-adapter→tool-registry→agent-engine→standard-loop→code-tools→code-engine 零错误 |
| ContextArchiveToolsTest（新增） | 14/14 |
| ArchivingCompressorTest（新增） | 6/6 |
| CodeHistoryStoreTest（新增，假钟） | 11/11 |
| ContextToolsTest（存量回归） | 24/24 |
| 门禁 | file-size / anti-pattern / string-mirror / changelog / test-discipline / bracket-balance 全绿 |
