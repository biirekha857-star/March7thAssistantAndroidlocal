package com.march7th.hkrpg.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.march7th.hkrpg.theme.AppDesignTokens

@Composable
private fun BaseCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = AppDesignTokens.Card.elevation),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        // ## 为什么白色卡片还要描边
        //
        // 卡片底色是 surface（纯白）、页面底色是 background（#F4F5F7），
        // 两者只差 3% 亮度 —— 再加上本项目 elevation 恒为 0（无投影），
        // 白卡在灰底上**几乎没有边界**，一屏卡片会糊成一片。
        //
        // 描一条 1dp 的极淡线（outlineVariant）既解决边界问题，又比加阴影
        // 更符合"工具型界面"的观感：投影会引入灰雾感，线条不会。
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content,
        )
    }
}

@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    title: String = "",
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    BaseCard(
        modifier = modifier,
        contentPadding = PaddingValues(AppDesignTokens.Card.innerPadding),
        containerColor = containerColor,
    ) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                modifier = Modifier.padding(bottom = AppDesignTokens.Spacing.sm),
            )
        }
        content()
    }
}

@Composable
fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    BaseCard(
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = AppDesignTokens.Card.innerPadding,
            vertical = AppDesignTokens.Spacing.xs,
        ),
        containerColor = containerColor,
        content = content,
    )
}
