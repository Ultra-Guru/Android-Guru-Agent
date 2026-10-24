package com.apex.agent.di

import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.platform.browser.BrowserAgentTools
import com.apex.agent.platform.browser.BrowserSessionTools

/**
 * §3.5 浏览器工具族注册体 —— 自 ToolModule.kt 拆分（SRP 预算：主文件
 * 逼近 1200 行上限；模式同 ToolModuleRegistrySections / FusionRegistration 卫星）。
 *
 * 两个家族共用同一 [com.apex.browser.engine.BrowserEngine] / tracer /
 * WAITING_HUMAN 守卫语义，注册面合并为一个入口：
 * - [BrowserAgentTools]（26 个页面内动作）：DOM 级网页操控，语义哈希稳定
 *   ref（抗 SPA 刷新）、物理触摸注入点击、显式握手人工接管、动作后验证；
 *   v1.8.0 新增 batchActions（一次调用串行执行 ≤8 个动作，模型往返 N → 1）；
 * - [BrowserSessionTools]（9 个会话级工具，v1.7.0）：历史导航、浏览历史、
 *   页内查找、下载中心、书签、文字缩放、清除浏览数据——对标 Playwright
 *   MCP 的会话面。
 */
internal fun registerBrowserTools(
    registry: DefaultToolRegistry,
    browserAgentTools: BrowserAgentTools,
    browserSessionTools: BrowserSessionTools,
) {
    // ═══ 3.5 内置浏览器自动化 (26) ═══
    // 对标 Operit 的 BrowserAgent：DOM 级网页操控，带语义哈希稳定 ref（抗 SPA 刷新），
    // 物理触摸注入点击、显式握手人工接管、动作后验证。工具内已含 WAITING_HUMAN 守卫。
    browserAgentTools.all().forEach { registry.register(SafeAgentTool(it)) }

    // ═══ 3.5b 浏览器会话工具集 (9) ═══
    // v1.7.0 会话级基线（对标 Playwright MCP 的会话面）：历史导航 back/forward/reload、
    // 浏览历史查询、页内查找计数、下载中心（全量台账 + cancel/retry）、书签管理、
    // 文字缩放、清除浏览数据。与 BrowserAgentTools 共用引擎 / tracer / 守卫语义。
    browserSessionTools.all().forEach { registry.register(SafeAgentTool(it)) }
}
