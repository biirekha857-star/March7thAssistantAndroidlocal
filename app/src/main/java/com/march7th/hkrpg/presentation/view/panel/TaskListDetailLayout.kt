package com.march7th.hkrpg.presentation.view.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.model.TaskChainNode
import com.march7th.hkrpg.data.model.TaskParamProvider
import com.march7th.hkrpg.data.model.TaskProfile
import com.march7th.hkrpg.data.model.TaskTypeInfo
import com.march7th.hkrpg.theme.AppDesignTokens

/**
 * 任务页的容器。
 *
 * ## 从双栏改成单栏
 *
 * 老实现是 `Row { 任务列表; 配置面板 }`：左边一列狭窄的勾选框，
 * 右边半屏常驻配置。问题是当前只有三个任务真有参数，其余点开来
 * 只有一句「无需配置」，右半屏经常是空的；而手机的横向空间本来就
 * 紧张，列表被 `IntrinsicSize.Max` 压成一条缝，任务名长一点就得截断。
 *
 * 现在三档职责：
 * 1. 配置方案 —— 整页二级视图（列表 → 返回）；
 * 2. 新增任务 —— 整页二级视图（选类型 → 自动回列表）；
 * 3. 默认 —— 卡片流，参数就地展开。
 *
 * 参数保持与老签名一致，调用方（[com.march7th.hkrpg.presentation.view.background.BackgroundTaskView]）不需要改。
 */
@Composable
fun TaskListDetailLayout(
    nodes: List<TaskChainNode>,
    selectedNode: TaskChainNode?,
    selectedNodeId: String?,
    isEditMode: Boolean,
    isAddingTask: Boolean,
    isProfileMode: Boolean,
    profiles: List<TaskProfile>,
    activeProfileId: String,
    /** 老参数：客户端类型只在老配置面板里用到，单栏下按节点类型渲染，不再需要 */
    @Suppress("UNUSED_PARAMETER") clientType: String,
    onNodeEnabledChange: (String, Boolean) -> Unit,
    onNodeSelected: (String) -> Unit,
    onNodeMove: (Int, Int) -> Unit,
    onToggleEditMode: () -> Unit,
    onToggleAddingTask: () -> Unit,
    onToggleProfileMode: () -> Unit,
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
    modifier: Modifier = Modifier,
    /** 老参数：右侧配置区是否包一层 Card。单栏下已无右侧面板，保留仅为兼容调用方 */
    @Suppress("UNUSED_PARAMETER") wrapDetailInCard: Boolean = false,
) {
    val configuration = LocalConfiguration.current
    // 与 OverlayController.calculatePanelLayout 一致：浮窗约 0.85 屏宽
    val floatMaxWidth = (configuration.screenWidthDp * 0.85f).dp

    // 哪张卡展开着。ViewModel 只有「选中」没有「取消选中」，
    // 所以收起状态放在页面本地：再点一次同一张卡就收起。
    var expandedNodeId by rememberSaveable { mutableStateOf<String?>(null) }
    // 新增任务 / 切换方案后 ViewModel 会改写选中项，跟着展开新卡
    LaunchedEffect(selectedNodeId) {
        val id = selectedNodeId ?: return@LaunchedEffect
        if (id != expandedNodeId) expandedNodeId = id
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val contentModifier = if (constraints.hasBoundedWidth) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .widthIn(max = floatMaxWidth)
                .fillMaxHeight()
                .fillMaxWidth()
        }

        Box(modifier = contentModifier) {
            when {
                isProfileMode -> SecondaryPane(
                    title = stringResource(R.string.panel_task_list_profiles_title),
                    onBack = onToggleProfileMode,
                ) {
                    ProfileManagementPanel(
                        profiles = profiles,
                        activeProfileId = activeProfileId,
                        onSwitch = onSwitchProfile,
                        onRename = onRenameProfile,
                        onDuplicate = onDuplicateProfile,
                        onDelete = onDeleteProfile,
                        onCreate = onCreateProfile,
                        onReorder = onReorderProfile,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                isAddingTask -> SecondaryPane(
                    title = stringResource(R.string.panel_task_list_add_title),
                    onBack = onToggleAddingTask,
                ) {
                    // 选中类型后 ViewModel 自己会把 isAddingTask 置回 false
                    TaskGalleryView(onAddNode = onAddNode)
                }

                else -> TaskListPanel(
                    nodes = nodes,
                    expandedNodeId = expandedNodeId,
                    isEditMode = isEditMode,
                    isProfileMode = isProfileMode,
                    onNodeEnabledChange = onNodeEnabledChange,
                    onNodeToggle = { nodeId ->
                        expandedNodeId = if (expandedNodeId == nodeId) {
                            // 已展开 → 收起（不动 ViewModel 的选中，避免影响其它逻辑）
                            null
                        } else {
                            onNodeSelected(nodeId)
                            nodeId
                        }
                    },
                    onNodeMove = onNodeMove,
                    onToggleEditMode = onToggleEditMode,
                    onToggleProfileMode = onToggleProfileMode,
                    onToggleAddingTask = onToggleAddingTask,
                    onConfigChange = { nodeId, config ->
                        // 只有展开的那张卡能改配置，它就是 ViewModel 的选中项
                        val target = selectedNode?.takeIf { it.id == nodeId } ?: nodes.firstOrNull { it.id == nodeId }
                        if (target != null) onConfigChange(config)
                    },
                    onRenameNode = onRenameNode,
                    onDuplicateNode = onDuplicateNode,
                    onRemoveNode = { nodeId ->
                        onRemoveNode(nodeId)
                        if (expandedNodeId == nodeId) expandedNodeId = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 二级整页视图：顶部一行返回 + 标题，下面是内容。
 *
 * 用它替换原来的「右半屏面板」，让方案管理 / 新增任务都能占满整页，
 * 而不是挤在屏幕右半边。
 */
@Composable
private fun SecondaryPane(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(AppDesignTokens.CornerRadius.inner))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            content()
        }
    }
}
