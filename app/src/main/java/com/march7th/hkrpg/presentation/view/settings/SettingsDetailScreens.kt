package com.march7th.hkrpg.presentation.view.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.march7th.hkrpg.R
import kotlinx.coroutines.delay
import com.march7th.hkrpg.constant.Routes
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.models.RemoteBackend
import com.march7th.hkrpg.domain.models.UnlockGesture
import com.march7th.hkrpg.domain.models.UnlockStep
import com.march7th.hkrpg.presentation.components.AdaptiveTaskPromptDialog
import com.march7th.hkrpg.presentation.components.CollapsibleSection
import com.march7th.hkrpg.presentation.components.ListItemDivider
import com.march7th.hkrpg.presentation.components.LogExportController
import com.march7th.hkrpg.data.model.InstalledTaskPack
import com.march7th.hkrpg.data.resource.RemoteTaskPack
import com.march7th.hkrpg.presentation.components.SettingsGroupCard
import com.march7th.hkrpg.presentation.viewmodel.SettingsViewModel
import com.march7th.hkrpg.theme.AppAnimatedVisibility
import com.march7th.hkrpg.theme.AppDesignTokens
import com.march7th.hkrpg.utils.i18n.LocaleBootstrap.resolveSelectedLanguage
import com.march7th.hkrpg.utils.i18n.resolve
import org.koin.androidx.compose.koinViewModel

// ============================================================
// 通用：语言 / 主题 / 自定义背景
// ============================================================

@Composable
fun GeneralSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val language by viewModel.language.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val useSystemMonetColor by viewModel.useSystemMonetColor.collectAsStateWithLifecycle()

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

    val effectiveLanguage = resolveSelectedLanguage(language)
    val languageLabel = stringResource(
        when (effectiveLanguage) {
            AppSettingsManager.AppLanguage.EN -> R.string.settings_language_en
            else -> R.string.settings_language_zh
        }
    )
    val themeLabel = stringResource(
        when (themeMode) {
            AppSettingsManager.ThemeMode.SYSTEM -> R.string.settings_theme_system
            AppSettingsManager.ThemeMode.WHITE -> R.string.settings_theme_white
            AppSettingsManager.ThemeMode.DARK -> R.string.settings_theme_dark
            AppSettingsManager.ThemeMode.PURE_DARK -> R.string.settings_theme_pure_dark
        }
    )

    SettingsDetailScreen(
        title = stringResource(R.string.settings_category_general),
        navController = navController,
    ) {
        item {
            SettingsGroupCard {
                SettingClickItem(
                    title = stringResource(R.string.settings_language_title),
                    value = languageLabel,
                    onClick = { showLanguageDialog = true },
                )
                ListItemDivider()
                SettingClickItem(
                    title = stringResource(R.string.settings_theme_title),
                    value = themeLabel,
                    onClick = { showThemeDialog = true },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ListItemDivider()
                    SettingSwitchItem(
                        title = stringResource(R.string.settings_monet_color_title),
                        description = stringResource(R.string.settings_monet_color_desc),
                        checked = useSystemMonetColor,
                        onCheckedChange = { viewModel.setUseSystemMonetColor(it) },
                    )
                }
            }
        }

        item {
            SettingsGroupCard {
                SettingClickItem(
                    title = stringResource(R.string.settings_background_title),
                    description = stringResource(R.string.settings_background_desc),
                    onClick = { navController.navigate(Routes.WALLPAPER) },
                )
            }
        }
    }

    if (showLanguageDialog) {
        SettingOptionDialog(
            title = stringResource(R.string.settings_language_title),
            options = listOf(
                AppSettingsManager.AppLanguage.ZH to stringResource(R.string.settings_language_zh),
                AppSettingsManager.AppLanguage.EN to stringResource(R.string.settings_language_en),
            ),
            selected = effectiveLanguage,
            onSelect = { viewModel.setLanguage(it) },
            onDismiss = { showLanguageDialog = false },
        )
    }

    if (showThemeDialog) {
        SettingOptionDialog(
            title = stringResource(R.string.settings_theme_title),
            options = listOf(
                AppSettingsManager.ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                AppSettingsManager.ThemeMode.WHITE to stringResource(R.string.settings_theme_white),
                AppSettingsManager.ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
                AppSettingsManager.ThemeMode.PURE_DARK to stringResource(R.string.settings_theme_pure_dark),
            ),
            selected = themeMode,
            onSelect = { viewModel.setThemeMode(it) },
            onDismiss = { showThemeDialog = false },
        )
    }
}

// ============================================================
// 启动与运行：启动模式 / 全屏 / 画中画 / 解锁
// ============================================================

@Composable
fun LaunchSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val startupBackend by viewModel.startupBackend.collectAsStateWithLifecycle()
    val skipShizukuCheck by viewModel.skipShizukuCheck.collectAsStateWithLifecycle()
    val forceFullscreenOnVirtualDisplay by viewModel.forceFullscreenOnVirtualDisplay.collectAsStateWithLifecycle()
    val pipOnHome by viewModel.pipOnHome.collectAsStateWithLifecycle()
    val wakeUnlockType by viewModel.wakeUnlockType.collectAsStateWithLifecycle()
    val wakeCredential by viewModel.wakeCredential.collectAsStateWithLifecycle()
    val wakeTestState by viewModel.wakeTestState.collectAsStateWithLifecycle()
    val unlockGesture by viewModel.unlockGesture.collectAsStateWithLifecycle()
    val gestureRecordState by viewModel.gestureRecordState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var showBackendDialog by remember { mutableStateOf(false) }
    var showForceFullscreenConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshGestureRecord() }

    wakeTestState?.let { state ->
        LaunchedEffect(state) {
            val done = state as? SettingsViewModel.WakeTestState.Done ?: return@LaunchedEffect
            Toast.makeText(context, done.result.message.resolve(context), Toast.LENGTH_LONG).show()
            viewModel.clearWakeTestResult()
        }
    }

    gestureRecordState?.let { state ->
        val doneTemplate = stringResource(R.string.settings_wake_gesture_done)
        LaunchedEffect(state) {
            val message = when (state) {
                is SettingsViewModel.GestureRecordState.Done -> doneTemplate.format(state.steps)
                is SettingsViewModel.GestureRecordState.Failed -> state.result.message.resolve(context)
                else -> return@LaunchedEffect
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.clearGestureRecordState()
        }
    }

    SettingsDetailScreen(
        title = stringResource(R.string.settings_category_launch),
        navController = navController,
    ) {
        item {
            SettingsGroupCard {
                SettingClickItem(
                    title = stringResource(R.string.settings_startup_backend_title),
                    description = stringResource(R.string.settings_startup_backend_desc),
                    value = startupBackend.display,
                    onClick = { showBackendDialog = true },
                )
                ListItemDivider()
                SettingSwitchItem(
                    title = stringResource(R.string.settings_skip_shizuku_check),
                    checked = skipShizukuCheck,
                    enabled = startupBackend == RemoteBackend.SHIZUKU,
                    onCheckedChange = { viewModel.setSkipShizukuCheck(it) },
                )
            }
        }

        item {
            SettingsGroupCard {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_force_fullscreen_on_virtual_display),
                    description = stringResource(R.string.settings_force_fullscreen_on_virtual_display_desc),
                    checked = forceFullscreenOnVirtualDisplay,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            showForceFullscreenConfirm = true
                        } else {
                            viewModel.setForceFullscreenOnVirtualDisplay(false)
                        }
                    },
                )
                ListItemDivider()
                SettingSwitchItem(
                    title = stringResource(R.string.settings_pip_on_home),
                    description = stringResource(R.string.settings_pip_on_home_desc),
                    checked = pipOnHome,
                    onCheckedChange = { viewModel.setPipOnHome(it) },
                )
            }
        }

        item {
            SettingsGroupCard {
                SettingWakeUnlockTypeItem(
                    selectedType = wakeUnlockType,
                    onTypeSelected = { viewModel.setWakeUnlockType(it) },
                )
                AppAnimatedVisibility(
                    visible = wakeUnlockType == AppSettingsManager.WAKE_TYPE_PIN,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column {
                        ListItemDivider()
                        SettingWakePinSection(
                            wakeCredential = wakeCredential,
                            onCredentialChange = { viewModel.setWakeCredential(it) },
                            onTest = { viewModel.runWakeTest() },
                        )
                    }
                }
                AppAnimatedVisibility(
                    visible = wakeUnlockType == AppSettingsManager.WAKE_TYPE_GESTURE,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column {
                        ListItemDivider()
                        SettingWakeGestureSection(
                            gesture = unlockGesture,
                            recordState = gestureRecordState,
                            onRecord = { viewModel.startGestureRecord() },
                            onCancelRecord = { viewModel.cancelGestureRecord() },
                            onClear = { viewModel.clearGesture() },
                            onTest = { viewModel.runWakeTest() },
                        )
                    }
                }
            }
        }
    }

    if (showBackendDialog) {
        SettingOptionDialog(
            title = stringResource(R.string.settings_startup_backend_title),
            options = RemoteBackend.entries.map { it to it.display },
            selected = startupBackend,
            onSelect = { viewModel.setStartupBackend(it) },
            onDismiss = { showBackendDialog = false },
        )
    }

    if (showForceFullscreenConfirm) {
        AdaptiveTaskPromptDialog(
            visible = true,
            title = stringResource(R.string.dialog_enable_force_fullscreen_title),
            message = stringResource(R.string.dialog_enable_force_fullscreen_message),
            onConfirm = {
                showForceFullscreenConfirm = false
                viewModel.setForceFullscreenOnVirtualDisplay(true)
            },
            onDismissRequest = { showForceFullscreenConfirm = false },
            confirmText = stringResource(R.string.common_confirm),
            dismissText = stringResource(R.string.common_cancel),
            icon = Icons.Rounded.Build,
        )
    }
}

@Composable
private fun SettingWakeUnlockTypeItem(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
) {
    // 三个选项字宽差得多，单选圆点排一行会左右不齐
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppDesignTokens.Spacing.listItemVertical),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_wake_unlock_type),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val options = listOf(
            AppSettingsManager.WAKE_TYPE_SWIPE to
                    stringResource(R.string.settings_wake_unlock_type_swipe),
            AppSettingsManager.WAKE_TYPE_GESTURE to
                    stringResource(R.string.settings_wake_unlock_type_gesture),
            AppSettingsManager.WAKE_TYPE_PIN to
                    stringResource(R.string.settings_wake_unlock_type_pin),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (type, label) ->
                SegmentedButton(
                    selected = type == selectedType,
                    onClick = { onTypeSelected(type) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size,
                        baseShape = RoundedCornerShape(4.dp),
                    ),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 锁屏 PIN 输入框：只收数字、限长、可临时明文查看。
 *
 * 原来那份是设置页里通用的「密钥输入」组件，随上报设置一起成了死代码，
 * 这里只保留定时任务解锁实际需要的形态。
 */
@Composable
private fun WakePinField(
    wakeCredential: String,
    onCredentialChange: (String) -> Unit,
) {
    var local by rememberSaveable { mutableStateOf(wakeCredential) }
    var visible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(wakeCredential) {
        if (wakeCredential != local) local = wakeCredential
    }
    OutlinedTextField(
        value = local,
        onValueChange = { raw ->
            val next = raw.filter(Char::isDigit).take(AppSettingsManager.MAX_PIN_LENGTH)
            local = next
            onCredentialChange(next)
        },
        label = { Text(stringResource(R.string.settings_wake_credential)) },
        placeholder = { Text(stringResource(R.string.settings_wake_credential_hint)) },
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.settings_secret_hide else R.string.settings_secret_show,
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingWakePinSection(
    wakeCredential: String,
    onCredentialChange: (String) -> Unit,
    onTest: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        WakePinField(
            wakeCredential = wakeCredential,
            onCredentialChange = onCredentialChange,
        )
        Text(
            text = stringResource(R.string.settings_wake_credential_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        SettingClickItem(
            title = stringResource(R.string.settings_wake_test_button),
            description = stringResource(R.string.settings_wake_test_hint),
            onClick = onTest,
        )
    }
}

@Composable
private fun SettingWakeGestureSection(
    gesture: UnlockGesture?,
    recordState: SettingsViewModel.GestureRecordState?,
    onRecord: () -> Unit,
    onCancelRecord: () -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit,
) {
    val recording = recordState is SettingsViewModel.GestureRecordState.Preparing ||
            recordState is SettingsViewModel.GestureRecordState.Recording

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (gesture == null) {
            Text(
                text = stringResource(R.string.settings_wake_gesture_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            // 步骤只在需要核对时才看，默认收起
            CollapsibleSection(
                sectionKey = "settings_wake_gesture_steps",
                initiallyExpanded = false,
                title = {
                    Text(
                        text = stringResource(
                            R.string.settings_wake_gesture_summary,
                            gesture.steps.size,
                            gesture.screenWidth,
                            gesture.screenHeight,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                },
            ) {
                gesture.steps.forEachIndexed { index, step ->
                    GestureStepRow(index = index, step = step)
                }
            }
        }

        Text(
            text = stringResource(R.string.settings_wake_gesture_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (recording) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(
                        if (recordState is SettingsViewModel.GestureRecordState.Preparing) {
                            R.string.settings_wake_gesture_preparing
                        } else {
                            R.string.settings_wake_gesture_waiting
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            OutlinedButton(
                onClick = onCancelRecord,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.settings_wake_gesture_cancel))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRecord,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(
                            if (gesture == null) {
                                R.string.settings_wake_gesture_record
                            } else {
                                R.string.settings_wake_gesture_rerecord
                            },
                        ),
                    )
                }
                if (gesture != null) {
                    OutlinedButton(
                        onClick = onClear,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(R.string.settings_wake_gesture_clear))
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.settings_wake_gesture_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        if (gesture != null) {
            SettingClickItem(
                title = stringResource(R.string.settings_wake_test_button),
                description = stringResource(R.string.settings_wake_gesture_test_hint),
                onClick = onTest,
            )
        }
    }
}

@Composable
private fun GestureStepRow(
    index: Int,
    step: UnlockStep,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = gestureStepText(step),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (step.delayBeforeMs > 0) {
                Text(
                    text = stringResource(
                        R.string.settings_wake_gesture_step_delay,
                        step.delayBeforeMs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun gestureStepText(step: UnlockStep): String = when (step) {
    is UnlockStep.Tap ->
        stringResource(R.string.settings_wake_gesture_step_tap, step.x, step.y)

    is UnlockStep.LongPress ->
        stringResource(
            R.string.settings_wake_gesture_step_long_press,
            step.x,
            step.y,
            step.holdMs,
        )

    is UnlockStep.Swipe -> {
        val first = step.points.firstOrNull()
        val last = step.points.lastOrNull()
        stringResource(
            R.string.settings_wake_gesture_step_swipe,
            first?.x ?: 0,
            first?.y ?: 0,
            last?.x ?: 0,
            last?.y ?: 0,
            last?.tMs ?: 0,
        )
    }
}

// ============================================================
// 日志与诊断
// ============================================================

@Composable
fun LogSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val debugMode by viewModel.debugMode.collectAsStateWithLifecycle()
    var showExportSheet by remember { mutableStateOf(false) }
    var showDebugModeConfirm by remember { mutableStateOf(false) }

    LogExportController(
        sheetVisible = showExportSheet,
        onSheetDismiss = { showExportSheet = false },
    )

    SettingsDetailScreen(
        title = stringResource(R.string.settings_category_log),
        navController = navController,
    ) {
        item {
            SettingsGroupCard {
                SettingClickItem(
                    title = stringResource(R.string.settings_log_history_title),
                    description = stringResource(R.string.settings_log_history_desc),
                    onClick = { navController.navigate(Routes.LOG_HISTORY) },
                )
                ListItemDivider()
                SettingClickItem(
                    title = stringResource(R.string.settings_log_error_title),
                    description = stringResource(R.string.settings_log_error_desc),
                    onClick = { navController.navigate(Routes.ERROR_LOG) },
                )
                ListItemDivider()
                SettingClickItem(
                    title = stringResource(R.string.settings_log_export_title),
                    description = stringResource(R.string.settings_log_export_desc),
                    onClick = { showExportSheet = true },
                )
            }
        }

        item {
            SettingsGroupCard {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_debug_mode_title),
                    description = stringResource(R.string.settings_debug_mode_desc),
                    checked = debugMode,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            showDebugModeConfirm = true
                        } else {
                            viewModel.setDebugMode(false)
                        }
                    },
                )
            }
        }
    }

    if (showDebugModeConfirm) {
        AdaptiveTaskPromptDialog(
            visible = true,
            title = stringResource(R.string.dialog_enable_debug_title),
            message = stringResource(R.string.dialog_enable_debug_message),
            onConfirm = {
                showDebugModeConfirm = false
                viewModel.setDebugMode(true)
            },
            onDismissRequest = { showDebugModeConfirm = false },
            confirmText = stringResource(R.string.common_confirm_restart),
            dismissText = stringResource(R.string.common_cancel),
            icon = Icons.Rounded.Build,
        )
    }
}

// ============================================================
// 数据：导入 / 导出配置
// ============================================================

@Composable
fun DataSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val settingsMessage by viewModel.settingsMessage.collectAsStateWithLifecycle()
    val showRestartDialog by viewModel.showRestartDialog.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openOutputStream(uri)?.let { viewModel.exportConfig(it) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openInputStream(uri)?.let { viewModel.importConfig(it) }
    }

    // ---- 任务包 ----
    val taskPacks by viewModel.taskPacks.collectAsStateWithLifecycle()
    val taskPackErrors by viewModel.taskPackErrors.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshTaskPacks() }

    // ---- 在线任务包（站点索引）----
    val remotePacks by viewModel.remotePacks.collectAsStateWithLifecycle()
    val remoteLoading by viewModel.remoteLoading.collectAsStateWithLifecycle()
    val remoteError by viewModel.remoteError.collectAsStateWithLifecycle()
    val installingPackId by viewModel.installingPackId.collectAsStateWithLifecycle()
    var showRemotePacks by rememberSaveable { mutableStateOf(false) }

    val taskPackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // `.m7atask` 不是注册过的 MIME，只能放开类型再靠内容（zip 魔数）判断
        context.contentResolver.openInputStream(uri)?.let { viewModel.importTaskPack(it) }
    }

    /** 导入前先过免责声明，同意后才打开文件选择器 */
    var showTaskPackDisclaimer by rememberSaveable { mutableStateOf(false) }

    settingsMessage?.let { msg ->
        Toast.makeText(context, msg.resolve(context), Toast.LENGTH_SHORT).show()
        viewModel.clearSettingsMessage()
    }

    SettingsDetailScreen(
        title = stringResource(R.string.settings_section_data),
        navController = navController,
    ) {
        item {
            SettingsGroupCard {
                SettingClickItem(
                    title = stringResource(R.string.settings_export_config_title),
                    description = stringResource(R.string.settings_export_config_desc),
                    onClick = { exportLauncher.launch("m7a_config.json") },
                )
                ListItemDivider()
                SettingClickItem(
                    title = stringResource(R.string.settings_import_config_title),
                    description = stringResource(R.string.settings_import_config_desc),
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                )
            }
        }

        // ---- 自定义任务包 ----
        item {
            SettingsGroupCard {
                SettingClickItem(
                    title = stringResource(R.string.settings_taskpack_import_title),
                    description = stringResource(R.string.settings_taskpack_import_desc),
                    // 先弹免责声明，读完才能继续选文件
                    onClick = { showTaskPackDisclaimer = true },
                )
                ListItemDivider()
                SettingClickItem(
                    title = stringResource(R.string.settings_taskpack_online_title),
                    description = stringResource(R.string.settings_taskpack_online_desc),
                    value = stringResource(R.string.settings_taskpack_online_browse),
                    onClick = {
                        showRemotePacks = true
                        viewModel.refreshRemotePacks()
                    },
                )
                ListItemDivider()
                SettingClickItem(
                    title = stringResource(R.string.settings_taskpack_installed_title),
                    description = if (taskPacks.isEmpty()) {
                        stringResource(R.string.settings_taskpack_empty)
                    } else {
                        stringResource(R.string.settings_taskpack_count, taskPacks.size)
                    },
                    value = stringResource(R.string.common_refresh),
                    onClick = { viewModel.refreshTaskPacks() },
                )
                taskPacks.forEach { pack ->
                    ListItemDivider()
                    TaskPackRow(pack = pack, onDelete = { viewModel.deleteTaskPack(pack) })
                }
                ListItemDivider()
                SettingClickItem(
                    title = stringResource(R.string.settings_taskpack_spec_title),
                    description = stringResource(R.string.settings_taskpack_spec_desc),
                    onClick = { openTaskFileSpec(context) },
                )
            }
        }
    }

    if (showRestartDialog) {
        AdaptiveTaskPromptDialog(
            visible = true,
            title = stringResource(R.string.dialog_import_success_title),
            message = stringResource(R.string.dialog_import_success_message),
            icon = Icons.Rounded.Build,
            confirmText = stringResource(R.string.common_restart_now),
            dismissText = stringResource(R.string.common_restart_later),
            onConfirm = { viewModel.confirmRestart() },
            onDismissRequest = { viewModel.dismissRestartDialog() },
        )
    }

    // 校验失败：整段列出所有问题，不用 Toast 挤成一行
    taskPackErrors?.let { errors ->
        AdaptiveTaskPromptDialog(
            visible = true,
            title = stringResource(R.string.settings_taskpack_invalid_title),
            message = errors.joinToString("\n") { "• $it" },
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = { viewModel.dismissTaskPackErrors() },
            onDismissRequest = { viewModel.dismissTaskPackErrors() },
        )
    }

    if (showTaskPackDisclaimer) {
        TaskPackDisclaimerDialog(
            onAgree = {
                showTaskPackDisclaimer = false
                // `.m7atask` 没有注册 MIME，只能放开类型、靠内容（zip 魔数）判断
                taskPackLauncher.launch(
                    arrayOf(
                        "application/json",
                        "application/zip",
                        "application/octet-stream",
                    )
                )
            },
            onDismiss = { showTaskPackDisclaimer = false },
        )
    }

    if (showRemotePacks) {
        RemoteTaskPackDialog(
            packs = remotePacks,
            loading = remoteLoading,
            error = remoteError,
            installingId = installingPackId,
            onRefresh = { viewModel.refreshRemotePacks() },
            onInstall = { viewModel.installRemotePack(it) },
            onDismiss = {
                showRemotePacks = false
                viewModel.clearRemoteError()
            },
        )
    }
}

/**
 * 在线任务包列表。
 *
 * 索引验签失败时**一个包都不列** —— 这里没有「忽略警告继续装」的入口，
 * 因为远程任务包是会被执行的指令，验签不过就等于不知道该听谁的。
 */
@Composable
private fun RemoteTaskPackDialog(
    packs: List<RemoteTaskPack>,
    loading: Boolean,
    error: String?,
    installingId: String?,
    onRefresh: () -> Unit,
    onInstall: (RemoteTaskPack) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_taskpack_online_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_taskpack_online_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onRefresh, enabled = !loading) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.common_refresh),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when {
                        loading && packs.isEmpty() -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.settings_taskpack_online_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // 原文直接展示：验签失败 / 站点没部署 / 断网 三种原因
                        // 处理方式完全不同，抹成一句「加载失败」就没法排查了
                        error != null -> Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        packs.isEmpty() -> Text(
                            text = stringResource(R.string.settings_taskpack_online_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> packs.forEach { pack ->
                            RemoteTaskPackRow(
                                pack = pack,
                                installing = installingId == pack.id,
                                enabled = installingId == null,
                                onInstall = { onInstall(pack) },
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.common_close))
                }
            }
        }
    }
}

@Composable
private fun RemoteTaskPackRow(
    pack: RemoteTaskPack,
    installing: Boolean,
    enabled: Boolean,
    onInstall: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = pack.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    if (pack.author.isNotBlank()) append(pack.author).append(" · ")
                    if (pack.version.isNotBlank()) append("v").append(pack.version)
                    if (pack.stepCount > 0) append(" · ").append(pack.stepCount).append(" 步")
                    if (pack.sizeText.isNotEmpty()) append(" · ").append(pack.sizeText)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pack.description.isNotBlank()) {
                Text(
                    text = pack.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (installing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            OutlinedButton(onClick = onInstall, enabled = enabled) {
                Text(stringResource(R.string.settings_taskpack_online_install))
            }
        }
    }
}

/** 已安装任务包一行：名字 + 版本/作者/步数 + 删除。 */
@Composable
private fun TaskPackRow(
    pack: InstalledTaskPack,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppDesignTokens.Spacing.listItemVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = pack.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.settings_taskpack_row_meta,
                    "v${pack.version}",
                    pack.author,
                    pack.stepCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { confirmDelete = true }) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.settings_taskpack_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (confirmDelete) {
        AdaptiveTaskPromptDialog(
            visible = true,
            title = pack.displayName,
            message = stringResource(R.string.settings_taskpack_delete),
            confirmText = stringResource(R.string.settings_taskpack_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismissRequest = { confirmDelete = false },
        )
    }
}

/** 规范文档放在仓库 docs/ 下，App 内不重复渲染一份，免得两边写飘。 */
private const val TASK_FILE_SPEC_URL =
    "https://github.com/biirekha857-star/March7thAssistantAndroidlocal/blob/main/docs/task-file.md"

private fun openTaskFileSpec(context: android.content.Context) {
    val ok = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TASK_FILE_SPEC_URL)))
    }.isSuccess
    if (!ok) {
        Toast.makeText(
            context,
            R.string.settings_taskpack_spec_desc,
            Toast.LENGTH_SHORT,
        ).show()
    }
}

/**
 * 导入任务包前的免责声明。
 *
 * **强制观看**：[DISCLAIMER_SECONDS] 秒内确认按钮不可点。这不是走过场 ——
 * 任务文件确实会代替用户在屏幕上点击，作者写错一步就是真的点歪，
 * 所以要让"我知道这东西会动我的屏幕"这件事被明确同意一次。
 *
 * 文案刻意写清**能做什么**和**不能做什么**：只写"后果自负"没有信息量，
 * 用户无法据此判断；写清边界，他才知道"最多是乱点，不会装别的 App"。
 *
 * 同时必须写明**来源**（第三方、来自网络、未审核）、**合法性自负**、
 * **恶意内容反馈渠道** 和 **免责** —— 任务包会真的消耗用户的游戏内资源，
 * 出问题时用户至少知道该找谁、当初同意了什么。
 */
@Composable
private fun TaskPackDisclaimerDialog(
    onAgree: () -> Unit,
    onDismiss: () -> Unit,
) {
    var left by remember { mutableStateOf(DISCLAIMER_SECONDS) }

    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1_000)
            left -= 1
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.taskpack_disclaimer_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.taskpack_disclaimer_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree, enabled = left <= 0) {
                Text(
                    if (left > 0) {
                        stringResource(R.string.taskpack_disclaimer_wait, left)
                    } else {
                        stringResource(R.string.taskpack_disclaimer_agree)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** 免责声明倒计时秒数。文案偏长（来源/合法性/免责三块），6 秒读不完，给 8 秒。 */
private const val DISCLAIMER_SECONDS = 8
