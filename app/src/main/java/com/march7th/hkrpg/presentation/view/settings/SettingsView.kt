package com.march7th.hkrpg.presentation.view.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.march7th.hkrpg.BuildConfig
import com.march7th.hkrpg.R
import com.march7th.hkrpg.constant.DefaultDisplayConfig
import com.march7th.hkrpg.constant.M7aApi
import com.march7th.hkrpg.constant.OFFICIAL_SHIZUKU_PACKAGE
import com.march7th.hkrpg.constant.Routes
import com.march7th.hkrpg.data.model.update.UpdateChannel
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.models.RemoteBackend
import com.march7th.hkrpg.domain.models.UnlockGesture
import com.march7th.hkrpg.domain.models.UnlockStep
import com.march7th.hkrpg.domain.service.ResourceInitService
import com.march7th.hkrpg.domain.state.ResourceInitState
import com.march7th.hkrpg.manager.ShizukuInstallHelper
import com.march7th.hkrpg.presentation.components.AdaptiveTaskPromptDialog
import com.march7th.hkrpg.presentation.components.ChangelogDialog
import com.march7th.hkrpg.presentation.components.CollapsibleSection
import com.march7th.hkrpg.presentation.components.ITextField
import com.march7th.hkrpg.presentation.components.ListItemDivider
import com.march7th.hkrpg.presentation.components.LogExportController
import com.march7th.hkrpg.presentation.components.ReInitializeConfirmDialog
import com.march7th.hkrpg.presentation.components.ResourceInitDialog
import com.march7th.hkrpg.presentation.components.SettingRow
import com.march7th.hkrpg.presentation.components.SettingsGroupCard
import com.march7th.hkrpg.presentation.components.TopAppBar
import com.march7th.hkrpg.presentation.viewmodel.SettingsViewModel
import com.march7th.hkrpg.theme.LocalReduceMotion
import com.march7th.hkrpg.theme.MaaAnimatedVisibility
import com.march7th.hkrpg.theme.MaaDesignTokens
import com.march7th.hkrpg.utils.Misc
import com.march7th.hkrpg.utils.UiScale
import com.march7th.hkrpg.utils.i18n.LocaleBootstrap.resolveSelectedLanguage
import com.march7th.hkrpg.utils.i18n.asString
import com.march7th.hkrpg.utils.i18n.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import com.march7th.hkrpg.domain.service.AchievementReporter

// LazyColumn 里「关于」分区的项序，item 顺序变了要同步
private const val ABOUT_ITEM_INDEX = 8

@Composable
fun SettingsView(
    navController: NavController,
    viewModel: SettingsViewModel = koinViewModel(),
    resourceInitService: ResourceInitService = koinInject(),
    achievementReporter: AchievementReporter = koinInject(),
) {
    val resourceInitState by resourceInitService.state.collectAsStateWithLifecycle()
    val showChangelog by viewModel.showChangelog.collectAsStateWithLifecycle()
    val currentChangelog by viewModel.currentChangelog.collectAsStateWithLifecycle()
    val debugMode by viewModel.debugMode.collectAsStateWithLifecycle()
    val autoCheckUpdate by viewModel.autoCheckUpdate.collectAsStateWithLifecycle()
    val autoDownloadUpdate by viewModel.autoDownloadUpdate.collectAsStateWithLifecycle()
    val startupBackend by viewModel.startupBackend.collectAsStateWithLifecycle()
    val skipShizukuCheck by viewModel.skipShizukuCheck.collectAsStateWithLifecycle()
    val shizukuShortcutEnabled by viewModel.shizukuShortcutEnabled.collectAsStateWithLifecycle()
    val shizukuLaunchPackage by viewModel.shizukuLaunchPackage.collectAsStateWithLifecycle()
    val reportToPenguin by viewModel.reportToPenguin.collectAsStateWithLifecycle()
    val reportToYituliu by viewModel.reportToYituliu.collectAsStateWithLifecycle()
    val penguinId by viewModel.penguinId.collectAsStateWithLifecycle()
    val yituliuOpenApiToken by viewModel.yituliuOpenApiToken.collectAsStateWithLifecycle()
    val operBoxUseYituliuApi by viewModel.operBoxUseYituliuApi.collectAsStateWithLifecycle()
    val yituliuVerifyMessage by viewModel.yituliuVerifyMessage.collectAsStateWithLifecycle()
    val yituliuVerifying by viewModel.yituliuVerifying.collectAsStateWithLifecycle()
    val forceFullscreenOnVirtualDisplay by viewModel.forceFullscreenOnVirtualDisplay.collectAsStateWithLifecycle()
    val pipOnHome by viewModel.pipOnHome.collectAsStateWithLifecycle()
    val wakeUnlockType by viewModel.wakeUnlockType.collectAsStateWithLifecycle()
    val wakeCredential by viewModel.wakeCredential.collectAsStateWithLifecycle()
    val wakeTestState by viewModel.wakeTestState.collectAsStateWithLifecycle()
    val unlockGesture by viewModel.unlockGesture.collectAsStateWithLifecycle()
    val gestureRecordState by viewModel.gestureRecordState.collectAsStateWithLifecycle()
    val updateChannel by viewModel.updateChannel.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val useSystemMonetColor by viewModel.useSystemMonetColor.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val settingsMessage by viewModel.settingsMessage.collectAsStateWithLifecycle()
    val showRestartDialog by viewModel.showRestartDialog.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val settingsListState = rememberLazyListState()

    wakeTestState?.let { state ->
        LaunchedEffect(state) {
            val done = state as? SettingsViewModel.WakeTestState.Done ?: return@LaunchedEffect
            Toast.makeText(
                context,
                done.result.message.resolve(context),
                Toast.LENGTH_LONG,
            ).show()
            viewModel.clearWakeTestResult()
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshGestureRecord() }

    gestureRecordState?.let { state ->
        val doneTemplate = stringResource(R.string.settings_wake_gesture_done)
        LaunchedEffect(state) {
            val message = when (state) {
                is SettingsViewModel.GestureRecordState.Done ->
                    doneTemplate.format(state.steps)

                is SettingsViewModel.GestureRecordState.Failed ->
                    state.result.message.resolve(context)

                else -> return@LaunchedEffect
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.clearGestureRecordState()
        }
    }

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

    var showShizukuAppPicker by remember { mutableStateOf(false) }
    var shizukuAppPickerLoadKey by remember { mutableIntStateOf(0) }
    var shizukuAppSearch by remember { mutableStateOf("") }
    var shizukuAppOptions by remember { mutableStateOf<List<ShizukuLaunchAppOption>?>(null) }
    var shizukuAppLoadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(showShizukuAppPicker, shizukuAppPickerLoadKey) {
        if (!showShizukuAppPicker) return@LaunchedEffect

        shizukuAppLoadFailed = false
        shizukuAppOptions = null
        shizukuAppOptions = try {
            withContext(Dispatchers.IO) {
                loadShizukuLaunchApps(context.applicationContext)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            shizukuAppLoadFailed = true
            emptyList()
        }
    }

    settingsMessage?.let { msg ->
        Toast.makeText(context, msg.resolve(context), Toast.LENGTH_SHORT).show()
        viewModel.clearSettingsMessage()
    }

    var showReInitConfirm by remember { mutableStateOf(false) }
    var showDebugModeConfirm by remember { mutableStateOf(false) }
    var showForceFullscreenConfirm by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    LogExportController(
        sheetVisible = showExportSheet,
        onSheetDismiss = { showExportSheet = false },
    )
    // 「切换资源目录」和「清除独立目录数据」两个确认弹窗已删除：
    // 触发它们的设置项（MAA 资源目录 / 清除独立目录数据）已从设置页移除，
    // 这两个弹窗永远不会被打开。
    if (showRestartDialog) {
        AdaptiveTaskPromptDialog(
            visible = true,
            title = stringResource(R.string.dialog_import_success_title),
            message = stringResource(R.string.dialog_import_success_message),
            icon = Icons.Rounded.Build,
            confirmText = stringResource(R.string.common_restart_now),
            dismissText = stringResource(R.string.common_restart_later),
            onConfirm = { viewModel.confirmRestart() },
            onDismissRequest = { viewModel.dismissRestartDialog() }
        )
    }

    if (showChangelog) {
        ChangelogDialog(
            content = currentChangelog?.markdown
                ?: stringResource(R.string.settings_about_changelog_empty),
            title = stringResource(R.string.settings_about_changelog),
            onDismiss = { viewModel.onDismissChangelog() }
        )
    }

    if (showReInitConfirm) {
        ReInitializeConfirmDialog(
            onConfirm = {
                showReInitConfirm = false
                coroutineScope.launch {
                    resourceInitService.reInitialize()
                }
            },
            onDismiss = { showReInitConfirm = false }
        )
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
            icon = Icons.Rounded.Build
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
            icon = Icons.Rounded.Build
        )
    }

    if (resourceInitState is ResourceInitState.Extracting) {
        ResourceInitDialog(
            state = resourceInitState,
            onRetry = {}
        )
    }

    if (showShizukuAppPicker) {
        val searchText = shizukuAppSearch.trim()
        val filteredOptions = shizukuAppOptions
            ?.filter { option ->
                searchText.isBlank() ||
                        option.label.contains(searchText, ignoreCase = true) ||
                        option.packageName.contains(searchText, ignoreCase = true)
            }
            .orEmpty()

        AdaptiveTaskPromptDialog(
            visible = true,
            title = stringResource(R.string.settings_shizuku_launch_app_picker_title),
            icon = Icons.Rounded.Build,
            confirmText = stringResource(R.string.common_close),
            dismissText = "",
            onConfirm = { showShizukuAppPicker = false },
            onDismissRequest = { showShizukuAppPicker = false },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when {
                        shizukuAppOptions == null -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.settings_shizuku_launch_app_loading),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        shizukuAppLoadFailed -> {
                            Text(
                                text = stringResource(R.string.settings_shizuku_launch_app_picker_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        else -> {
                            ITextField(
                                value = shizukuAppSearch,
                                onValueChange = { shizukuAppSearch = it },
                                placeholder = stringResource(R.string.settings_shizuku_launch_app_search_hint),
                                singleLine = true
                            )

                            if (filteredOptions.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.settings_shizuku_launch_app_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.heightIn(max = 320.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(filteredOptions, key = { it.packageName }) { option ->
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        viewModel.setShizukuLaunchPackage(option.packageName)
                                                        showShizukuAppPicker = false
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = option.label,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = option.packageName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title)
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        val contentColor = MaterialTheme.colorScheme.onSurface

        LazyColumn(
            state = settingsListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                horizontal = MaaDesignTokens.Spacing.listHorizontal,
                vertical = MaaDesignTokens.Spacing.sm
            ),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sectionGap)
        ) {
            // 日志
            item {
                CollapsibleSection(
                    title = stringResource(R.string.settings_section_log),
                    sectionKey = "settings_section_log",
                ) {
                    SettingsGroupCard {
                        SettingClickItem(
                            title = stringResource(R.string.settings_log_history_title),
                            description = stringResource(R.string.settings_log_history_desc),
                            contentColor = contentColor
                        ) {
                            navController.navigate("log_history")
                        }
                        ListItemDivider()
                        SettingClickItem(
                            title = stringResource(R.string.settings_log_error_title),
                            description = stringResource(R.string.settings_log_error_desc),
                            contentColor = contentColor
                        ) {
                            navController.navigate("error_log")
                        }
                        ListItemDivider()
                        SettingClickItem(
                            title = stringResource(R.string.settings_log_export_title),
                            description = stringResource(R.string.settings_log_export_desc),
                            contentColor = contentColor
                        ) {
                            showExportSheet = true
                        }
                        ListItemDivider()
                        SettingSwitchItem(
                            title = stringResource(R.string.settings_debug_mode_title),
                            description = stringResource(R.string.settings_debug_mode_desc),
                            contentColor = contentColor,
                            checked = debugMode,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    showDebugModeConfirm = true
                                } else {
                                    viewModel.setDebugMode(false)
                                }
                            }
                        )
                    }
                }
            }

            // 显示设置
            item {
                CollapsibleSection(
                    title = stringResource(R.string.settings_section_display),
                    sectionKey = "settings_section_display",
                ) {
                    SettingsGroupCard {
                        SettingLanguageItem(
                            contentColor = contentColor,
                            selectedLanguage = language,
                            onLanguageSelected = { viewModel.setLanguage(it) }
                        )
                        ListItemDivider()
                        SettingThemeSection(
                            contentColor = contentColor,
                            selectedMode = themeMode,
                            onModeSelected = { viewModel.setThemeMode(it) },
                            useSystemMonetColor = useSystemMonetColor,
                            onMonetColorChanged = { viewModel.setUseSystemMonetColor(it) }
                        )
                        ListItemDivider()
                        SettingClickItem(
                            title = stringResource(R.string.settings_background_title),
                            description = stringResource(R.string.settings_background_desc),
                            contentColor = contentColor
                        ) {
                            navController.navigate(Routes.WALLPAPER)
                        }
                    }
                }
            }

            // 其他设置
            item {
                CollapsibleSection(
                    title = stringResource(R.string.settings_section_other),
                    sectionKey = "settings_section_other",
                ) {
                    SettingsGroupCard {
                        SettingRadioItem(
                            title = stringResource(R.string.settings_startup_backend_title),
                            contentColor = contentColor,
                            entries = RemoteBackend.entries,
                            selected = startupBackend,
                            label = { it.display },
                            onSelected = { viewModel.setStartupBackend(it) }
                        )
                        ListItemDivider()
                        SettingSwitchItem(
                            title = stringResource(R.string.settings_skip_shizuku_check),
                            contentColor = contentColor,
                            checked = skipShizukuCheck,
                            enabled = startupBackend == RemoteBackend.SHIZUKU,
                            onCheckedChange = { viewModel.setSkipShizukuCheck(it) }
                        )
                        ListItemDivider()
                        SettingSwitchItem(
                            title = stringResource(R.string.settings_force_fullscreen_on_virtual_display),
                            description = stringResource(R.string.settings_force_fullscreen_on_virtual_display_desc),
                            contentColor = contentColor,
                            checked = forceFullscreenOnVirtualDisplay,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    showForceFullscreenConfirm = true
                                } else {
                                    viewModel.setForceFullscreenOnVirtualDisplay(false)
                                }
                            }
                        )
                        ListItemDivider()
                        SettingSwitchItem(
                            title = stringResource(R.string.settings_pip_on_home),
                            description = stringResource(R.string.settings_pip_on_home_desc),
                            contentColor = contentColor,
                            checked = pipOnHome,
                            onCheckedChange = { viewModel.setPipOnHome(it) }
                        )
                        ListItemDivider()
                        SettingWakeUnlockTypeItem(
                            contentColor = contentColor,
                            selectedType = wakeUnlockType,
                            onTypeSelected = { viewModel.setWakeUnlockType(it) },
                        )
                        MaaAnimatedVisibility(
                            visible = wakeUnlockType == AppSettingsManager.WAKE_TYPE_PIN,
                            enter = expandVertically(),
                            exit = shrinkVertically(),
                        ) {
                            Column {
                                ListItemDivider()
                                SettingWakePinSection(
                                    contentColor = contentColor,
                                    wakeCredential = wakeCredential,
                                    onCredentialChange = { viewModel.setWakeCredential(it) },
                                    onTest = { viewModel.runWakeTest() },
                                )
                            }
                        }
                        MaaAnimatedVisibility(
                            visible = wakeUnlockType == AppSettingsManager.WAKE_TYPE_GESTURE,
                            enter = expandVertically(),
                            exit = shrinkVertically(),
                        ) {
                            Column {
                                ListItemDivider()
                                SettingWakeGestureSection(
                                    contentColor = contentColor,
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

            // 「任务设置」整块已删除：
            //   - 划火柴模式（deployWithPause）：明日方舟的部署暂停技巧，与崩铁无关
            //   - MAA 任务覆盖（tasksOverride）：替换 MaaCore 的任务 JSON，
            //     崩铁走的是自己的 StarRailTaskRegistry，用不到
            // 连带删掉了 Routes.TASK_OVERRIDE_EDITOR 的入口。

            // 数据管理
            item {
                CollapsibleSection(
                    title = stringResource(R.string.settings_section_data),
                    sectionKey = "settings_section_data",
                ) {
                    SettingsGroupCard {
                        SettingClickItem(
                            title = stringResource(R.string.settings_export_config_title),
                            description = stringResource(R.string.settings_export_config_desc),
                            contentColor = contentColor
                        ) {
                            exportLauncher.launch("m7a_config.json")
                        }
                        ListItemDivider()
                        SettingClickItem(
                            title = stringResource(R.string.settings_import_config_title),
                            description = stringResource(R.string.settings_import_config_desc),
                            contentColor = contentColor
                        ) {
                            importLauncher.launch(arrayOf("application/json"))
                        }
                    }
                }
            }

            // 通知
            item {
                CollapsibleSection(
                    title = stringResource(R.string.settings_section_notification),
                    sectionKey = "settings_section_notification",
                ) {
                    SettingsGroupCard {
                        SettingClickItem(
                            title = stringResource(R.string.settings_notification_title),
                            description = stringResource(R.string.settings_notification_desc),
                            contentColor = contentColor
                        ) {
                            navController.navigate(Routes.NOTIFICATION)
                        }
                    }
                }
            }

            // 关于（ABOUT_ITEM_INDEX 指向这一项）
            item {
                CollapsibleSection(
                    title = stringResource(R.string.settings_section_about),
                    sectionKey = "settings_section_about",
                    forceExpanded = false,
                ) {
                    SettingsGroupCard {
                        SettingInfoRow(
                            label = stringResource(R.string.settings_about_version),
                            value = BuildConfig.VERSION_NAME,
                            contentColor = contentColor,
                        )
                        ListItemDivider()
                        SettingInfoRow(
                            label = stringResource(R.string.settings_about_developer),
                            value = "miguan",
                            contentColor = contentColor
                        )
                        ListItemDivider()
                        Column {
                            SettingClickItem(
                                title = stringResource(R.string.settings_about_faq_title),
                                description = stringResource(R.string.settings_about_faq_desc),
                                contentColor = contentColor,
                            ) {
                                Misc.openUriSafely(context, M7aApi.FAQ_URL)
                            }
                            ListItemDivider()
                            SettingClickItem(
                                title = stringResource(R.string.settings_about_feedback_title),
                                description = stringResource(R.string.settings_about_feedback_desc),
                                contentColor = contentColor,
                            ) {
                                Misc.openUriSafely(context, M7aApi.FEEDBACK_URL)
                            }
                        }
                        ListItemDivider()
                        SettingClickItem(
                            title = stringResource(R.string.settings_about_qq_group_title),
                            description = stringResource(R.string.settings_about_qq_group_desc),
                            contentColor = contentColor
                        ) {
                            achievementReporter.reportFeedbackGroupOpened()
                            Misc.openUriSafely(context, M7aApi.QQ_GROUP_URL)
                        }
                        ListItemDivider()
                        // ## Telegram 群
                        //
                        // 给海外或不用 QQ 的用户一条路。
                        // 复用 QQ 群的成就计数 —— 对用户来说都是"点开了反馈群"，
                        // 不必为渠道差异单独记一个成就。
                        SettingClickItem(
                            title = stringResource(R.string.settings_about_telegram_title),
                            description = stringResource(R.string.settings_about_telegram_desc),
                            contentColor = contentColor
                        ) {
                            achievementReporter.reportFeedbackGroupOpened()
                            Misc.openUriSafely(context, M7aApi.TELEGRAM_GROUP_URL)
                        }
                        ListItemDivider()
                        SettingClickItem(
                            title = stringResource(R.string.settings_about_changelog),
                            contentColor = contentColor
                        ) {
                            viewModel.onShowChangelog()
                        }
                        ListItemDivider()
                        Text(
                            text = stringResource(R.string.settings_about_star),
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    Misc.openUriSafely(
                                        context,
                                        "https://github.com/Aliothmoon/MAA-Meow"
                                    )
                                }
                                .padding(vertical = MaaDesignTokens.Spacing.listItemVertical),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SettingThemeSection(
    contentColor: Color,
    selectedMode: AppSettingsManager.ThemeMode,
    onModeSelected: (AppSettingsManager.ThemeMode) -> Unit,
    useSystemMonetColor: Boolean,
    onMonetColorChanged: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaaDesignTokens.Spacing.listItemVertical),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            Text(
                text = stringResource(R.string.settings_theme_title),
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                val modes = listOf(
                    AppSettingsManager.ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                    AppSettingsManager.ThemeMode.WHITE to stringResource(R.string.settings_theme_white),
                    AppSettingsManager.ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
                    AppSettingsManager.ThemeMode.PURE_DARK to stringResource(R.string.settings_theme_pure_dark),
                )
                modes.forEach { (mode, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .selectable(
                                selected = mode == selectedMode,
                                onClick = { onModeSelected(mode) },
                                role = Role.RadioButton,
                            ),
                    ) {
                        RadioButton(
                            selected = mode == selectedMode,
                            onClick = null,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ListItemDivider()
            SettingRow(
                title = stringResource(R.string.settings_monet_color_title),
                description = stringResource(R.string.settings_monet_color_desc),
                titleColor = contentColor,
                descriptionColor = contentColor.copy(alpha = 0.7f),
                trailing = {
                    Switch(
                        checked = useSystemMonetColor,
                        onCheckedChange = onMonetColorChanged,
                    )
                },
            )
        }
    }
}

@Composable
private fun SettingClickItem(
    title: String,
    description: String = "",
    contentColor: Color,
    onClick: () -> Unit
) {
    SettingRow(
        title = title,
        description = description.ifEmpty { null },
        titleColor = contentColor,
        descriptionColor = contentColor.copy(alpha = 0.7f),
        onClick = onClick,
    )
}

@Composable
private fun SettingWakeUnlockTypeItem(
    contentColor: Color,
    selectedType: String,
    onTypeSelected: (String) -> Unit,
) {
    // 三个选项字宽差得多，单选圆点排一行会左右不齐
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaaDesignTokens.Spacing.listItemVertical),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_wake_unlock_type),
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
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

@Composable
private fun SettingWakeGestureSection(
    contentColor: Color,
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
                color = contentColor,
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
                        color = contentColor,
                        modifier = Modifier.weight(1f),
                    )
                },
            ) {
                gesture.steps.forEachIndexed { index, step ->
                    GestureStepRow(index = index, step = step, contentColor = contentColor)
                }
            }
        }

        Text(
            text = stringResource(R.string.settings_wake_gesture_hint),
            style = MaterialTheme.typography.bodySmall,
            color = contentColor.copy(alpha = 0.7f),
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
                    color = contentColor,
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
            SettingRow(
                title = stringResource(R.string.settings_wake_test_button),
                description = stringResource(R.string.settings_wake_gesture_test_hint),
                titleColor = contentColor,
                descriptionColor = contentColor.copy(alpha = 0.7f),
                onClick = onTest,
            )
        }
    }
}

@Composable
private fun GestureStepRow(
    index: Int,
    step: UnlockStep,
    contentColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = contentColor.copy(alpha = 0.6f),
            modifier = Modifier.width(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = gestureStepText(step),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
            if (step.delayBeforeMs > 0) {
                Text(
                    text = stringResource(
                        R.string.settings_wake_gesture_step_delay,
                        step.delayBeforeMs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.6f),
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

@Composable
private fun SettingPenguinIdField(
    penguinId: String,
    onIdChange: (String) -> Unit,
) {
    var localId by rememberSaveable { mutableStateOf(penguinId) }
    LaunchedEffect(penguinId) {
        if (penguinId != localId) localId = penguinId
    }
    OutlinedTextField(
        value = localId,
        onValueChange = { raw ->
            localId = raw
            onIdChange(raw)
        },
        label = { Text(stringResource(R.string.settings_penguin_id)) },
        placeholder = { Text(stringResource(R.string.settings_penguin_id_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaaDesignTokens.Spacing.lg, vertical = 8.dp),
    )
}

@Composable
private fun SettingSecretField(
    value: String,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
    transform: (String) -> String = { it },
) {
    var local by rememberSaveable { mutableStateOf(value) }
    var visible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (value != local) local = value
    }
    OutlinedTextField(
        value = local,
        onValueChange = {
            val next = transform(it)
            local = next
            onValueChange(next)
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
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
private fun SettingYituliuTokenSection(
    token: String,
    verifying: Boolean,
    verifyMessage: SettingsViewModel.TokenVerifyMessage?,
    onTokenChange: (String) -> Unit,
    onVerify: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(
            horizontal = MaaDesignTokens.Spacing.lg,
            vertical = 8.dp,
        ),
    ) {
        SettingSecretField(
            value = token,
            label = stringResource(R.string.settings_yituliu_open_api_token),
            placeholder = stringResource(R.string.settings_yituliu_open_api_token_hint),
            keyboardType = KeyboardType.Password,
            onValueChange = onTokenChange,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (verifyMessage != null) {
                Text(
                    text = verifyMessage.text.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (verifyMessage.ok) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            TextButton(onClick = onVerify, enabled = !verifying) {
                Text(stringResource(R.string.settings_yituliu_token_verify))
            }
        }
    }
}

@Composable
private fun SettingWakePinSection(
    contentColor: Color,
    wakeCredential: String,
    onCredentialChange: (String) -> Unit,
    onTest: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        SettingSecretField(
            value = wakeCredential,
            label = stringResource(R.string.settings_wake_credential),
            placeholder = stringResource(R.string.settings_wake_credential_hint),
            keyboardType = KeyboardType.NumberPassword,
            onValueChange = onCredentialChange,
            transform = { it.filter(Char::isDigit).take(AppSettingsManager.MAX_PIN_LENGTH) },
        )
        Text(
            text = stringResource(R.string.settings_wake_credential_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        SettingRow(
            title = stringResource(R.string.settings_wake_test_button),
            description = stringResource(R.string.settings_wake_test_hint),
            titleColor = contentColor,
            descriptionColor = contentColor.copy(alpha = 0.7f),
            onClick = onTest,
        )
    }
}


@Composable
internal fun SettingSwitchItem(
    title: String,
    description: String? = null,
    contentColor: Color,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingRow(
        title = title,
        description = description,
        titleColor = contentColor,
        descriptionColor = contentColor.copy(alpha = 0.7f),
        enabled = enabled,
        trailing = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        },
    )
}

@Composable
private fun SettingInfoRow(
    label: String,
    value: String,
    contentColor: Color,
    onClick: (() -> Unit)? = null,
) {
    SettingRow(
        title = label,
        titleColor = contentColor,
        trailing = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor.copy(alpha = 0.7f)
            )
        },
        onClick = onClick,
    )
}

@Composable
private fun SettingChannelItem(
    contentColor: Color,
    selectedChannel: UpdateChannel,
    onChannelSelected: (UpdateChannel) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaaDesignTokens.Spacing.listItemVertical),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.rowTitleGap)
        ) {
            Text(
                text = stringResource(R.string.settings_update_channel_title),
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor
            )
            Text(
                text = stringResource(R.string.settings_update_channel_desc),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UpdateChannel.entries.forEach { channel ->
                val channelName = stringResource(channel.resId)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .selectable(
                            selected = channel == selectedChannel,
                            onClick = { onChannelSelected(channel) },
                            role = Role.RadioButton
                        )
                ) {
                    RadioButton(
                        selected = channel == selectedChannel,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = channelName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor
                    )
                }
            }
        }
    }
}


@Composable
private fun SettingLanguageItem(
    contentColor: Color,
    selectedLanguage: AppSettingsManager.AppLanguage,
    onLanguageSelected: (AppSettingsManager.AppLanguage) -> Unit
) {
    val effectiveSelectedLanguage = resolveSelectedLanguage(selectedLanguage)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaaDesignTokens.Spacing.listItemVertical),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_language_title),
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val options = listOf(
                AppSettingsManager.AppLanguage.ZH to stringResource(R.string.settings_language_zh),
                AppSettingsManager.AppLanguage.EN to stringResource(R.string.settings_language_en)
            )
            options.forEach { (lang, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .selectable(
                            selected = lang == effectiveSelectedLanguage,
                            onClick = { onLanguageSelected(lang) },
                            role = Role.RadioButton
                        )
                ) {
                    RadioButton(
                        selected = lang == effectiveSelectedLanguage,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> SettingRadioItem(
    title: String,
    contentColor: Color,
    entries: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaaDesignTokens.Spacing.listItemVertical),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            modifier = Modifier.weight(1f)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            entries.forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .selectable(
                            selected = entry == selected,
                            onClick = { onSelected(entry) },
                            role = Role.RadioButton
                        )
                ) {
                    RadioButton(
                        selected = entry == selected,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = label(entry),
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor
                    )
                }
            }
        }
    }
}

private data class ShizukuLaunchAppOption(
    val label: String,
    val packageName: String
)

private fun loadShizukuLaunchApps(context: Context): List<ShizukuLaunchAppOption> {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    // 应用列表查询较慢，调用方应在 IO 线程执行。
    return packageManager.queryIntentActivities(launcherIntent, 0)
        .mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
            val label = resolveInfo.loadLabel(packageManager).toString()
                .takeIf { it.isNotBlank() }
                ?: packageName
            ShizukuLaunchAppOption(label = label, packageName = packageName)
        }
        .distinctBy { it.packageName }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}
