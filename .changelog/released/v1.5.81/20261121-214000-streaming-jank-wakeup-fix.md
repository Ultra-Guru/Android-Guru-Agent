# fix(P0): 流水式输出卡顿回归修复（事件唤醒缺失导致节拍异常）

## 修复问题
修复issue #932 流水式输出「不丝滑/卡/慢」回归问题。

## 根因定位
P0级bug：`CodeViewModel.kt`事件处理分支中，事件到达时仅发送了`renderWakeSignal.trySend(Unit)`打断delay，**但缺失了`renderTickerPolicy.wakeUp()`调用**：
- 空闲连击计数`idleStreak`没有在事件到达时重置
- ticker从250ms深巡逻/50ms巡逻档被唤醒后，下一拍`nextDelayMs()`仍返回巡逻延迟
- 导致事件到上屏延迟最高可达250ms，首token延迟增加10倍，帧间隔严重抖动，视觉上表现为明显卡顿和出字不连贯

## 修改内容
1. **核心修复**：在事件唤醒逻辑中添加`renderTickerPolicy.wakeUp()`调用，事件到达时立即重置空闲计数，回到忙档基线25ms
2. **零侵入**：不改动自适应档位升降逻辑、不改动双皮肤（rikka链路零改动）、不破坏不丢字/断线安全契约

## 验收指标预期恢复
- 事件到上屏延迟：从最高250ms恢复到≤1拍（≤33ms）
- 首token延迟：与固定25ms对照组差异≤1拍
- jank占比：恢复到v1.5.62修复后水平
- 单帧最长：≤33ms（16.7ms两倍以内）
- 不丢字/双皮肤/断线安全：全量保留既有行为

## 验证说明
- 本修复为最小改动（仅新增1行代码）
- 所有单元测试（RenderTickerPolicyTest等）预期全部通过
- GitHub Actions CI门禁编译验证通过