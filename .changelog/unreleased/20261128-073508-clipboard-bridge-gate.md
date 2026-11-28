<!-- pr: clipboard-bridge-gate -->

- 剪贴板桥默认关闭（安全默认，#854）：LLM 工具（TerminalBridge call clipboard / legacy Clipboard）与 PRoot guest 内任意进程（`apexctl clipboard`，含 npm/pip 钩子）不再零门槛读剪贴板 —— 密码管理器 OTP/收码不再直入模型上下文，set 静默投毒通道同步关闭；关闭时 get/set 一律结构化拒绝（稳定错误码 CLIPBOARD_ACCESS_DISABLED + 引导 hint，桥层新 BridgeAccessRefused 契约贯穿直连与文件队列两条路径）
- 新增「剪贴板桥」设置开关（默认关，设置→代理与安全→权限）：开启后 get/set 恢复；开关实时读取（切完即生效，无需重启）；拒绝文案引导改用金库 VaultSave/VaultPaste
