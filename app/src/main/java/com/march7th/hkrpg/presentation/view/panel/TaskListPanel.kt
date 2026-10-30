package com.march7th.hkrpg.presentation.view.panel

import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.model.DailyTaskConfig
import com.march7th.hkrpg.data.model.PowerTaskConfig
import com.march7th.hkrpg.data.model.StartUpTaskConfig
import com.march7th.hkrpg.data.model.StarRailTaskConfig
import com.march7th.hkrpg.data.model.TaskChainNode
import com.march7th.hkrpg.data.model.TaskParamProvider
import com.march7th.hkrpg.data.model.TaskTypeInfo
import com.march7th.hkrpg.data.model.taskTypeOrNull
import com.march7th.hkrpg.theme.AppAnimatedVisibility
import com.march7th.hkrpg.theme.AppDesignTokens
import sh.calvin.reorderable.ReorderableColumn

/**
 * 任务链卡片流。
 *
 * ## 为什么不再是「左列表 + 右配置」
 *
 * 老布局是 MAA 那套双栏：左边一列勾选框，右边半屏常驻配置面板。
 * 但当前只有三个任务真的有参数（启动游戏 / 清体力 / 每日实训），
 * 其余任务点开来只是一句「无需配置」——右半屏常常是空的，
 * 而且手机的横向空间本来就不够，列表被挤成一条窄缝。
 *
 * 现在改成单栏卡片流：一张卡 = 一个任务，摘要写在第二行，
 * 需要参数时卡片就地展开。列表和配置不再抢宽度。
 *
 * ## 隐藏任务不显示
 *
 * [TaskTypeInfo.hidden] 的任务（目前是「启动游戏」）不进这个列表：
 * 它由 [com.march7th.hkrpg.data.preferences.TaskChainState] 强制插入并启用，
 * 用户没必要看到、也不该能取消勾选 —— 取消后所有后续任务都会
 * 对着游戏标题页空转。
 *
 * ⚠️ 过滤只影响**显示**：`onNodeMove` 的索引是列表索引，而
 * 隐藏任务永远排在链首（由 ensureHiddenTasksEnabled 保证），
 * 所以这里的索引偏移不会错位。
 */
@Composable
fun TaskListPanel(
    nodes: List<TaskChainNode>,
    /** 当前展开的卡片；null = 全部收起 */
    expandedNodeId: String?,
    isEditMode: Boolean,
    isProfileMode: Boolean,
    onNodeEnabledChange: (String, Boolean) -> Unit,
    onNodeToggle: (String) -> Unit,
    onNodeMove: (Int, Int) -> Unit,
    onToggleEditMode: () -> Unit,
    onToggleProfileMode: () -> Unit,
    onToggleAddingTask: () -> Unit,
    onConfigChange: (String, TaskParamProvider) -> Unit,
    onRenameNode: (String, String) -> Unit,
    onDuplicateNode: (String) -> Unit,
    onRemoveNode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleNodes = remember(nodes) {
        nodes.filterNot { node ->
            val type = node.config.taskTypeOrNull()
            type != null && TaskTypeInfo.entries.any { it.hidden && it.taskType == type }
        }
    }
    val enabledCount = remember(visibleNodes) { visibleNodes.count { it.enabled } }

    Column(modifier = modifier) {
        TaskListToolbar(
            total = visibleNodes.size,
            enabled = enabledCount,
            isEditMode = isEditMode,
            isProfileMode = isProfileMode,
            onToggleEditMode = onToggleEditMode,
            onToggleProfileMode = onToggleProfileMode,
            onToggleAddingTask = onToggleAddingTask,
        )

        if (visibleNodes.isEmpty()) {
            EmptyTaskChainHint(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            ReorderableColumn(
                list = visibleNodes,
                onSettle = { fromIndex, toIndex -> onNodeMove(fromIndex, toIndex) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { _, node, _ ->
                key(node.id) {
                    ReorderableItem {
                        TaskNodeCard(
                            node = node,
                            isExpanded = expandedNodeId == node.id,
                            isEditMode = isEditMode,
                            onEnabledChange = { onNodeEnabledChange(node.id, it) },
                            onToggle = { onNodeToggle(node.id) },
                            onConfigChange = { onConfigChange(node.id, it) },
                            onRename = { onRenameNode(node.id, it) },
                            onDuplicate = { onDuplicateNode(node.id) },
                            onRemove = { onRemoveNode(node.id) },
                            // 只有编辑模式才允许拖动：普通模式下长按拖会误触
                            modifier = if (isEditMode) Modifier.longPressDraggableHandle() else Modifier,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 顶部工具条：左边是一句统计，右边三个动作。
 *
 * 老布局这里塞了两张整行大按钮（「编辑配置」「编辑任务」），
 * 垂直空间被吃掉一截；改成图标按钮后同样能点到，但不占高度。
 */
@Composable
private fun TaskListToolbar(
    total: Int,
    enabled: Int,
    isEditMode: Boolean,
    isProfileMode: Boolean,
    onToggleEditMode: () -> Unit,
    onToggleProfileMode: () -> Unit,
    onToggleAddingTask: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.panel_task_list_summary, total, enabled),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )

        // 配置方案
        ToolbarIconButton(
            icon = Icons.Rounded.SwapHoriz,
            contentDescription = stringResource(R.string.panel_task_list_edit_config),
            active = isProfileMode,
            onClick = onToggleProfileMode,
        )

        // 新增任务（只在编辑模式出现，避免平时误触）
        if (isEditMode) {
            ToolbarIconButton(
                icon = Icons.Default.Add,
                contentDescription = stringResource(R.string.panel_task_list_add),
                active = false,
                onClick = onToggleAddingTask,
            )
        }

        ToolbarIconButton(
            icon = if (isEditMode) Icons.Default.Check else Icons.Default.Edit,
            contentDescription = stringResource(
                if (isEditMode) R.string.common_done else R.string.panel_task_list_edit_tasks
            ),
            active = isEditMode,
            onClick = onToggleEditMode,
        )
    }
}

@Composable
private fun ToolbarIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(AppDesignTokens.CornerRadius.inner),
        color = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        modifier = Modifier
            .padding(start = 6.dp)
            .size(34.dp)
            .clip(RoundedCornerShape(AppDesignTokens.CornerRadius.inner))
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
                tint = if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * 一张任务卡。
 *
 * 收起态只有一行：图标 / 名称 + 摘要 / 开关。
 * 展开后追加配置（普通模式）或改名 + 复制 + 删除（编辑模式）。
 */
@Composable
private fun TaskNodeCard(
    node: TaskChainNode,
    isExpanded: Boolean,
    isEditMode: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onToggle: () -> Unit,
    onConfigChange: (TaskParamProvider) -> Unit,
    onRename: (String) -> Unit,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = node.enabled
    // 未启用的任务整卡降透明，让"这一项不跑"一眼可见
    val contentAlpha = if (enabled) 1f else 0.55f

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppDesignTokens.CornerRadius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (isExpanded) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TaskTypeBadge(icon = node.taskTypeIcon(), enabled = enabled)

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = node.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = taskSummary(node),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 开关让出点击区域的一部分，但仍然可点
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .scale(0.82f),
                    colors = SwitchDefaults.colors(
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )

                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(if (isExpanded) 180f else 0f),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }

            AppAnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 58.dp, end = 12.dp, bottom = 12.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 10.dp),
                    )

                    if (isEditMode) {
                        NodeRenameEditor(node = node, onRename = onRename)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onDuplicate,
                                shape = RoundedCornerShape(AppDesignTokens.CornerRadius.inner),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.panel_task_card_duplicate),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            OutlinedButton(
                                onClick = onRemove,
                                shape = RoundedCornerShape(AppDesignTokens.CornerRadius.inner),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.panel_task_card_delete),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    } else {
                        TaskConfigBody(node = node, onConfigChange = onConfigChange)
                    }
                }
            }
        }
    }
}

/** 任务类型图标底片：形状和大小统一，颜色随启用状态变化。 */
@Composable
private fun TaskTypeBadge(icon: ImageVector, enabled: Boolean) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(AppDesignTokens.CornerRadius.inner))
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
    }
}

@Composable
private fun EmptyTaskChainHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Tune,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.panel_task_list_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 任务类型 → 图标。老类型（方舟遗留）统一给个通用图标。 */
private fun TaskChainNode.taskTypeIcon(): ImageVector = when (config.taskTypeOrNull()) {
    TaskTypeInfo.START_UP.taskType -> Icons.Rounded.PlayArrow
    TaskTypeInfo.MAIL.taskType -> Icons.Rounded.Email
    TaskTypeInfo.POWER.taskType -> Icons.Rounded.BatteryChargingFull
    TaskTypeInfo.DAILY.taskType -> Icons.Rounded.Checklist
    TaskTypeInfo.CLOSE_DOWN.taskType -> Icons.Rounded.PowerSettingsNew
    else -> Icons.Rounded.Tune
}

/**
 * 卡片第二行的摘要：让用户不展开也知道这一项会怎么做。
 *
 * 只描述**影响行为的字段**，不做花哨的格式化。
 */
@Composable
private fun taskSummary(node: TaskChainNode): String = when (val cfg = node.config) {
    is StartUpTaskConfig -> stringResource(
        R.string.panel_task_card_summary_client, clientLabel(cfg.client)
    )

    is PowerTaskConfig -> {
        val instance = cfg.instance
        if (cfg.maxRuns > 0) {
            stringResource(R.string.panel_task_card_summary_power, instance, cfg.maxRuns)
        } else {
            stringResource(R.string.panel_task_card_summary_power_unlimited, instance)
        }
    }

    is DailyTaskConfig -> {
        val picked = listOf(
            cfg.doDispatch, cfg.doPhoto, cfg.doSynthesize,
            cfg.doTechnique, cfg.doPower, cfg.doClaim,
        ).count { it }
        if (picked == 0) {
            stringResource(R.string.panel_task_card_summary_daily_none)
        } else {
            stringResource(R.string.panel_task_card_summary_daily, picked)
        }
    }

    is StarRailTaskConfig -> stringResource(R.string.panel_task_card_no_config)

    else -> stringResource(R.string.panel_task_card_no_config)
}
