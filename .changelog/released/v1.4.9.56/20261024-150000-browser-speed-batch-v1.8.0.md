<!-- pr: browser-speed-batch-v1.8.0 -->
- 浏览器自动化提速（apex-kit-browser v1.8.0 接入，lock bump 365bc0e→06563a0）：动作后固定延迟改探针轮询早退（典型点击 ~550ms→~140ms，click/input/select/press_key/hover/drag 全覆盖）；snapshot 不再每次重复注入网络监控 JS（Tab.netHooked 位图去重）；BrowserWaitFor 等待前 1.2s 用 100ms 快轮；快照摘要 lineOf 按 bid 缓存
- 新工具 BrowserBatchActions：一次调用串行执行 ≤8 个动作（click/input/select/press_key/scroll/wait），逐个带重试/熔断/模糊自愈/动作后验证，任一失败即停并标明断点——表单填写场景模型往返 N→1
- BrowserWaitFor 补齐七模式：element_gone / text_gone / network_idle（在途请求归零）/ selector_count（命中数谓词，如 'li.item >= 10'）此前引擎已支持但工具面只暴露 3 种
- BrowserScroll 增 to 参数（top/bottom 一步到边界，超长页面优先于 delta_y）；BrowserSnapshot 增 token_budget 参数（400..4000，默认 1600）
- 修库侧后台视口截图死代码：提前 return 守卫使手动 measure/layout 兜底永不可达（未挂浮窗的 WebView 截图必失败），移除守卫后兜底重新生效
- 浏览器仪表盘 UI：动作卡改 tracer 事件流即时驱动（落记录即滚，轮询放缓至 2.5s 兜底）；动作条目增成败色点（失败=错误红）与慢动作耗时警示（>2s 琥珀色）
- 聊天工具卡：BrowserSnapshot 输出渲染为紧凑快照卡（交互元素计数 chip + 截断警示 chip + 限高等宽滚动列表），不再以文本墙撑爆聊天流
