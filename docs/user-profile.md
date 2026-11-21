# 用户画像（User Profile）—— 五维长期特征

> 版本：v1.0 · 状态：已实现
> 模块：`app/ui/screen/memory/profile` · DI：`di/UserProfileModule`

## 定位

回答「这个用户是谁」：跨会话长期稳定的五维度结构化特征。与既有两套记忆通道分工互补：

| 通道 | 回答的问题 | 数据形态 |
|---|---|---|
| 聊天记忆（memKit.chat） | 发生过什么 | 自由文本事实（画像 / 近况 / 里程碑） |
| 角色经验卡（RoleExperience） | 学到过什么教训 | 任务执行经验（场景 + 工具序列） |
| **用户画像（本特性）** | **用户是谁** | **五维结构化条目 + 置信度 + 证据** |

## 五维度

| key | 中文 | 说明 |
|---|---|---|
| `tech-stack` | 技术栈 | 常用语言、框架、工具链 |
| `coding-style` | 编码风格 | 命名 / 注释 / 结构偏好 |
| `preferences` | 偏好 | 交互与输出偏好 |
| `domains` | 关注领域 | 正在做的业务与技术领域 |
| `habits` | 工作习惯 | 工作节奏、提交习惯 |

## 架构

```
CodeSessionStore（跨工作区快照，listAll 新增）
        │
        ▼
ProfileDistiller ── ChatModel 端口（ModelRuntimeChatModel：SUMMARY 便宜线）
        │              提示词：写入门禁 + update 优先 + 严格 JSON 协议
        ▼
UserProfileStore（filesDir/user_profile/profile.json 单卷）
  · 三层并发：@Volatile 缓存 + Mutex 写串行 + 惰性装载
  · 原子写：tmp → fsync → rename；坏卷隔离 .corrupt
  · 有界：每维度 ≤ 12 条 / 总量 ≤ 60（丢最弱：置信度升序 → 最旧）
  · snapshotBlocking：主线程安全（发送路径注入读）
        │
        ├── MemoryScreen 画像分区（头 + 维度卡 + 提炼按钮 + 手动增改删）
        └── CodeViewModel.runEngine：置信度 Top-8 → sessionExtras 最前段
```

## 蒸馏协议

**入口**：记忆页 → 用户画像分区 → 「提炼」按钮（防重入，后台协程）。

**提示词门禁**（代码侧钳制兜底，不信任 LLM 输出形状）：

- 只记用户特异且长期稳定的信息；不记常识 / 一次性细节 / 未来计划 / 助手发言
- 更新优先：同主题演进必须 `update`（`old` 逐字复制原文），全新概念才 `new`
- 每条 ≤ 40 字、第三人称；`confidence` 0.3~1.0；单次最多 8 个动作
- 无信号输出 `{"skip":true}`

**转写组装**：最近 5 个工作区快照 × 尾部 30 条消息（USER 保 600 字 / ASSISTANT 400 字 / TOOL 压成一行信号 / SYSTEM 滤除），总量截断 12000 字符。

**落账门禁**（`UserProfileStore.apply`）：未知维度跳过、空内容跳过、`update` 的 `old` 逐字匹配才生效（宁可漏更不可错改）、置信度钳制、内容截断 60 字、id 服务端重生成。

## 注入

发送路径（`CodeViewModel.runEngine`）：全量条目按置信度降序取 Top-8，格式化为「## 用户画像（跨会话稳定特征，按置信度排序）」段，拼在 sessionExtras 最前（先于小圆环与角色经验卡——画像是身份层，经验是任务层）。无画像时零注入（不产生空段）。

## 隐私

- 画像仅存本机 `filesDir/user_profile/`，随应用数据卸载删除
- 记忆页可逐条查看 / 编辑 / 删除，支持一键清空（二次确认）
- 蒸馏走 SUMMARY 便宜模型（与聊天记忆蒸馏同口径）

## 测试

- `UserProfileModelsTest`：维度解析容错 / 分组序与置信度排序 / Top-N / 注入段格式 / JSON 提取容错（围栏 / 前后废话 / 字符串内花括号 / 不平衡）
- `UserProfileStoreTest`：upsert 双态 / 幂等删除 / 蒸馏门禁全矩阵 / 有界裁剪 / 坏卷隔离 / 跨实例持久化
- `ProfileDistillerTest`：noSignal 三态 / 围栏废话容错 / 真实 Store 落账 / TOOL 入转写 SYSTEM 滤除
- `MemoryListPlanTest`：PROFILE 分区序 + 锚点 + 维度卡渲染（既有断言同步更新为五分区）
