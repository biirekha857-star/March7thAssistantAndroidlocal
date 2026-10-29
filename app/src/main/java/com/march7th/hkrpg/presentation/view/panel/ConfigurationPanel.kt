package com.march7th.hkrpg.presentation.view.panel

import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.model.AwardConfig
import com.march7th.hkrpg.data.model.DepotMaintainConfig
import com.march7th.hkrpg.data.model.FightConfig
import com.march7th.hkrpg.data.model.InfrastConfig
import com.march7th.hkrpg.data.model.MallConfig
import com.march7th.hkrpg.data.model.ReclamationConfig
import com.march7th.hkrpg.data.model.RecruitConfig
import com.march7th.hkrpg.data.model.PowerInstance
import com.march7th.hkrpg.data.model.DailyTaskConfig
import com.march7th.hkrpg.data.model.PowerTaskConfig
import com.march7th.hkrpg.data.model.RoguelikeConfig
import com.march7th.hkrpg.data.model.SwitchThemeConfig
import com.march7th.hkrpg.data.model.StartUpTaskConfig
import com.march7th.hkrpg.data.model.StarRailTaskConfig
import com.march7th.hkrpg.data.model.TaskChainNode
import com.march7th.hkrpg.data.model.TaskParamProvider
import com.march7th.hkrpg.data.model.TaskProfile
import com.march7th.hkrpg.data.model.TaskTypeInfo
import com.march7th.hkrpg.data.model.UserDataUpdateConfig
import com.march7th.hkrpg.data.model.WakeUpConfig
import com.march7th.hkrpg.presentation.components.ITextField
import com.march7th.hkrpg.presentation.view.panel.depot.DepotMaintainConfigPanel
import com.march7th.hkrpg.presentation.view.panel.fight.FightConfigPanel
import com.march7th.hkrpg.presentation.view.panel.mall.MallConfigPanel
import com.march7th.hkrpg.presentation.view.panel.roguelike.RoguelikeConfigPanel
import com.march7th.hkrpg.presentation.view.panel.switchtheme.SwitchThemeConfigPanel
import com.march7th.hkrpg.presentation.view.panel.userdata.UserDataUpdateConfigPanel

@Composable
fun TaskConfigPanel(
    selectedNode: TaskChainNode?,
    isEditMode: Boolean,
    isAddingTask: Boolean,
    isProfileMode: Boolean,
    profiles: List<TaskProfile>,
    activeProfileId: String,
    clientType: String,
    onConfigChange: (TaskParamProvider) -> Unit,
    onAddNode: (TaskTypeInfo) -> Unit,
    onRemoveNode: (String) -> Unit,
    onDuplicateNode: (String) -> Unit,
    onRenameNode: (String, String) -> Unit,
    onSwitchProfile: (String) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onDuplicateProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onCreateProfile: () -> Unit,
    onReorderProfile: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        when {
            // 配置管理模式
            isProfileMode -> {
                ProfileManagementPanel(
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    onSwitch = onSwitchProfile,
                    onRename = onRenameProfile,
                    onDuplicate = onDuplicateProfile,
                    onDelete = onDeleteProfile,
                    onCreate = onCreateProfile,
                    onReorder = onReorderProfile
                )
            }
            // 编辑模式：正在新增任务
            isEditMode && isAddingTask -> {
                TaskGalleryView(onAddNode = onAddNode)
            }

            // 编辑模式：已选中任务
            isEditMode && selectedNode != null -> {
                TaskManagementView(
                    node = selectedNode,
                    onRename = { onRenameNode(selectedNode.id, it) },
                    onDuplicate = { onDuplicateNode(selectedNode.id) },
                    onRemove = { onRemoveNode(selectedNode.id) })
            }

            // 编辑模式：未选中
            isEditMode -> {
                EmptyStateHint(
                    title = stringResource(R.string.panel_config_empty_edit_title),
                    descriptions = listOf(
                        stringResource(R.string.panel_config_empty_edit_desc_primary),
                        stringResource(R.string.panel_config_empty_edit_desc_add)
                    )
                )
            }

            // 普通模式：已选中任务
            // key(node.id)：切任务时销毁并重建配置面板，避免复用同一 composition/输入框节点，
            // 导致焦点残留跳转，以及各任务的 remember 状态（展开态/Tab/滚动/输入缓冲）互相串台
            !isEditMode && selectedNode != null -> key(selectedNode.id) {
                val cfg = selectedNode.config
                Box(modifier = Modifier.fillMaxSize()) {
                    when (cfg) {
                        is WakeUpConfig -> WakeUpConfigPanel(
                            config = cfg, onConfigChange = onConfigChange
                        )

                        is RecruitConfig -> RecruitConfigPanel(
                            config = cfg, onConfigChange = onConfigChange
                        )

                        is InfrastConfig -> InfrastConfigPanel(
                            config = cfg, onConfigChange = onConfigChange
                        )

                        is FightConfig -> FightConfigPanel(
                            config = cfg, clientType = clientType, onConfigChange = onConfigChange
                        )

                        is MallConfig -> MallConfigPanel(
                            config = cfg, onConfigChange = onConfigChange
                        )

                        is AwardConfig -> AwardConfigPanel(
                            config = cfg, onConfigChange = onConfigChange
                        )

                        is RoguelikeConfig -> RoguelikeConfigPanel(
                            config = cfg, onConfigChange = onConfigChange
                        )

                        is ReclamationConfig -> ReclamationConfigPanel(
                            config = cfg, onConfigChange = onConfigChange
                        )

                        is UserDataUpdateConfig -> UserDataUpdateConfigPanel(
                            config = cfg, onConfigChange = onConfigChange
                        )

                        is DepotMaintainConfig -> DepotMaintainConfigPanel(
                            config = cfg, onConfigChange = onConfigChange
                        )

                        is SwitchThemeConfig -> SwitchThemeConfigPanel(
                            config = cfg, onConfigChange = onConfigChange
                        )

                        // 崩铁任务：目前都不需要配置，显示占位说明
                        is StarRailTaskConfig -> NoConfigHint()

                        // 启动游戏：选客户端（本地 / 云游戏）
                        is StartUpTaskConfig -> StartUpConfigPanel(
                            config = cfg, onConfigChange = onConfigChange
                        )

                        // 清体力：选副本 + 次数上限 + 是否用燃料
                        is PowerTaskConfig -> PowerConfigPanel(
                            config = cfg, onConfigChange = onConfigChange
                        )

                        // 每日实训：选副本（排除周本）+ 是否自动消耗体力
                        is DailyTaskConfig -> DailyConfigPanel(
                            config = cfg, onConfigChange = onConfigChange
                        )
                    }
                }
            }

            // 普通模式：未选中
            else -> {
                EmptyStateHint(
                    title = stringResource(R.string.panel_config_empty_view_title),
                    descriptions = listOf(stringResource(R.string.panel_config_empty_view_desc))
                )
            }
        }
    }
}

@Composable
private fun EmptyStateHint(
    title: String, descriptions: List<String>, showReorderHint: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp)) // 固定顶部高度，防止切换标题时跳变

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.ExtraBold
        )

        Spacer(modifier = Modifier.height(16.dp))

        descriptions.forEach { desc ->
            HintItem(Icons.Default.Info, desc)
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (showReorderHint) {
            HintItem(Icons.Default.Info, stringResource(R.string.panel_config_reorder_hint))
        }
    }
}

@Composable
private fun HintItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .size(18.dp)
                .padding(top = 2.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 22.sp
        )
    }
}

@Composable
private fun TaskGalleryView(onAddNode: (TaskTypeInfo) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(
            stringResource(R.string.panel_config_select_type),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            // 隐藏任务（启动游戏）不提供手动添加：它已默认在链里且强制启用
            items(TaskTypeInfo.entries.filter { !it.hidden }) { typeInfo ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.clickable { onAddNode(typeInfo) }) {
                    Box(
                        modifier = Modifier.padding(12.dp), contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = taskTypeLabel(typeInfo),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskManagementView(
    node: TaskChainNode, onRename: (String) -> Unit, onDuplicate: () -> Unit, onRemove: () -> Unit
) {
    var text by remember(node.id) { mutableStateOf(node.name) }
    val typeDisplayName = taskTypeInfoForConfig(node.config)?.let { taskTypeLabel(it) }
        ?: stringResource(R.string.panel_config_unknown_task_type)

    val trimmedText = text.trim()
    val isError = trimmedText.isEmpty()
    val isTooLong = text.length > 20

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.panel_config_editing_badge),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = typeDisplayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        ITextField(
            value = text,
            onValueChange = { newText ->
                text = newText
                val name = newText.trim()
                if (name.isNotEmpty() && name.length <= 20 && name != node.name) {
                    onRename(name)
                }
            },
            label = stringResource(R.string.panel_config_task_name_label),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                if (isError) {
                    Text(stringResource(R.string.panel_config_name_empty))
                } else if (isTooLong) {
                    Text(stringResource(R.string.panel_config_name_too_long))
                }
            },
            outlineColor = if (isError || isTooLong) MaterialTheme.colorScheme.error else null
        )

        // 字数统计在 ITextField 下方展示，因为 ITextField 内部目前可能不支持 trailingIcon（取决于具体实现）
        Text(
            text = "${text.length}/20",
            style = MaterialTheme.typography.labelSmall,
            color = if (isTooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.End
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onDuplicate,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4.dp)
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.panel_config_duplicate_task))
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onRemove,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(4.dp)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.panel_config_delete_task))
        }
    }
}

/**
 * 崩铁任务的配置占位。
 *
 * 目前崩铁的邮件是"一键领取"语义，不需要参数。
 */
@Composable
private fun NoConfigHint() {
    EmptyStateHint(
        title = stringResource(R.string.panel_config_empty_view_title),
        descriptions = listOf(stringResource(R.string.panel_config_empty_view_desc)),
    )
}

/**
 * 清体力配置面板。
 *
 * ## ⚠️ 顶部必须先说清楚"这个版本不会真的刷"
 *
 * 当前实现只导航到副本入口就停（见 `PowerTask` 的类注释），
 * 因为选副本 / 开打都依赖还没落地的 OCR。
 *
 * 如果这里不说清楚，用户勾上「清体力」跑一轮，看到任务失败
 * 会以为是 bug —— 实际上那是**故意设计的保护**：
 * 宁可停在副本门口，也不乱点把体力花错地方。
 *
 * 所以面板第一屏就把这件事讲明白，而不是藏在日志里。
 */
/**
 * 清体力面板里「挑战次数」滑块的右端最大值。
 *
 * 为什么是 24：拟造花萼（金/赤）是单次消耗 10 体力、连打上限 24，
 * 是所有副本里最高的一个。用户"一次把体力刷完"的需求最多也就到
 * 这里；再往上没有实际意义（体力上限 240）。
 */
private const val MAX_RUNS_SLIDER = 24

@Composable
private fun PowerConfigPanel(
    config: PowerTaskConfig,
    onConfigChange: (TaskParamProvider) -> Unit,
) {
    val current = remember(config.instance) {
        PowerInstance.fromWireName(config.instance) ?: PowerInstance.DEFAULT
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ---- 顶部提示：当前版本的能力边界 ----
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.panel_power_not_implemented_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    lineHeight = 18.sp
                )
            }
        }

        // ---- 副本选择 ----
        Text(
            text = stringResource(R.string.panel_power_instance_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = stringResource(R.string.panel_power_instance_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PowerInstance.entries.forEach { inst ->
                val selected = inst == current
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onConfigChange(config.copy(instance = inst.wireName))
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = inst.wireName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(
                                R.string.panel_power_instance_cost,
                                inst.powerPerRun,
                                inst.maxAttemptsPerRun,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }

        // ---- 挑战次数（滑块）----
        //
        // 原来是 0/1/3/6/12 五个档位按钮，用户反馈"想打几次就得凑档"。
        // 改成连续滑块后：
        // - 直接拉到想要的次数；
        // - 实时显示"打几次 = 消耗几体力"，避免只看到数字没有概念；
        // - 0 表示不限制（体力够几次打几次）。
        Text(
            text = stringResource(R.string.panel_power_max_runs_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = stringResource(R.string.panel_power_max_runs_desc_slider),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (config.maxRuns == 0) {
                    stringResource(R.string.panel_power_max_runs_unlimited)
                } else {
                    stringResource(R.string.panel_power_runs_value, config.maxRuns)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            if (config.maxRuns > 0) {
                Text(
                    text = stringResource(
                        R.string.panel_power_runs_cost,
                        config.maxRuns * current.powerPerRun,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // ## 次数上限 = 游戏内的「单次连打上限」
        //
        // 每个副本在游戏里能一次设的连打次数是**固定的**，
        // 而且各不相同：
        //
        // | 副本 | 单次上限 |
        // |---|---|
        // | 拟造花萼（金/赤） | 24 |
        // | 凝滞虚影 | 8 |
        // | 侵蚀隧洞 | 6 |
        // | 饰品提取 | 6 |
        // | 历战余响 | 3 |
        //
        // 尤其是历战余响：每周只有 3 次有奖励，设再多也是白打。
        // 所以滑块上限直接跟着副本走，不再写死 24。
        val sliderMax = current.maxAttemptsPerRun

        // 切换副本后如果原值超了，自动压到新上限
        LaunchedEffect(sliderMax) {
            if (config.maxRuns > sliderMax) {
                onConfigChange(config.copy(maxRuns = sliderMax))
            }
        }


        Slider(
            value = config.maxRuns.toFloat(),
            onValueChange = { onConfigChange(config.copy(maxRuns = it.toInt())) },
            valueRange = 0f..sliderMax.toFloat(),
            steps = sliderMax - 1,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.panel_power_runs_unlimited_short),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$sliderMax",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 每日实训的配置面板。
 *
 * # 勾选式，不做任务识别
 *
 * 每项对应游戏里的一条实训，用户在面板上勾掉今天不做的那几项即可。
 * 分值写在右侧，方便自己算够不够 500。
 *
 * 这样避开了早期 OCR 扫描任务列表的所有坑（拆字、误识、布局浮动）。
 */
@Composable
private fun DailyConfigPanel(
    config: DailyTaskConfig,
    onConfigChange: (TaskParamProvider) -> Unit,
) {
    val candidates = remember { config.candidates() }
    val current = remember(config.instance) {
        PowerInstance.fromWireName(config.instance)
            ?: PowerInstance.CALYX_CRIMSON
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.panel_daily_checklist_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    lineHeight = 18.sp
                )
            }
        }

        // ---- 子项勾选 ----
        Text(
            text = stringResource(R.string.panel_daily_items_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )

        DailyCheckItem(
            title = stringResource(R.string.panel_daily_item_dispatch),
            score = 100,
            checked = config.doDispatch,
            onCheckedChange = { onConfigChange(config.copy(doDispatch = it)) }
        )
        DailyCheckItem(
            title = stringResource(R.string.panel_daily_item_photo),
            score = 100,
            checked = config.doPhoto,
            onCheckedChange = { onConfigChange(config.copy(doPhoto = it)) }
        )
        DailyCheckItem(
            title = stringResource(R.string.panel_daily_item_synthesize),
            score = 100,
            checked = config.doSynthesize,
            onCheckedChange = { onConfigChange(config.copy(doSynthesize = it)) }
        )
        DailyCheckItem(
            title = stringResource(R.string.panel_daily_item_technique),
            score = 200,
            checked = config.doTechnique,
            onCheckedChange = { onConfigChange(config.copy(doTechnique = it)) }
        )
        DailyCheckItem(
            title = stringResource(R.string.panel_daily_item_power),
            score = 200,
            checked = config.doPower,
            onCheckedChange = { onConfigChange(config.copy(doPower = it)) }
        )

        // ---- 刷本选择（仅勾了消耗体力才显示）----
        if (config.doPower) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = stringResource(R.string.panel_daily_instance_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.panel_daily_instance_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                candidates.forEach { inst ->
                    val selected = inst == current
                    val runs = config.runsForTarget(inst)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onConfigChange(config.copy(instance = inst.wireName))
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = inst.wireName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(
                                    R.string.panel_daily_instance_runs,
                                    runs,
                                    runs * inst.powerPerRun,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ---- 领奖 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onConfigChange(config.copy(doClaim = !config.doClaim)) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = config.doClaim,
                onCheckedChange = { onConfigChange(config.copy(doClaim = it)) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.panel_daily_claim_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.panel_daily_claim_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 每日实训面板里的一个勾选行。
 *
 * @param score 这项值多少活跃度（显示在右侧，方便用户算总分）
 */
@Composable
private fun DailyCheckItem(
    title: String,
    score: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "+$score",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun taskTypeInfoForConfig(config: TaskParamProvider): TaskTypeInfo? {    // 崩铁任务的参数都是 StarRailTaskConfig，按 type 字段精确匹配；
    // 方舟任务的参数类型各不相同，走类名匹配。
    if (config is StarRailTaskConfig) {
        return TaskTypeInfo.entries.firstOrNull { it.taskType == config.type }
    }
    return TaskTypeInfo.entries.firstOrNull { it.defaultConfig()::class == config::class }
}

@Composable
private fun taskTypeLabel(typeInfo: TaskTypeInfo): String {
    return when (typeInfo) {
        // 崩铁任务（成就已删除）
        TaskTypeInfo.START_UP -> stringResource(R.string.panel_task_type_start_up)
        TaskTypeInfo.MAIL -> stringResource(R.string.panel_task_type_mail)
        TaskTypeInfo.POWER -> stringResource(R.string.panel_task_type_power)
        TaskTypeInfo.DAILY -> stringResource(R.string.panel_task_type_daily)
        TaskTypeInfo.CLOSE_DOWN -> stringResource(R.string.panel_task_type_close_down)
    }
}

/**
 * 「启动游戏」的配置面板 —— **选择启动哪个客户端**。
 *
 * 交互和清体力选副本一致：一列可点的选项卡片，选中态高亮。
 *
 * ```
 * ○ 本地客户端        com.miHoYo.hkrpg
 * ● 云游戏            com.miHoYo.cloudgames.hkrpg
 * ```
 *
 * 不做成两个独立任务，是因为手机同一时刻只能跑一个游戏实例，
 * 两个「启动」任务并存没有意义。
 */
@Composable
private fun StartUpConfigPanel(
    config: StartUpTaskConfig,
    onConfigChange: (TaskParamProvider) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.panel_startup_client_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    lineHeight = 18.sp
                )
            }
        }

        Text(
            text = stringResource(R.string.panel_startup_client_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )

        StartUpTaskConfig.GameClient.entries.forEach { client ->
            val selected = client == config.client
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
                border = BorderStroke(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onConfigChange(config.copy(client = client)) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = clientLabel(client),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Text(
                            text = client.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ---- 启动超时 ----
        Text(
            text = stringResource(R.string.panel_startup_timeout_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = stringResource(R.string.panel_startup_timeout_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${config.effectiveTimeoutSec} 秒",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Slider(
                value = config.effectiveTimeoutSec.toFloat(),
                onValueChange = {
                    onConfigChange(config.copy(timeoutSec = it.roundToInt()))
                },
                valueRange = 60f..600f,
                steps = 17,
                modifier = Modifier.weight(2f)
            )
        }
    }
}

/** 客户端显示名。 */
@Composable
private fun clientLabel(client: StartUpTaskConfig.GameClient): String = when (client) {
    StartUpTaskConfig.GameClient.LOCAL ->
        stringResource(R.string.panel_startup_client_local)
    StartUpTaskConfig.GameClient.CLOUD ->
        stringResource(R.string.panel_startup_client_cloud)
}
