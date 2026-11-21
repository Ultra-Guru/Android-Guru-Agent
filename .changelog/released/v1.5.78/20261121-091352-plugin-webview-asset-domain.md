<!-- pr: plugin-webview-asset-domain -->

- 安全修复（#818）：功能性插件 UI 页面彻底根除 WebView file:// 域 —— 静态资源改经 WebViewAssetLoader 以 https 资产域（appassets.androidplatform.net）映射回插件目录，allowFileAccess / allowFileAccessFromFileURLs 等四开关全关。旧实现页面 XHR 可读 App 私有目录任意文件（含明文 MCP Host token），市场零签名门槛下即完整攻击链
- 子资源请求全量拦截：非资产域请求（外链/任意 scheme）以 403 空响应阻断，不再透传网络栈 —— shouldOverrideUrlLoading 只拦导航管不住 XHR 的口子一并封死
- MCP Host Bearer Token 迁入 EncryptedSharedPreferences（与金库/GitHub token 同款信封，fail-closed 纯内存降级）：明文 JSON 的 token 字段恒置空落盘，旧版明文残留启动时自动迁移并抹除；token 引导惰性异步（主线程零 Keystore 开销，对齐启动税治理方向）
- 插件目录 canonical 边界复核抽为 PluginUiAssetGuard 纯逻辑（入口 URL 构造 / 子资源解析 / 目录穿越与绝对段注入拒绝），新增 12 项 JVM 单元测试锁定契约
