package com.march7th.hkrpg.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 通用的公招时长选择器（小时:分钟）
 *
 * 严格对齐 RecruitConfigPanel 的原始逻辑：
 * - 总时长范围: 60-540 分钟 (1:00 - 9:00)
 * - 循环修正: < 60 -> 540 (9:00), > 540 -> 60 (1:00)
 * - 步进: 分钟按 10 步进
 */
@Composable
fun RecruitTimeSelector(
    totalMinutes: Int,
    onTimeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val hour = totalMinutes / 60
    val minute = totalMinutes % 60

    Row(
        modifier = modifier.alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 小时输入：范围 1-9，格式 01-09
        INumericField(
            value = hour,
            onValueChange = { newHour ->
                if (enabled) {
                    val newTotal = newHour * 60 + minute
                    onTimeChange(validateRecruitTime(newTotal))
                }
            },
            minimum = 1,
            maximum = 9,
            valueFormat = "%02d",
            enabled = enabled,
            modifier = Modifier
                .width(60.dp)
                .height(56.dp)
        )

        Text(
            text = ":",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // 分钟输入：范围 0-50，步进 10，格式 00-50
        INumericField(
            value = minute,
            onValueChange = { newMin ->
                if (enabled) {
                    val newTotal = hour * 60 + newMin
                    onTimeChange(validateRecruitTime(newTotal))
                }
            },
            minimum = 0,
            maximum = 50,
            increment = 10,
            valueFormat = "%02d",
            enabled = enabled,
            modifier = Modifier
                .width(60.dp)
                .height(56.dp)
        )
    }
}

private fun validateRecruitTime(minutes: Int): Int {
    return when {
        minutes < 60 -> 540
        minutes > 540 -> 60
        else -> minutes
    }
}
