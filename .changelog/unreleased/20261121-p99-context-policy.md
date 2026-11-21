<!-- pr: feat/aga-self-introspection -->

- 新增 ContextPolicyGet / ContextPolicySet：白名单九档（3/5/8/10/15/20/30/50/100，保留层数与压缩阈值成对绑定），逐次确认后写入设置、下轮生效；非法档位返回列全允许值的可操作错误
- 压缩门接入档位（实时读取，coerceAtMost 引擎窗口防越窗覆写）；压缩前抢救保护无关闭开关，SYSTEM 段永不裁
