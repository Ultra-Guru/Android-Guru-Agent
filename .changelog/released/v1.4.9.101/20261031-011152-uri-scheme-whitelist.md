<!-- pr: uri-scheme-whitelist -->

- 三处 ACTION_VIEW 外链出口统一 scheme 白名单（http/https）：更新日志弹窗与关于页的外链函数、FileOpener.openUrl（Markdown 链接/视频卡出口）—— 非 http(s) scheme（如 javascript:、file:、自定义 scheme）一律拒绝打开，防 intent 重定向；URL 源自外部内容（changelog.json / Markdown 渲染）的路径不再裸传系统
