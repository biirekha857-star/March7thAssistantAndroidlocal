package com.march7th.hkrpg.presentation.activation

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.march7th.hkrpg.data.permission.PermissionState
import com.march7th.hkrpg.domain.models.RemoteBackend
import com.march7th.hkrpg.theme.MaaDesignTokens
import com.march7th.hkrpg.theme.MaaThemeAlphas
import kotlinx.coroutines.delay

/**
 * 激活向导全屏界面。
 *
 * ## 设计意图
 *
 * 像手机开箱那样**一步只做一件事**：顶部一条进度指示，中间一个
 * 大图标 + 标题 + 说明，底部一个主按钮。必需步骤（提权、选客户端）
 * 不给"跳过"，非必需步骤（欢迎页）才允许跳过。
 *
 * ## 为什么不做成 Dialog
 *
 * Dialog 会让人下意识找"关闭"按钮，而这里的步骤是**必须完成**的；
 * 全屏 + 无返回键更符合"激活"的语义。
 *
 * @param step 当前步骤
 * @param permissionState 实时权限状态（决定权限步能否通过）
 * @param onStepChange 切步
 * @param onRequestRemoteAccess 请求提权
 * @param onRequestOverlay 请求悬浮窗
 * @param onRequestBattery 请求电池优化豁免
 * @param onRequestNotification 请求通知
 * @param onBackendChange 切换 Shizuku / Root
 * @param onClientChange 切换游戏客户端
 * @param onFinish 全部完成
 */
@Composable
fun ActivationWizard(
    step: ActivationStep,
    permissionState: PermissionState,
    backend: RemoteBackend,
    clientType: String,
    onStepChange: (ActivationStep) -> Unit,
    onRequestRemoteAccess: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestNotification: () -> Unit,
    onBackendChange: (RemoteBackend) -> Unit,
    onClientChange: (String) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val steps = remember { ActivationStep.entries.toList() }
    val index = steps.indexOf(step)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        StepIndicator(current = index, total = steps.size)

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val offset = if (forward) 1 else -1
                (slideInHorizontally(tween(260)) { it * offset / 3 } + fadeIn(tween(260)))
                    .togetherWith(
                        slideOutHorizontally(tween(260)) { -it * offset / 3 } + fadeOut(tween(200))
                    )
            },
            label = "activation-step",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { current ->
            when (current) {
                ActivationStep.WELCOME -> WelcomePage(
                    // 欢迎之后必须先过用户协议，再选提权方式
                    onNext = { onStepChange(ActivationStep.AGREEMENT) },
                )

                ActivationStep.AGREEMENT -> AgreementPage(
                    onAgree = { onStepChange(ActivationStep.BACKEND) },
                    onBack = { onStepChange(ActivationStep.WELCOME) },
                )

                ActivationStep.BACKEND -> BackendPage(
                    backend = backend,
                    permissionState = permissionState,
                    onBackendChange = onBackendChange,
                    onNext = { onStepChange(ActivationStep.PERMISSION) },
                    onBack = { onStepChange(ActivationStep.AGREEMENT) },
                )

                ActivationStep.PERMISSION -> PermissionPage(
                    permissionState = permissionState,
                    backend = backend,
                    onRequestRemoteAccess = onRequestRemoteAccess,
                    onRequestOverlay = onRequestOverlay,
                    onRequestBattery = onRequestBattery,
                    onRequestNotification = onRequestNotification,
                    onNext = { onStepChange(ActivationStep.CLIENT) },
                    onBack = { onStepChange(ActivationStep.BACKEND) },
                )

                ActivationStep.CLIENT -> ClientPage(
                    clientType = clientType,
                    onClientChange = onClientChange,
                    onNext = { onStepChange(ActivationStep.DONE) },
                    onBack = { onStepChange(ActivationStep.PERMISSION) },
                )

                ActivationStep.DONE -> DonePage(
                    onFinish = onFinish,
                )
            }
        }
    }
}

/** 顶部进度条：一排小胶囊，走过的是主色，未到的是灰。 */
@Composable
private fun StepIndicator(current: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = MaaDesignTokens.Spacing.xl,
                vertical = MaaDesignTokens.Spacing.lg,
            ),
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            val active = i <= current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }
    }
}

/** 步骤页骨架：大图标 + 标题 + 说明 + 底部按钮区。 */
@Composable
private fun StepScaffold(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
    actions: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaaDesignTokens.Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.lg),
        ) {
            Spacer(Modifier.height(MaaDesignTokens.Spacing.xl))

            // 图标：带主色光晕的圆底
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            content()
            Spacer(Modifier.height(MaaDesignTokens.Spacing.lg))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaaDesignTokens.Spacing.xl,
                    vertical = MaaDesignTokens.Spacing.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            actions()
        }
    }
}

/** 主按钮：全宽，走主题主色。 */
@Composable
private fun PrimaryAction(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.button),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun SecondaryAction(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================== 各步骤 ==============================

/**
 * 用户协议与免责声明页（强制阅读 7 秒）。
 *
 * ## 为什么放在这里
 *
 * 位置：欢迎页之后、选择提权方式之前 ——
 * 用户**还没授予任何高权限、程序还没碰游戏**的时候，先把风险讲清楚。
 *
 * ## 为什么强制看 7 秒
 *
 * 免责声明如果做成"直接点下一步"，绝大多数用户根本不会看。
 * 倒计时期间按钮上直接显示剩余秒数，用户不会误以为按钮坏了。
 *
 * ⚠️ 这是整个向导里**唯一不可跳过**的一步。
 */
@Composable
private fun AgreementPage(
    onAgree: () -> Unit,
    onBack: () -> Unit,
) {
    var remain by remember { mutableStateOf(AGREEMENT_READ_SECONDS) }
    LaunchedEffect(Unit) {
        while (remain > 0) {
            delay(1000)
            remain -= 1
        }
    }

    StepScaffold(
        icon = Icons.Rounded.Gavel,
        title = "用户协议与免责声明",
        body = "使用本工具前，请务必阅读以下内容。",
        content = {
            // ⚠️ **千万不要在这里再套 verticalScroll**
            //
            // StepScaffold 的外层 Column 已经带了
            // `.weight(1f).verticalScroll(...)`。嵌套滚动容器会抛：
            //   IllegalStateException: Vertically scrollable component was
            //   measured with an infinity maximum height constraints
            // 实测表现就是点「开始设置」直接闪退。
            //
            // 滚动交给 StepScaffold，这里只负责内容排布。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                WarningCard(
                    title = "⚠️ 封号风险由您自行承担",
                    body = "本工具是第三方自动化脚本，并非米哈游官方产品。" +
                        "使用任何自动化工具都存在被《崩坏：星穹铁道》判定为违规、" +
                        "进而导致账号封禁或限制的风险。\n\n" +
                        "该风险及由此产生的一切后果，完全由使用者本人承担，" +
                        "与作者、贡献者无关。请自行评估风险后再决定是否使用。",
                )
                WarningCard(
                    title = "🚫 请勿在米游社等官方社区讨论",
                    body = "请不要在米游社、官方论坛、官方 QQ 群等米哈游官方渠道" +
                        "提及、宣传或讨论本工具。\n\n" +
                        "在这些地方讨论自动化工具容易被判定为外挂宣传，" +
                        "既可能影响其他用户，也可能给本项目和您自己带来不必要的麻烦。\n\n" +
                        "如需交流，请使用项目自身的讨论渠道。",
                )
                InfoCard(
                    title = "本工具会申请以下权限",
                    lines = listOf(
                        "Shizuku 或 Root —— 创建虚拟屏幕、模拟点击触摸。",
                        "屏幕录制 —— 仅在虚拟屏幕内采集游戏画面用于识别。",
                        "悬浮窗 —— 显示悬浮球，方便随时停止任务。",
                        "通知 —— 展示任务进度与完成结果。",
                        "忽略电池优化 —— 防止后台任务被系统休眠中断。",
                        "网络 —— 检查更新与下载资源。",
                    ),
                )
                InfoCard(
                    title = "本工具不会做的事",
                    lines = listOf(
                        "不修改游戏客户端文件。",
                        "不注入游戏进程、不读取游戏内存。",
                        "不读取或上传您的账号、密码、验证码。",
                        "不收集您的个人信息或游戏数据。",
                        "所有识别与点击都在本机完成，离线运行。",
                    ),
                )
                Text(
                    text = "继续即表示您已阅读、理解并同意上述全部内容，" +
                        "并自愿承担使用本工具可能带来的全部风险。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        actions = {
            if (remain > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "请阅读上方条款… $remain 秒后可继续",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PrimaryAction(
                text = if (remain > 0) "同意并继续（$remain）" else "同意并继续",
                enabled = remain == 0,
                onClick = onAgree,
            )
            SecondaryAction(text = "返回", onClick = onBack)
        },
    )
}

/** 红色强调卡片：封号风险、社区提醒这类必须看到的警示。 */
@Composable
private fun WarningCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            lineHeight = 19.sp,
        )
    }
}

/** 普通信息卡片：权限清单、不做的事。 */
@Composable
private fun InfoCard(title: String, lines: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        lines.forEach { line ->
            Text(
                text = "· $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
            )
        }
    }
}

/** 协议页强制阅读秒数。 */
private const val AGREEMENT_READ_SECONDS = 7

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    StepScaffold(
        icon = Icons.Rounded.WavingHand,
        title = "欢迎使用崩铁助手",
        body = "这个工具能在后台自动帮你完成《崩坏：星穹铁道》的日常。" +
                "它会把游戏跑在一块独立的虚拟屏幕上，所以你可以正常用手机做别的事。\n\n" +
                "接下来几步会带你完成必要的初始化设置，大概需要一分钟。",
        actions = {
            PrimaryAction(text = "开始设置", onClick = onNext)
        },
    )
}

@Composable
private fun BackendPage(
    backend: RemoteBackend,
    permissionState: PermissionState,
    onBackendChange: (RemoteBackend) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    StepScaffold(
        icon = Icons.Rounded.Security,
        title = "选择提权方式",
        body = "崩铁助手需要较高权限才能创建虚拟屏幕、模拟点击。" +
                "两种方式选一个可用的即可。",
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                RemoteBackend.entries.forEach { candidate ->
                    val available = permissionState.isStartupBackendAvailable(candidate)
                    BackendOption(
                        backend = candidate,
                        selected = backend == candidate,
                        available = available,
                        onClick = { onBackendChange(candidate) },
                    )
                }
            }
        },
        actions = {
            PrimaryAction(
                text = "下一步",
                enabled = permissionState.isStartupBackendAvailable(backend),
                onClick = onNext,
            )
            SecondaryAction(text = "返回", onClick = onBack)
        },
    )
}

@Composable
private fun BackendOption(
    backend: RemoteBackend,
    selected: Boolean,
    available: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaaDesignTokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = backend.display,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = when {
                        available -> "已就绪"
                        backend == RemoteBackend.SHIZUKU -> "未检测到 Shizuku 服务"
                        else -> "未检测到 Root 权限"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (available) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionPage(
    permissionState: PermissionState,
    backend: RemoteBackend,
    onRequestRemoteAccess: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestNotification: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    StepScaffold(
        icon = Icons.Rounded.RocketLaunch,
        title = "授予必要权限",
        body = "点每一项右侧的按钮去授权。带「必需」标记的必须给，" +
                "否则任务无法运行。",
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                PermissionRow(
                    label = "${backend.display} 提权",
                    granted = permissionState.remoteAccessGranted,
                    required = true,
                    onRequest = onRequestRemoteAccess,
                )
                PermissionRow(
                    label = "悬浮窗",
                    granted = permissionState.overlay,
                    required = false,
                    onRequest = onRequestOverlay,
                )
                PermissionRow(
                    label = "忽略电池优化",
                    granted = permissionState.batteryWhitelist,
                    required = false,
                    onRequest = onRequestBattery,
                )
                PermissionRow(
                    label = "通知",
                    granted = permissionState.notification,
                    required = false,
                    onRequest = onRequestNotification,
                )
            }
        },
        actions = {
            PrimaryAction(
                text = "下一步",
                enabled = permissionState.remoteAccessGranted,
                onClick = onNext,
            )
            SecondaryAction(text = "返回", onClick = onBack)
        },
    )
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    required: Boolean,
    onRequest: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaaDesignTokens.Spacing.md,
                    vertical = MaaDesignTokens.Spacing.md,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (granted) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }

            Spacer(Modifier.width(MaaDesignTokens.Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (required) {
                    Text(
                        text = "必需",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = MaaThemeAlphas.MEDIUM),
                    )
                }
            }

            if (!granted) {
                OutlinedButton(onClick = onRequest) {
                    Text("授权")
                }
            }
        }
    }
}

@Composable
private fun ClientPage(
    clientType: String,
    onClientChange: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    StepScaffold(
        icon = Icons.Rounded.SportsEsports,
        title = "选择游戏客户端",
        body = "选你实际安装的崩铁版本。国服和国际服是两个不同的应用。",
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            ) {
                ClientOption(
                    label = "国服",
                    pkg = "com.miHoYo.hkrpg",
                    selected = clientType == "Official",
                    onClick = { onClientChange("Official") },
                )
                ClientOption(
                    label = "国际服",
                    pkg = "com.HoYoverse.hkrpgoversea",
                    selected = clientType == "YoStarEN",
                    onClick = { onClientChange("YoStarEN") },
                )
            }
        },
        actions = {
            PrimaryAction(text = "下一步", onClick = onNext)
            SecondaryAction(text = "返回", onClick = onBack)
        },
    )
}

@Composable
private fun ClientOption(
    label: String,
    pkg: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaaDesignTokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = pkg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DonePage(onFinish: () -> Unit) {
    StepScaffold(
        icon = Icons.Rounded.Check,
        title = "设置完成",
        body = "一切就绪。现在可以去「任务」页勾选要做的事情，" +
                "点「开始任务」就会在后台自动完成。",
        actions = {
            PrimaryAction(text = "进入应用", onClick = onFinish)
        },
    )
}
