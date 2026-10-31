<!-- pr: precompiled-regex-hot-paths -->

- 5 个热点路径文件的正则全部提级预编译：计划解析国栏提取（EngineResponseParsers，每条 LLM 响应一次）、任务文件 id 防穿越校验（FileTaskStore，每次任务访问）、工作区 id 清洗（CodeConversationMemory / CodeStreamCheckpoint）、GitOpParser 全部 13 处解析正则（status/commit/log/branch 五类可视化卡解析逐次编译 → 对象级 private val 一次编译）
