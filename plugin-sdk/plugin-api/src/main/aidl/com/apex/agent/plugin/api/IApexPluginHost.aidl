// IApexPluginHost.aidl
package com.apex.agent.plugin.api;

/**
 * 宿主桥：宿主经 IApexPlugin.attachHost() 注入给插件的反向回调通道。
 *
 * 背景：浏览器引擎（BrowserEngine）等能力活在宿主进程，插件进程无法直接
 * 访问——插件可只声明与分发工具清单（v1.5.0 前的网页自动化插件即此形态，
 * 现已内置为宿主大功能），实际执行经本接口回到宿主。
 */
interface IApexPluginHost {
    /** 在宿主进程执行一个工具（当前为 BrowserAgentTools 的 browser_* 工具）。 */
    String executeHostTool(String toolId, String argumentsJson);

    /** 宿主能力清单（JSON），插件可用于校验/展示，例：{"browserTools":[...]}。 */
    String getHostCapabilitiesJson();
}