<!-- pr: backup-restore-reload -->

- 备份恢复后设置内存态即时回灌：导入完成即重读 SharedPreferences 并刷新 SettingsRepository 状态流（与首启加载共用同一 loadFromDisk 路径，含损坏域修复横幅即时消退）—— 恢复后任意一次设置编辑不再把陈旧快照整体写回、抹掉刚导入的 provider 与密钥（#853）
- 恢复完成提示从「重启后完全生效」改为「已立即生效，无需重启」；回灌失败仍诚实提示重启后再编辑（错误码 settings_reload_failed）
