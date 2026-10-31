<!-- pr: tool-registration-guide-removal -->

- ToolRegistrationGuide 迁移指南对象删除：ATR 2.0 Phase 3 时代的装配指南（newTools/legacyTools/TOOL_SPEC_MAP），零外部引用 —— 真实装配在 app 层 ToolModule.kt，指南与实际注册表早已漂移（如 Signal/Close 条目与 #585 删除同步过期），保留只会误导后来人
