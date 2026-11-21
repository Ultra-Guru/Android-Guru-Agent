package com.apex.agent.di

import android.content.Context
import android.util.Log
import com.apex.agent.core.engine.templates.PromptTemplateRegistry
import com.apex.agent.core.engine.templates.runtime.TemplateRuntime
import com.apex.agent.core.engine.templates.runtime.builtin.BuiltinBehaviorCatalog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * #197 模板工坊 DI —— PromptTemplateRegistry 首次接线到 app 层。
 *
 * core 层的提示词模板库（此前仅有测试消费）落盘在
 * `<filesDir>/prompt_templates/templates.json`；scope 字段按分类自动
 * 分层（CODING → coding 工位，其余 → agent 工位）。
 *
 * ★ 内置模板播种修复（用户反馈「模板工坊切换没有反应」的数据面根因）：
 * 旧注释宣称「内置 10 套模板在构造时幂等播种」，但注册表 init 只建目录、
 * 生产代码从未调用 reseedBuiltIns —— 全新安装两个页签恒为空清单，切换
 * 页签看不到任何模板卡片。现于构造后异步播种（幂等：缺则补，不触碰
 * 用户已改动过的内置条目），首次访问前完成即可；onChanged 回调让
 * 正在收集清单的模板工坊 VM 自动刷出播种结果。
 */
@Module
@InstallIn(SingletonComponent::class)
object TemplateModule {

    @Provides
    @Singleton
    fun providePromptTemplateRegistry(
        @ApplicationContext context: Context
    ): PromptTemplateRegistry {
        val registry = PromptTemplateRegistry(
            storageDir = File(context.filesDir, "prompt_templates")
        )
        // 播种作用域与 PluginModule/ToolModule 同款纪律：单例自持
        // SupervisorJob + IO，异常只留痕不外抛（DI provide 不允许抛）。
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        seedScope.launch {
            runCatching { registry.reseedBuiltIns() }
                .onFailure { Log.w("TemplateModule", "seed built-in templates failed: ${it.message}") }
        }
        return registry
    }

    /**
     * 模板行为运行时（#20 步系列）：内置模板绑定代码行为（十行为 1:1 目录），
     * 自定义模板自动回落兜底行为（纯变量替换管线）。行为无状态，进程内共享安全。
     */
    @Provides
    @Singleton
    fun provideTemplateRuntime(
        registry: PromptTemplateRegistry
    ): TemplateRuntime = TemplateRuntime(
        registry = registry,
        behaviors = BuiltinBehaviorCatalog.ALL
    )
}
