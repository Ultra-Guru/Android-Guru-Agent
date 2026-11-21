package com.apex.agent.ui.screen.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.apex.agent.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 新手引导第 3 页 —— 权限全量引导（v2）。
 *
 * 八个步骤一张可滚动卡片列表，用户按需逐项授权：
 *  1 存储（API < 33）          2 所有文件访问（API 30+）
 *  3 修改系统设置              4 安装未知来源应用
 *  5 忽略电池优化              6 悬浮窗（补充项）
 *  7 通知                      8 无障碍（进阶，可选补充项）
 * （第 9 步「配置工作区」独立成页 —— 见 [OnboardingWorkspacePage]。）
 *
 * 交互约定（对齐官方 requesting-permissions 的「不阻断用户」原则）：
 *  - 所有步骤零门控可跳过，不做任何「必须授权才能继续」的强制；
 *  - 未授权时按钮文案「去授权」，授权后自动变「已授权」并停用；
 *  - 当前系统版本不适用的步骤显示「无需」（如 API 33+ 的传统存储权限），
 *    不计入顶部进度（x/y）分母；
 *  - 跳系统设置授权后返回（ON_RESUME）自动回填状态 —— 与抽屉权限页
 *    PermissionsScreen 的双重刷新模式同源。
 */
@Composable
internal fun OnboardingPermissionsPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 步骤 → 状态。空 map = 首次检测尚未回来（卡片渲染中性态）。
    var statuses by remember {
        mutableStateOf<Map<OnboardingPermissionKind, OnboardingPermissionStatus>>(emptyMap())
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val fresh = OnboardingPermissionKind.entries.associateWith {
            OnboardingPermissionChecks.check(it, context)
        }
        withContext(Dispatchers.Main) { statuses = fresh }
    }

    // 首次组合检测一次；从系统设置页返回（ON_RESUME）再检测一次 ——
    // 用户在设置里手动打开开关后回来，状态要即时「未获得 → 已获得」。
    LaunchedEffect(Unit) { refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { refresh() }
    }

    // 运行时权限（存储 / 通知）：系统对话框不离开本 Activity，
    // 回调里立即回填状态。
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> scope.launch { refresh() } }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> scope.launch { refresh() } }

    fun request(kind: OnboardingPermissionKind) {
        when (kind) {
            OnboardingPermissionKind.STORAGE -> storageLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
            // Android 13+ 走标准运行时弹窗；旧版本通知默认开，
            // 关闭过的用户跳本应用通知设置页手动开。
            OnboardingPermissionKind.NOTIFICATION ->
                if (Build.VERSION.SDK_INT >= 33) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    OnboardingPermissionIntents.launchSettings(kind, context)
                    scope.launch { refresh() }
                }
            // 特殊权限：跳系统设置页，ON_RESUME 负责回填。
            else -> OnboardingPermissionIntents.launchSettings(kind, context)
        }
    }

    val steps = permissionSteps()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_perms_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_perms_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))

        // 进度徽标：已授权数 / 适用步骤数（「无需」的不计入分母）
        val applicable = statuses.values.filter { !it.notApplicable }
        if (applicable.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            ) {
                Text(
                    stringResource(
                        R.string.onboarding_perms_progress,
                        applicable.count { it.granted },
                        applicable.size
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        steps.forEach { step ->
            PermissionStepCard(
                step = step,
                status = statuses[step.kind],
                onRequest = { request(step.kind) }
            )
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.onboarding_perm_skip_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** 步骤展示模型（title/description 在组合期经 stringResource 取词，跟随语言切换）。 */
private data class PermissionStep(
    val kind: OnboardingPermissionKind,
    /** 引导序号（1-7）；null = 进阶补充项，不占序号。 */
    val order: Int?,
    val icon: ImageVector,
    val title: String,
    val description: String
)

@Composable
private fun permissionSteps(): List<PermissionStep> = listOf(
    PermissionStep(
        OnboardingPermissionKind.STORAGE, 1, Icons.Default.Save,
        stringResource(R.string.onboarding_perm_step_storage_title),
        stringResource(R.string.onboarding_perm_step_storage_desc)
    ),
    PermissionStep(
        OnboardingPermissionKind.ALL_FILES, 2, Icons.Default.FolderOpen,
        stringResource(R.string.onboarding_perm_step_allfiles_title),
        stringResource(R.string.onboarding_perm_step_allfiles_desc)
    ),
    PermissionStep(
        OnboardingPermissionKind.WRITE_SETTINGS, 3, Icons.Default.Tune,
        stringResource(R.string.onboarding_perm_step_writesettings_title),
        stringResource(R.string.onboarding_perm_step_writesettings_desc)
    ),
    PermissionStep(
        OnboardingPermissionKind.INSTALL_UNKNOWN, 4, Icons.Default.SystemUpdate,
        stringResource(R.string.onboarding_perm_step_install_title),
        stringResource(R.string.onboarding_perm_step_install_desc)
    ),
    PermissionStep(
        OnboardingPermissionKind.BATTERY, 5, Icons.Default.BatteryChargingFull,
        stringResource(R.string.onboarding_perm_step_battery_title),
        stringResource(R.string.onboarding_perm_step_battery_desc)
    ),
    PermissionStep(
        OnboardingPermissionKind.OVERLAY, 6, Icons.Default.Layers,
        stringResource(R.string.onboarding_perm_step_overlay_title),
        stringResource(R.string.onboarding_perm_step_overlay_desc)
    ),
    PermissionStep(
        OnboardingPermissionKind.NOTIFICATION, 7, Icons.Default.Notifications,
        stringResource(R.string.onboarding_perm_notif_title),
        stringResource(R.string.onboarding_perm_notif_desc)
    ),
    PermissionStep(
        OnboardingPermissionKind.ACCESSIBILITY, null, Icons.Default.Accessibility,
        stringResource(R.string.onboarding_perm_step_accessibility_title),
        stringResource(R.string.onboarding_perm_step_accessibility_desc)
    )
)

/**
 * 单步授权卡片 —— 与抽屉权限页 PermissionCard 同款骨架（44dp 圆形图标 chip +
 * 标题行状态徽标 + 右侧按钮），叠加引导序号徽章与「无需 / 进阶」态。
 */
@Composable
private fun PermissionStepCard(
    step: PermissionStep,
    status: OnboardingPermissionStatus?,
    onRequest: () -> Unit
) {
    val granted = status?.granted == true
    val notApplicable = status?.notApplicable == true
    // 检测未回来（null）：中性灰，不渲染错误色吓用户
    val accent = when {
        granted || notApplicable -> MaterialTheme.colorScheme.primary
        status == null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.secondary
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = accent.copy(alpha = 0.15f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(step.icon, null, tint = accent, modifier = Modifier.size(24.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    step.order?.let {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                        ) {
                            Text(
                                it.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        step.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    StepStatusPill(
                        text = when {
                            notApplicable -> stringResource(R.string.onboarding_perm_state_not_needed)
                            granted -> stringResource(R.string.onboarding_perm_granted)
                            else -> null
                        },
                        accent = MaterialTheme.colorScheme.primary
                    )
                    if (step.order == null) {
                        StepStatusPill(
                            text = stringResource(R.string.onboarding_perm_state_advanced),
                            accent = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    step.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 右侧行动按钮：无需 → 停用「无需」；已授权 → 停用「已授权」；
            // 未授权（或检测中）→「去授权」。全部步骤可跳过 = 不点即跳过。
            val buttonEnabled = status != null && !granted && !notApplicable
            Button(
                onClick = onRequest,
                enabled = buttonEnabled,
                colors = if (buttonEnabled) {
                    ButtonDefaults.buttonColors()
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            ) {
                Text(
                    when {
                        notApplicable -> stringResource(R.string.onboarding_perm_state_not_needed)
                        granted -> stringResource(R.string.onboarding_perm_granted)
                        else -> stringResource(R.string.onboarding_perm_action)
                    }
                )
            }
        }
    }
}

/** 紧凑状态徽标（沿 StatusPill 形态：accent 14% 底 + labelSmall）。text 为 null 时不渲染。 */
@Composable
private fun StepStatusPill(text: String?, accent: Color) {
    if (text == null) return
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = accent.copy(alpha = 0.14f)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = accent,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
