package com.apex.agent.core.tools.catalog

/**
 * # Tool System v5 — 旧工具 id → 新 PascalCase id 的唯一兼容映射
 *
 * 命名改造把工具 id 从 snake_case / dotted（`read_file`、`terminal.exec`、
 * `mcp_list`）统一成**无分隔符 PascalCase**（`Read`、`TerminalExec`、
 * `McpList`）。改名会波及四类"旧名字仍然存在"的地方：
 *
 * 1. **持久化状态**：用户收藏的工具、会话快照、技能 manifest、插件宏、快捷指令；
 * 2. **模型回显**：历史上下文里的旧工具调用（旧会话续跑、模型记忆漂移）；
 * 3. **技能文本**：市场安装的技能正文里引用旧工具名；
 * 4. **外部文档 / 用户的肌肉记忆**。
 *
 * [canonical] 是唯一的归一入口：[DefaultToolRegistry] 的查找/注销都会先过它，
 * 因此旧名字永远能解析到新工具，而**请求体里只会出现新名字**（旧名字不是
 * 注册表 id，不会进入 tools 数组）。
 *
 * 注意：本表只做"旧 → 现役"映射，不做反向（不把新名字映射回旧名字）——
 * 反查由 [ToolNameSanitizer.resolveRegistryId] 负责。
 */
object ToolIdAliases {

    /** 发布前使用过的工具 id → 现役 PascalCase id。 */
    val LEGACY_TO_CURRENT: Map<String, String> = mapOf(
        "agent_mode_preset_list" to "AgentModePresetList",

        "agent_mode_preset_select" to "AgentModePresetSelect",

        "agent_profile_list" to "AgentProfileList",

        "agent_profile_set_default" to "AgentProfileSetDefault",

        "agent_provider_set_key" to "AgentProviderSetKey",

        "agent_role_activate" to "AgentRoleActivate",

        "agent_role_list" to "AgentRoleList",

        "agent_setting_get" to "AgentSettingGet",

        "agent_setting_set" to "AgentSettingSet",

        "alarm_set" to "AlarmSet",

        "app_download_install" to "AppDownloadInstall",

        "app_force_stop" to "AppForceStop",

        "app_info" to "AppInfo",

        "app_install" to "AppInstall",

        "app_install_apk" to "AppInstallApk",

        "app_launch" to "AppLaunch",

        "app_list" to "AppList",

        "app_uninstall" to "AppUninstall",

        "ask_user" to "AskUser",

        "ask_user_choice" to "AskUserChoice",

        "base_convert" to "BaseConvert",

        "battery_status" to "BatteryStatus",

        "browser_click" to "BrowserClick",

        "browser_context_summary" to "BrowserContextSummary",

        "browser_date_input" to "BrowserDateInput",

        "browser_debug_dump" to "BrowserDebugDump",

        "browser_download_list" to "BrowserDownloadList",

        "browser_drag" to "BrowserDrag",

        "browser_execute_js" to "BrowserExecuteJs",

        "browser_extract_content" to "BrowserExtractContent",

        "browser_file_upload" to "BrowserFileUpload",

        "browser_get_cookies" to "BrowserGetCookies",

        "browser_hover" to "BrowserHover",

        "browser_input" to "BrowserInput",

        "browser_locate" to "BrowserLocate",

        "browser_navigate" to "BrowserNavigate",

        "browser_network_log" to "BrowserNetworkLog",

        "browser_page_type" to "BrowserPageType",

        "browser_press_key" to "BrowserPressKey",

        "browser_screenshot" to "BrowserScreenshot",

        "browser_scroll" to "BrowserScroll",

        "browser_select" to "BrowserSelect",

        "browser_show" to "BrowserShow",

        "browser_snapshot" to "BrowserSnapshot",

        "browser_toggle" to "BrowserToggle",

        "browser_wait_for" to "BrowserWaitFor",

        "calculate" to "Calculate",

        "calendar_event_list" to "CalendarEventList",

        "capability_report" to "CapabilityReport",

        "clipboard" to "Clipboard",

        "code_check" to "CodeCheck",

        "code_edit" to "CodeEdit",

        "code_git_branch" to "CodeGitBranch",

        "code_git_commit" to "CodeGitCommit",

        "code_git_diff" to "CodeGitDiff",

        "code_git_log" to "CodeGitLog",

        "code_git_status" to "CodeGitStatus",

        "code_glob" to "CodeGlob",

        "code_grep" to "CodeGrep",

        "code_read" to "CodeRead",

        "code_task" to "CodeTask",

        "code_todo" to "CodeTodo",

        "code_write" to "CodeWrite",

        "connector_list" to "ConnectorList",

        "connector_send_message" to "ConnectorSendMessage",

        "contact_search" to "ContactSearch",

        "context_recap" to "ContextRecap",

        "context_search" to "ContextSearch",

        "control_media" to "ControlMedia",

        "copy_move_file" to "CopyMoveFile",

        "cron_next" to "CronNextLegacy",

        "csv_query" to "CsvQuery",

        "datetime" to "DatetimeLegacy",

        "deep_link" to "DeepLink",

        "delete_file" to "DeleteFile",

        "download_file" to "DownloadFile",

        "duration_convert" to "DurationConvertLegacy",

        "edit_file" to "Edit",

        "file_hash" to "FileHash",

        "file_pick" to "FilePick",

        "get_device_info" to "GetDeviceInfo",

        "get_location" to "GetLocation",

        "get_set_settings" to "GetSetSettings",

        "get_time" to "GetTimeLegacy",

        "github_create_issue" to "GithubCreateIssue",

        "github_get_user" to "GithubGetUser",

        "github_list_branches" to "GithubListBranches",

        "github_list_issues" to "GithubListIssues",

        "github_list_repos" to "GithubListRepos",

        "github_read_file" to "GithubReadFile",

        "github_search_code" to "GithubSearchCode",

        "github_search_repos" to "GithubSearchRepos",

        "github_write_file" to "GithubWriteFile",

        "glob_files" to "GlobFiles",

        "http_request" to "HttpRequest",

        "image_capture" to "ImageCapture",

        "image_convert" to "ImageConvert",

        "image_info" to "ImageInfo",

        "input_text" to "InputText",

        "json" to "Json",

        "json_path" to "JsonPathLegacy",

        "json_transform" to "JsonTransformLegacy",

        "list_files" to "ListFiles",

        "logcat" to "Logcat",

        "market_search" to "MarketSearch",

        "mcp_call" to "McpCall",

        "mcp_connect" to "McpConnect",

        "mcp_list" to "McpList",

        "memory_recall_macro" to "MemoryRecallMacro",

        "memory_recent_episodes" to "MemoryRecentEpisodes",

        "memory_search_nodes" to "MemorySearchNodes",

        "network_info" to "NetworkInfo",

        "notification_post" to "NotificationPost",

        "notification_read" to "NotificationRead",

        "random" to "Random",

        "random_generate" to "RandomGenerateLegacy",

        "read_file" to "Read",

        "regex" to "Regex",

        "regex_extract" to "RegexExtractLegacy",

        "regex_replace" to "RegexReplaceLegacy",

        "screenshot" to "Screenshot",

        "search_files" to "SearchFiles",

        "session_stats" to "SessionStats",

        "share_content" to "ShareContent",

        "shell_execute" to "ShellExecute",

        "shortcut_define" to "ShortcutDefine",

        "shortcut_list" to "ShortcutList",

        "shortcut_run" to "ShortcutRun",

        "skill_activate" to "SkillActivate",

        "skill_create" to "SkillCreate",

        "skill_install" to "SkillInstall",

        "skill_list" to "SkillList",

        "skill_search" to "SkillSearch",

        "skill_uninstall" to "SkillUninstall",

        "sms_send" to "SmsSend",

        "string_distance" to "StringDistance",

        "terminal_close" to "TerminalCloseLegacy",

        "terminal_exec" to "TerminalExecLegacy",

        "terminal_list" to "TerminalListLegacy",

        "terminal_read" to "TerminalReadLegacy",

        "terminal_send" to "TerminalSendLegacy",

        "terminal_signal" to "TerminalSignalLegacy",

        "terminal.backends" to "TerminalBackends",

        "terminal.bridge" to "TerminalBridge",

        "terminal.close" to "TerminalClose",

        "terminal.create" to "TerminalCreate",

        "terminal.diagnostics" to "TerminalDiagnostics",

        "terminal.exec" to "TerminalExec",

        "terminal.fs" to "TerminalFs",

        "terminal.linux.bootstrap" to "TerminalLinuxBootstrap",

        "terminal.linux.capabilities" to "TerminalLinuxCapabilities",

        "terminal.linux.network" to "TerminalLinuxNetwork",

        "terminal.linux.packages" to "TerminalLinuxPackages",

        "terminal.linux.repair" to "TerminalLinuxRepair",

        "terminal.linux.status" to "TerminalLinuxStatus",

        "terminal.observe" to "TerminalObserve",

        "terminal.resize" to "TerminalResize",

        "terminal.run" to "TerminalRun",

        "terminal.signal" to "TerminalSignal",

        "terminal.snapshot" to "TerminalSnapshot",

        "terminal.ubuntu.ensure" to "TerminalUbuntuEnsure",

        "terminal.ubuntu.install" to "TerminalUbuntuInstall",

        "terminal.ubuntu.status" to "TerminalUbuntuStatus",

        "terminal.wait" to "TerminalWait",

        "terminal.workspace.environment" to "TerminalWorkspaceEnvironment",

        "terminal.workspaces" to "TerminalWorkspaces",

        "terminal.write" to "TerminalWrite",

        "text_diff" to "TextDiff",

        "text_transform" to "TextTransform",

        "time" to "Time",

        "tool_batch_run" to "ToolBatchRun",

        "tool_list" to "ToolList",

        "tool_open" to "ToolOpen",

        "tool_search" to "ToolSearch",

        "torch" to "Torch",

        "tts_speak" to "TtsSpeak",

        "ui_dump" to "UiDump",

        "ui_notifications" to "UiNotifications",

        "ui_swipe" to "UiSwipe",

        "ui_tap" to "UiTap",

        "unit_convert" to "UnitConvert",

        "uuid_generate" to "UuidGenerateLegacy",

        "vault_delete" to "VaultDelete",

        "vault_list" to "VaultList",

        "vault_paste" to "VaultPaste",

        "vault_save" to "VaultSave",

        "version_compare" to "VersionCompare",

        "vibrate" to "Vibrate",

        "wait" to "Wait",

        "web_fetch" to "WebFetch",

        "web_search" to "WebSearch",

        "write_file" to "Write",

        "xml_extract" to "XmlExtract",
    )

    /** 归一：命中旧表返回现役 id，否则原样返回。大小写不敏感匹配由注册表兜底。 */
    fun canonical(toolId: String): String = LEGACY_TO_CURRENT[toolId] ?: toolId

    /** @return true 当 [toolId] 是发布前使用过的旧名字。 */
    fun isLegacy(toolId: String): Boolean = toolId in LEGACY_TO_CURRENT
}
