# 工作区浏览器（Coding 模式全屏页 v1）

- Coding 模式右上角新增文件图标：一键进入全屏工作区浏览器（文件夹 / Git / 变更和产物三标签 + 实时跟随开关）
- 文件夹：工作区文件树（目录优先 / 过滤 / git 变更状态点）+ 多格式预览（文本行号 / Markdown 渲染 / JSON 美化 / 图片缩放 / SVG / PDF 首页 / 音视频 / zip·apk 清单 / 二进制 hex dump）
- Git：分支与 ahead/behind、暂存/未暂存/未跟踪分组行内 stage/unstage、提交框（同源身份注入）、单文件 diff 双行号增删着色、历史与分支列表、非仓库一键 init
- 变更和产物：统计三卡 + 精确 +增/-删行数 + 内联 diff 懒加载 + 构建产物目录分组点击跳预览
- 实时跟随：agent 改动实时映射到文件树 / 预览 / Git 面板（工具事件直推 + 轮询指纹兜底，callId 幂等去重）
- 核心能力拆独立库仓库 apex-workspace-kit 维护，主仓库 composite build 消费（apex-browser-kit 同款拆库模式）
