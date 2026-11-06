<!-- source: feat/fusion-first-class -->
<!-- target: patch -->

## UI 修复三连：背景层/设置测试按钮/流式跟随

- **自定义背景修复**：背景图层从 Surface 内部移至 Box 底层，内容层设为透明 —— 设置中导入的背景图现在实际可见，切换即时生效
- **设置改进**：顶栏测试按钮移除，测试连接按钮移至模型配置卡最下方并显示连接信息（成功/失败状态 + 消息）；新增联网搜索（ToolsSection）和 JSON Schema（AdvancedSection）设置项
- **流式跟随修复**：跟随不再自行中断 —— 程序化滚动改用时间戳窗口抑制（覆盖 snapshotFlow 异步竞态）、轻触不锁存阅读模式（位移 > 12px 才算拖拽）；跟随位置不再跑到输入栏下方 —— snapToLast 双步贴底（scrollToItem + scrollBy 强制到达 max scroll）、内容节流 200 → 50 字符
