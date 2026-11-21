# 重构

- 模式与角色选择器的同职责弱描边共用 `selectorOutlineColor()` 语义入口：跟随当前 Material 主题的 `outlineVariant`，不改变原有 0.6 视觉强度（Refs #270）。连续动画透明度、disabled 状态和其它 alpha 魔数不在此切片；#270 保持开放。
