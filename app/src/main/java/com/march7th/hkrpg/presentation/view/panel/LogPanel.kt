package com.march7th.hkrpg.presentation.view.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.model.LogColorRole
import com.march7th.hkrpg.data.model.LogItem
import com.march7th.hkrpg.data.model.LogLevel
import com.march7th.hkrpg.data.model.LogSeverity
import com.march7th.hkrpg.data.model.RecruitCombination
import com.march7th.hkrpg.presentation.components.AdaptiveTaskPromptDialog
import com.march7th.hkrpg.theme.LocalLogPalette
import com.march7th.hkrpg.theme.themedColor

/** 时间列宽度：`HH:mm:ss` 在 10sp 等宽字体下约 48dp，留一点余量。 */
private val TimeColumnWidth = 52.dp

/** 时间列和内容之间那条竖线的槽宽。 */
private val AxisSlotWidth = 12.dp

/** 行内边距：竖线的上下端点、圆点的位置都以它为基准。 */
private val EntryVerticalPadding = 5.dp

/**
 * 任务执行日志面板（时间线）。
 *
 * ## 为什么改成时间线
 *
 * 老实现是「时间戳 + 级别徽章 + 正文」三列平铺，每行都挂一个
 * 带底色的 INFO 方块。级别色块比正文还抢眼，一眼看过去全是色块，
 * 真正要紧的 WARNING / ERROR 反而淹在里面。
 *
 * 现在把级别收进左侧时间轴上的圆点：普通日志是灰线灰点，
 * 警告/错误才亮起来并加粗。想确认级别可以点开详情，那里仍有完整徽章。
 */
@Composable
fun LogPanel(
    modifier: Modifier = Modifier,
    logs: List<LogItem>,
    onClearLogs: () -> Unit,
    onExportLogs: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    var isAutoScroll by remember { mutableStateOf(true) }
    var selectedLog by remember { mutableStateOf<LogItem?>(null) }

    LaunchedEffect(logs.size, isAutoScroll) {
        if (isAutoScroll && logs.isNotEmpty()) {
            listState.scrollToItem(logs.size - 1)
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            isAutoScroll = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LogToolbar(
            count = logs.size,
            onExportLogs = onExportLogs,
            onClearLogs = onClearLogs,
        )

        Box(modifier = Modifier.weight(1f)) {
            if (logs.isEmpty()) {
                EmptyLogHint(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    itemsIndexed(items = logs, key = { _, item -> item.id }) { index, logItem ->
                        TimelineEntry(
                            logItem = logItem,
                            isFirst = index == 0,
                            isLast = index == logs.lastIndex,
                            onClick = { selectedLog = logItem },
                        )
                    }
                }
            }

            if (listState.canScrollForward && logs.isNotEmpty()) {
                IconButton(
                    onClick = { isAutoScroll = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.panel_log_resume_auto_scroll),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    selectedLog?.let { log ->
        LogDetailDialog(
            logItem = log,
            onDismiss = { selectedLog = null }
        )
    }
}

/** 顶部工具条：标题 + 条数 + 导出 / 清空。 */
@Composable
private fun LogToolbar(
    count: Int,
    onExportLogs: (() -> Unit)?,
    onClearLogs: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Timeline,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.panel_log_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(modifier = Modifier.weight(1f))

        if (onExportLogs != null) {
            IconButton(onClick = onExportLogs, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.FolderZip,
                    contentDescription = stringResource(R.string.settings_log_export_title),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onClearLogs, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.common_clear),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 时间线上的一条记录。
 *
 * 左列时间 → 竖轴上的圆点 → 正文。圆点颜色就是级别色，
 * 警告/错误额外放大并加粗正文，其余保持安静。
 */
@Composable
private fun TimelineEntry(
    logItem: LogItem,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val levelColor = logItem.level.themedColor()
    val severity = logItem.level.severity
    val emphasized = severity == LogSeverity.WARNING || severity == LogSeverity.ERROR
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val dotSize = if (emphasized) 8.dp else 6.dp
    val dotTop = EntryVerticalPadding + if (emphasized) 9.dp else 10.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val axisX = (TimeColumnWidth + 6.dp + AxisSlotWidth / 2).toPx()
                val dotCenterY = (EntryVerticalPadding + 13.dp).toPx()
                val stroke = 1.dp.toPx()
                if (!isFirst) {
                    drawLine(
                        color = axisColor,
                        start = Offset(axisX, 0f),
                        end = Offset(axisX, dotCenterY),
                        strokeWidth = stroke,
                    )
                }
                if (!isLast) {
                    drawLine(
                        color = axisColor,
                        start = Offset(axisX, dotCenterY),
                        end = Offset(axisX, size.height),
                        strokeWidth = stroke,
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(vertical = EntryVerticalPadding),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = logItem.formattedTime,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(TimeColumnWidth),
        )

        Spacer(modifier = Modifier.width(6.dp))

        Box(
            modifier = Modifier.width(AxisSlotWidth),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = dotTop)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(if (emphasized) levelColor else axisColor.copy(alpha = 0.9f))
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = logItem.content,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = if (emphasized) levelColor else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (logItem.hasDetails) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.panel_log_view_details),
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(13.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 没有日志时的空态：告诉用户日志会在开跑后出现，而不是留一片空白。 */
@Composable
private fun EmptyLogHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Timeline,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.panel_log_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogDetailDialog(
    logItem: LogItem,
    onDismiss: () -> Unit
) {
    val levelColor = logItem.level.themedColor()
    AdaptiveTaskPromptDialog(
        visible = true,
        title = stringResource(R.string.log_detail_title),
        onConfirm = onDismiss,
        onDismissRequest = onDismiss,
        confirmText = stringResource(R.string.common_confirm),
        dismissText = null,
        icon = Icons.Rounded.Info,
        iconTint = levelColor,
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LogLevelBadge(level = logItem.level, color = levelColor)
                    Text(
                        text = logItem.formattedTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = logItem.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = levelColor
                )

                val recruitTooltip = logItem.recruitTooltip
                val plainTooltip = logItem.tooltip
                if (recruitTooltip != null || plainTooltip != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.panel_log_details_section),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            if (recruitTooltip != null) {
                                val tooltipTextColor = MaterialTheme.colorScheme.onSurface
                                val richTooltip = rememberRecruitTooltipAnnotated(
                                    data = recruitTooltip,
                                    defaultColor = tooltipTextColor,
                                )
                                Text(
                                    text = richTooltip,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tooltipTextColor,
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                        .verticalScroll(rememberScrollState())
                                )
                            } else {
                                Text(
                                    text = plainTooltip!!,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                        .verticalScroll(rememberScrollState())
                                )
                            }
                        }
                    }
                }

                logItem.screenshotPath?.let { path ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.panel_log_screenshot_section),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { /* TODO: 打开图片 */ }
                        )
                    }
                }
            }
        }
    )
}

/** 详情弹窗里的级别徽章。 */
@Composable
private fun LogLevelBadge(
    level: LogLevel,
    color: Color,
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = stringResource(level.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * [defaultColor] 用于干员名等无星级修饰的片段，应与外层 [Text.color] 一致——
 * 否则外层 [Surface] 切换 `LocalContentColor` 会让默认色与外层不一致。
 */
@Composable
private fun rememberRecruitTooltipAnnotated(
    data: List<RecruitCombination>,
    defaultColor: Color,
): AnnotatedString {
    val palette = LocalLogPalette.current
    return remember(data, palette, defaultColor) {
        val starColors = arrayOf(
            Color.Unspecified, // 星级从 1 开始，索引 0 占位
            palette[LogColorRole.STAR_1],
            palette[LogColorRole.STAR_2],
            palette[LogColorRole.STAR_3],
            palette[LogColorRole.STAR_4],
            palette[LogColorRole.STAR_5],
            palette[LogColorRole.STAR_6],
        )

        fun colorOf(star: Int): Color = starColors.getOrNull(star) ?: defaultColor
        buildAnnotatedString {
            data.forEachIndexed { i, combo ->
                withStyle(
                    SpanStyle(
                        color = colorOf(combo.starLevel),
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append("${combo.starLevel}★ Tags:  ${combo.tags.joinToString("  ")}")
                }
                combo.opers.forEach { oper ->
                    append("\n  ")
                    withStyle(SpanStyle(color = colorOf(oper.starLevel))) {
                        append("★".repeat(oper.starLevel))
                    }
                    withStyle(SpanStyle(color = defaultColor)) {
                        append(" ${oper.name}")
                    }
                }
                if (i < data.lastIndex) append("\n\n")
            }
        }
    }
}
