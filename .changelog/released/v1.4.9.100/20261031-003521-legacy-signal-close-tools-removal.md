<!-- pr: legacy-signal-close-tools-removal -->

- 两个从未注册的 Legacy 终端工具删除：LegacySignalTool / LegacyCloseTool（80+72 行 —— ToolModule 只登记过 Exec/Send/Read/List 四个别名，Signal/Close 是纯死代码），幂等表死条目、LEGACY_ALIAS_IDS 死 id、两条指向不存在工具的别 名映射（terminal_signal/terminal_close）与 ToolRegistrationGuide 引用同步收口
- LegacyListTool 的 Input(dummy) 占位空壳移除：execute() 改无参、schema 里的 dummy 占位属性一并删除（工具本就不接收参数）
