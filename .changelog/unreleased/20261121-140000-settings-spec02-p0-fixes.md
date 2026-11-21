<!-- pr: settings-spec02-p0-fixes -->

- BUG-01（P0）：设置 JSON 解析失败不再被默认值静默覆盖 —— 原始串备份到应用存储 settings-corrupt/ 并进入只读安全模式，设置页顶部持久横幅告知备份位置与恢复路径；一次文件损坏不再永久抹掉全部配置
- BUG-02（P0）：旧版明文 `llm_api_key` 迁移后彻底删除 —— 加密区 `commit()` 确认成功才清明文、置迁移标记；启动清扫全部明文 `provider_api_key_*` 残留，读取路径删除明文回退分支
- BUG-03（P0）：服务商编辑弹窗 API Key 改密码遮挡 + 显隐切换 + 单值输入（运行时本就只消费第一个 Key），编辑态 rememberSaveable（旋转不再丢编辑）
- BUG-06（P2）：启动不再无条件全量写盘 —— 只在「种子 / 迁移」真实发生时单次批量 commit 并校验结果，失败进诊断日志
- BUG-07（P2）：档案 ID 改 UUID（同毫秒连点不再碰撞覆盖）；副本名本地化（英文 (copy) / 中文（副本），新增 settings_profile_copy_suffix 双语资源）
