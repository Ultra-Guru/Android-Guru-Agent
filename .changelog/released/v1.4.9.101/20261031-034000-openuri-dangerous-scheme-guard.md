<!-- pr: openuri-dangerous-scheme-guard -->

- 深链工具（DeepLink）新增危险 scheme 边界拦截：javascript/data/file/content/intent 等执行与本地穿透类 URI 直接拒绝并返回明确报错，http(s) 与 market:// 等合法深链不受影响
