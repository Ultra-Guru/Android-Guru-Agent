package com.apex.agent.ui.screen.code

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.marketplace.SearchMcpInstaller
import com.apex.agent.marketplace.SearchMcpPreset
import com.apex.agent.platform.terminal.searxng.SearxngLifecycleCoordinator
import com.apex.agent.platform.terminal.searxng.SearxngState
import com.apex.agent.service.ApexCoreService
import com.apex.agent.searxng.SearxngSettingsStore
import com.apex.agent.ui.chatkit.ChatToolkitStore
import com.apex.agent.ui.chatkit.SearchChannelHint
import com.apex.agent.ui.component.FeedbackSeverity
import com.apex.agent.ui.component.LocalFeedbackController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * 网络搜索通道 —— Coding 工位输入栏「🌐 网络搜索」按钮的三选一语义
 * （2026-10 用户规格：单选互斥，选中自动停其它；MCP 通道子级三台再选一）。
 *
 * - [BUILTIN]：模型内置搜索 —— [ChatToolkitStore] 的 webSearch 开关
 *   （进程内 BuiltinSearchMcpTransport，三级供应商回退链）；
 * - [SEARXNG]：本地 SearXNG —— 内置搜索开 + SearXNG 服务启动（SearXNG
 *   本就是内置搜索回退链的链首源，服务在位即本地优先）；未安装时引导
 *   设置中心「本地搜索」安装；
 * - [MCP_FREE_SEARCH] / [MCP_SEARCHLIGHT] / [MCP_AGENT_SEARCH]：三台
 *   零 key 本地搜索 MCP（[SearchMcpPreset]）—— 首次选择沙箱真实安装
 *   （pip/npm），启用所选台并停其余两台与内置开关。
 */
enum class WebSearchChannel(val preset: SearchMcpPreset?) {
    BUILTIN(null),
    SEARXNG(null),
    MCP_FREE_SEARCH(SearchMcpPreset.FREE_SEARCH),
    MCP_SEARCHLIGHT(SearchMcpPreset.SEARCHLIGHT),
    MCP_AGENT_SEARCH(SearchMcpPreset.AGENT_SEARCH);

    companion object {
        fun byPreset(preset: SearchMcpPreset): WebSearchChannel =
            entries.first { it.preset == preset }
    }
}

/** 通道切换计划（纯数据，单测锚点）：activate=null = 关闭当前通道。 */
data class WebSearchChannelPlan(
    val activate: WebSearchChannel?,
    val deactivate: WebSearchChannel?
)

/**
 * 通道选择纯逻辑：单选互斥 —— 再点当前通道 = 关闭（给搜索留总闸）；
 * 点其它通道 = 停旧开新。
 */
object WebSearchChannelLogic {
    fun planToggle(current: WebSearchChannel?, target: WebSearchChannel): WebSearchChannelPlan =
        if (current == target) {
            WebSearchChannelPlan(activate = null, deactivate = target)
        } else {
            WebSearchChannelPlan(activate = target, deactivate = current)
        }
}

/** 通道选择持久化（SharedPreferences 轻量单值，与 MirrorPrefs 同款档位）。 */
object WebSearchChannelStore {
    private const val PREFS = "code_websearch_prefs"
    private const val KEY_CHANNEL = "web_search_channel"

    fun load(context: Context): WebSearchChannel? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CHANNEL, null)
            ?.let { stored -> WebSearchChannel.entries.firstOrNull { it.name == stored } }

    fun save(context: Context, channel: WebSearchChannel?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHANNEL, channel?.name)
            .apply()
    }
}

/** 通道切换执行器：把 [WebSearchChannelPlan] 落到 toolkit/SearXNG/MCP 三套基建。 */
class WebSearchChannelEffects(
    private val context: Context,
    private val toolkit: ChatToolkitStore,
    private val mcpManager: McpManager,
    private val installer: SearchMcpInstaller
) {

    /**
     * 应用切换计划。顺序 = 先开新（MCP 安装失败时旧通道原样保留，不出现
     * 「双无」）→ 智能停旧（同基建复用时不误关，如 BUILTIN→SEARXNG 都靠
     * webSearch 开关）→ 通道提示殿后落库（模型侧 buildSessionContext 注入）。
     * MCP 通道未安装时先沙箱安装（失败返回 false 并把错误文案交给
     * [onError] 供反馈条展示）。返回 true = 通道态已落盘生效。
     */
    suspend fun apply(plan: WebSearchChannelPlan, onError: (String) -> Unit): Boolean {
        // ① 开新：MCP 先保证安装（失败即中止，旧通道态不变）
        val target = plan.activate
        if (target?.preset != null) {
            val installed = runCatching { installer.ensureInstalled(target.preset) }
            if (installed.isFailure) {
                onError(installed.exceptionOrNull()?.message ?: "搜索 MCP 安装失败")
                return false
            }
            val enabled = installer.setEnabled(target.preset, true)
            if (enabled.isFailure) {
                onError(enabled.exceptionOrNull()?.message ?: "搜索 MCP 启用失败")
                return false
            }
            // MCP 通道与内置搜索互斥：模型侧搜索链关停，工具面只剩 MCP
            toolkit.setWebSearchEnabled(false)
        } else if (target == WebSearchChannel.BUILTIN) {
            toolkit.setWebSearchEnabled(true)
        } else if (target == WebSearchChannel.SEARXNG) {
            // SearXNG 是内置搜索回退链首源：服务在位即本地优先
            toolkit.setWebSearchEnabled(true)
            SearxngSettingsStore(context).setEnabled(true)
            runCatching {
                val intent = Intent(context, ApexCoreService::class.java).apply {
                    action = ApexCoreService.ACTION_START_SEARXNG
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }
        // 三台搜索 MCP 互斥：启用任一台时其余两台静默停用
        target?.preset?.let { active ->
            SearchMcpPreset.entries
                .filter { it != active }
                .forEach { runCatching { installer.setEnabled(it, false) } }
        }
        // ② 停旧：同基建复用时不误关（BUILTIN↔SEARXNG 都靠 webSearch 开关，
        // 互切时不能把新通道刚打开的开关又关掉）
        plan.deactivate?.let { old ->
            when (old) {
                WebSearchChannel.BUILTIN, WebSearchChannel.SEARXNG ->
                    if (target == null || target.preset != null) toolkit.setWebSearchEnabled(false)
                else -> old.preset?.let { installer.setEnabled(it, false) }
            }
        }
        // ③ 通道提示殿后落库（模型侧 buildSessionContext 据此注入调用指引；
        // 关闭通道 = 置空回到 legacy 内置指引）
        toolkit.setSearchChannelHint(
            when (target) {
                WebSearchChannel.BUILTIN -> SearchChannelHint(SearchChannelHint.Kind.BUILTIN)
                WebSearchChannel.SEARXNG -> SearchChannelHint(SearchChannelHint.Kind.SEARXNG)
                null -> null
                else -> SearchChannelHint(SearchChannelHint.Kind.MCP, target.preset?.serverName)
            }
        )
        return true
    }
}

/** Compose 中获取搜索 MCP 安装器（EntryPoint 模式，与 FusionBundleController 同款）。 */
@Composable
private fun rememberSearchMcpInstaller(): SearchMcpInstaller {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            WebSearchChannelEntryPoint::class.java
        ).searchMcpInstaller()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WebSearchChannelEntryPoint {
    fun searchMcpInstaller(): SearchMcpInstaller
    fun searxngLifecycleCoordinator(): SearxngLifecycleCoordinator
}

/**
 * 🌐 网络搜索通道按钮（工具行第 2 位）：点击弹单选菜单
 * （内置搜索 / SearXNG 本地 / 搜索 MCP▸三台子级）；有激活通道时按钮
 * 主色高亮（与 ToolkitRing 的 anyActive 同口径）；再点当前通道 = 关闭
 * （搜索总闸）。选中 MCP 未安装时先沙箱安装（菜单内进度提示），成功
 * 即启用。
 */
@Composable
fun WebSearchMenuButton(
    toolkit: ChatToolkitStore,
    mcpManager: McpManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val feedback = LocalFeedbackController.current
    val scope = rememberCoroutineScope()
    val installer = rememberSearchMcpInstaller()
    // SearXNG 状态流直通（INV4：状态由 Coordinator 驱动）—— 菜单内动态
    // 副标题让「本地服务跑没跑」在 Code 工位直接可见，不必跳设置页
    val searxngCoordinator = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            WebSearchChannelEntryPoint::class.java
        ).searxngLifecycleCoordinator()
    }
    val searxngState by searxngCoordinator.state.collectAsStateWithLifecycle()
    val effects = remember(context, toolkit, mcpManager, installer) {
        WebSearchChannelEffects(context, toolkit, mcpManager, installer)
    }

    var channel by remember { mutableStateOf(WebSearchChannelStore.load(context)) }
    var menuOpen by remember { mutableStateOf(false) }
    var mcpExpanded by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    val cd = stringResource(R.string.code_websearch_cd)

    fun toggle(target: WebSearchChannel) {
        if (installing) return
        val plan = WebSearchChannelLogic.planToggle(channel, target)
        val needsInstall = plan.activate?.preset?.let { !installer.isInstalled(it) } == true
        scope.launch {
            if (needsInstall) installing = true
            val ok = effects.apply(plan) { message ->
                feedback.show(message, FeedbackSeverity.ERROR)
            }
            installing = false
            if (ok) {
                if (needsInstall) {
                    feedback.show(
                        context.getString(R.string.code_websearch_install_done),
                        FeedbackSeverity.SUCCESS
                    )
                }
                if (plan.activate == WebSearchChannel.SEARXNG) {
                    // 首次启用会触发 SearXNG 安装（apt/clone/venv 分钟级）——
                    // Code 工位看不到进度条，指路设置中心的状态机页面
                    feedback.show(
                        context.getString(R.string.code_websearch_searxng_on_hint),
                        FeedbackSeverity.INFO
                    )
                }
                channel = plan.activate
                WebSearchChannelStore.save(context, plan.activate)
            }
            menuOpen = false
        }
    }

    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .background(
                color = if (channel != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = CircleShape
            )
            .semantics { contentDescription = cd }
            .clickable { menuOpen = !menuOpen },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Public,
            contentDescription = null,
            tint = if (channel != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.width(280.dp)
        ) {
            // ═══ 模型内置搜索 ═══
            ChannelItem(
                title = stringResource(R.string.code_websearch_builtin),
                subtitle = stringResource(R.string.code_websearch_builtin_desc),
                selected = channel == WebSearchChannel.BUILTIN,
                enabled = !installing,
                onClick = { toggle(WebSearchChannel.BUILTIN) }
            )
            // ═══ SearXNG 本地（副标题随 Coordinator 状态机实时变化）═══
            ChannelItem(
                title = stringResource(R.string.code_websearch_searxng),
                subtitle = searxngStatusLabel(searxngState),
                selected = channel == WebSearchChannel.SEARXNG,
                enabled = !installing,
                onClick = { toggle(WebSearchChannel.SEARXNG) }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            // ═══ 搜索 MCP（二级展开：三台零 key 本地服务器，单选三选一）═══
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { mcpExpanded = !mcpExpanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.code_websearch_mcp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.code_websearch_mcp_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (mcpExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(
                visible = mcpExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    SearchMcpPreset.entries.forEach { preset ->
                        ChannelItem(
                            title = presetTitle(preset),
                            subtitle = presetSubtitle(preset),
                            selected = channel == WebSearchChannel.byPreset(preset),
                            enabled = !installing,
                            installing = installing && channel?.preset == preset,
                            onClick = { toggle(WebSearchChannel.byPreset(preset)) }
                        )
                    }
                }
            }
            if (installing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.code_websearch_install_progress),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 单选行：选中 ✓ + 两行文案；[installing] = 该台沙箱安装中（尾巴转圈）。 */
@Composable
private fun ChannelItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    installing: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold
                else androidx.compose.ui.text.font.FontWeight.Normal
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (installing) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        } else if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 三台 MCP 的展示名（产品名不做翻译）。 */
private fun presetTitle(preset: SearchMcpPreset): String = when (preset) {
    SearchMcpPreset.FREE_SEARCH -> "free-search-mcp"
    SearchMcpPreset.SEARCHLIGHT -> "Searchlight"
    SearchMcpPreset.AGENT_SEARCH -> "Agent Search MCP"
}

/** 三台 MCP 的一句话定位（i18n）。 */
@Composable
private fun presetSubtitle(preset: SearchMcpPreset): String = stringResource(
    when (preset) {
        SearchMcpPreset.FREE_SEARCH -> R.string.code_websearch_mcp_fs_desc
        SearchMcpPreset.SEARCHLIGHT -> R.string.code_websearch_mcp_sl_desc
        SearchMcpPreset.AGENT_SEARCH -> R.string.code_websearch_mcp_as_desc
    }
)

/** SearXNG 状态机 → 菜单副标题（Off/Stopping 走静态描述，其余如实播报）。 */
@Composable
private fun searxngStatusLabel(state: SearxngState): String = when (state) {
    is SearxngState.Ready -> stringResource(R.string.code_websearch_searxng_ready)
    is SearxngState.Installing, is SearxngState.Starting,
    is SearxngState.Restarting, is SearxngState.Stopping ->
        stringResource(R.string.code_websearch_searxng_busy)
    is SearxngState.Failed -> stringResource(R.string.code_websearch_searxng_failed)
    SearxngState.Off -> stringResource(R.string.code_websearch_searxng_desc)
}
