<!-- pr: kit-request-sanitizer -->

- 修复「停止后再继续直接 400 致命错误」：工具执行中点停止 → 硬取消绕过库引擎的中止补齐路径 → 会话留下无配对的 `Assistant.toolCalls` → 再次发消息/点「继续」时请求携带悬空 tool_calls，服务端拒绝（400 "tool_calls must be followed by tool messages"）→ 容错降级无工具重发仍 400（消息列表未动）→ 会话级报废
- kit 三线（标准/织造/信使）模型客户端出站统一消毒（KitRequestSanitizer，与深潜线 buildMessages 逐请求修补同款纯函数）：悬空声明就地合成「结果未知」应答、游离应答回迁、孤儿/重复应答丢弃——无论库引擎内存态处于何种中断残局，出站请求的配对协议恒成立；无悬空零开销、幂等
- 织造/信使线的记忆加载归一化补齐（此前纯 load，与标准线 loadNormalized 同缺陷在重启恢复路径上复现）；标准线实现上收共享
- 修复「停止后提问卡残留」：宿主侧工具门挂起的提问收口（#395 同款）此前只接深潜线，kit 三线中止后「继续」按钮把答案投给已死任务——统一在四线路由门面（DualLogicCodeEngine.abort）前置收口，幂等
