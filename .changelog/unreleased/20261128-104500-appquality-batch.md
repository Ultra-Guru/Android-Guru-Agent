<!-- pr: appquality-batch -->

- 冷启动主线程不再初始化 Keystore：SettingsRepository 密钥迁移/回填改懒触发（ApexApp 后台预热 + defaultLlmConfig / getProviderApiKey 幂等兜底）—— Application.onCreate 零 Keystore 开销（#861）
- Provider 保存校验接入系统明文放行口径：局域网 http:// 端点（如 http://192.168.1.20:11434）保存时即报结构化错误 —— 不再运行期才以 Cleartext not permitted 连接失败暴露（#838）
