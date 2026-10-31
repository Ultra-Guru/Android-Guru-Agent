# 🔥 apex-quality-forge 审计报告

**结论：❌ **4 项硬红灯****

## ❌ 硬红灯（门禁镜像）

- 📐 app/src/main/kotlin/com/apex/agent/ui/screen/code/CodeViewModel.kt 1205/1200 行超预算
- 🕳️ ComposeFoundry/app/src/main/java/com/foundry/preview/engine/UiRenderer.kt:200 空 catch
- 🕳️ app/src/main/kotlin/com/apex/agent/tools/ApkInstaller.kt:118 空 catch
- 🕳️ app/src/main/kotlin/com/apex/agent/tools/MoreCapabilitiesHost.kt:478 空 catch

## 📐 预算余量预警（下一个要爆的文件）

| 余量 | 行数/预算 | 文件 |
|---:|---|---|
| -5 | 1205/1200 | `app/src/main/kotlin/com/apex/agent/ui/screen/code/CodeViewModel.kt` |
| 0 | 1200/1200 | `terminal-view/src/main/kotlin/com/apex/agent/terminalview/TerminalView.kt` |
| 1 | 1199/1200 | `core/agent-engine/src/main/kotlin/com/apex/agent/core/engine/ApexAgentEngine.kt` |
| 5 | 1195/1200 | `app/src/main/kotlin/com/apex/agent/ui/screen/agent/AgentChatViewModel.kt` |
| 20 | 1180/1200 | `app/src/main/kotlin/com/apex/agent/ui/screen/about/AboutScreen.kt` |
| 36 | 1164/1200 | `app/src/main/kotlin/com/apex/agent/ui/screen/about/AboutUpdatePanel.kt` |
| 39 | 1161/1200 | `terminal-emulator/src/main/kotlin/com/apex/agent/terminalemulator/TerminalCore.kt` |
| 57 | 1143/1200 | `app/src/main/kotlin/com/apex/agent/ui/screen/agent/AgentChatOutputCards.kt` |
| 63 | 1137/1200 | `app/src/main/kotlin/com/apex/agent/marketplace/MarketInstallManager.kt` |
| 71 | 1129/1200 | `app/src/main/kotlin/com/apex/agent/di/ToolModule.kt` |
| 80 | 1120/1200 | `app/src/main/kotlin/com/apex/agent/ui/screen/agent/AgentChatScreen.kt` |
| 107 | 1093/1200 | `app/src/main/kotlin/com/apex/agent/ui/screen/code/CodeScreen.kt` |

## 📏 超长函数热点（>80 行）

- `app/src/main/kotlin/com/apex/agent/ui/screen/code/CodeViewModel.kt` runEngine() 111 行
- `core/agent-engine/src/main/kotlin/com/apex/agent/core/engine/ApexAgentEngine.kt` executeBuildLoop() 323 行
- `app/src/main/kotlin/com/apex/agent/ui/screen/agent/AgentChatViewModel.kt` runEngine() 130 行
- `app/src/main/kotlin/com/apex/agent/ui/screen/about/AboutScreen.kt` AboutScreen() 272 行
- `app/src/main/kotlin/com/apex/agent/ui/screen/about/AboutUpdatePanel.kt` UpdatePanel() 996 行
- `terminal-emulator/src/main/kotlin/com/apex/agent/terminalemulator/TerminalCore.kt` handleCsi() 144 行
- `app/src/main/kotlin/com/apex/agent/ui/screen/agent/AgentChatOutputCards.kt` FileOpCard() 100 行
- `app/src/main/kotlin/com/apex/agent/marketplace/MarketInstallManager.kt` installClawHubSkill() 92 行
- `app/src/main/kotlin/com/apex/agent/di/ToolModule.kt` provideToolRegistry() 668 行
- `app/src/main/kotlin/com/apex/agent/ui/screen/code/longtask/CodeLongTaskSheet.kt` LongTaskRecordCard() 216 行

## 🌀 深嵌套热点（>6 层）

- `app/src/main/kotlin/com/apex/agent/ui/screen/code/CodeViewModel.kt` 嵌套 7 层
- `terminal-view/src/main/kotlin/com/apex/agent/terminalview/TerminalView.kt` 嵌套 7 层
- `core/agent-engine/src/main/kotlin/com/apex/agent/core/engine/ApexAgentEngine.kt` 嵌套 8 层
- `app/src/main/kotlin/com/apex/agent/ui/screen/about/AboutScreen.kt` 嵌套 7 层
- `app/src/main/kotlin/com/apex/agent/ui/screen/about/AboutUpdatePanel.kt` 嵌套 14 层
- `app/src/main/kotlin/com/apex/agent/ui/screen/agent/AgentChatOutputCards.kt` 嵌套 7 层
- `app/src/main/kotlin/com/apex/agent/ui/screen/agent/AgentChatScreen.kt` 嵌套 12 层
- `app/src/main/kotlin/com/apex/agent/ui/screen/code/CodeScreen.kt` 嵌套 8 层
- `app/src/main/kotlin/com/apex/agent/ui/screen/code/longtask/CodeLongTaskSheet.kt` 嵌套 10 层
- `app/src/main/kotlin/com/apex/agent/ui/screen/settings/SettingsScreen.kt` 嵌套 9 层

## 📊 存量指标（趋势观察，不拦截）

- 技术债标记（TODO/FIXME/HACK/XXX）：**9**
- `!!` 非空断言：**1382**
- `runBlocking`：**772**（主仓棘轮基线 696）
- `Thread.sleep`：**49**（主仓棘轮基线 33）
- Kotlin 文件：main **1008** + test **313**
