<!-- pr: codetodo-dual-shape-streaming-todos -->

- 修复 CodeTodo 工具调用失败（invalid argument: 'todos': expected string）：模型发内联 JSON 数组时被旧 schema（声明为 string）在执行前就拒绝、白费一轮重试——现在 todos 声明为数组，内联数组与字符串编码两种形态都直收；ShortcutDefine / ShortcutRun / ToolBatchRun / JsonTransform 等同款复合参数一并修正
- 任务清单在流水式输出过程中实时出现：todos 条目随参数生成逐条落到面板（不再等工具执行完成），胶囊头行同步显示「N 项」计数；条目数不再有 50 条硬上限，长清单在面板内滚动浏览
