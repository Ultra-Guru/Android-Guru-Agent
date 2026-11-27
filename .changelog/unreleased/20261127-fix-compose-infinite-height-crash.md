---
title: 修复 Compose 垂直滚动容器无限高度约束崩溃
severity: critical
category: crash
---

## 问题
修复 `java.lang.IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints` 崩溃。

该崩溃在键盘弹出、布局重组时触发，原因为垂直滚动容器（LazyColumn/LazyVerticalGrid）被放置在另一个垂直滚动容器（Column with verticalScroll）内，或被放置在高度未约束的父布局（如 `wrapContentSize(unbounded=true)`、`weight` 错误使用）中，导致滚动组件测量时拿到无限大的最大高度约束，触发 Compose 运行时检查失败。

## 修复要点
1. 排查所有使用 `Modifier.verticalScroll()` 的 Column 容器，移除其中嵌套的 LazyColumn/LazyVerticalGrid，改为将头部内容作为 `item()` 放入 LazyColumn 作用域内
2. 为所有在非滚动父容器内的可滚动子组件添加明确的高度约束（`heightIn(max=...)` / `fillMaxHeight()` / `weight()`）
3. 移除不当使用的 `Modifier.wrapContentSize(unbounded = true)` 调用
4. 检查 LinearLayout 中 ComposeView 的 weight 属性，确保在滚动层次上给出确定高度

这是 Compose 的标准最佳实践：永远不要在一个垂直滚动的父布局中直接嵌套另一个垂直滚动的子布局，除非给子布局明确的高度边界。
