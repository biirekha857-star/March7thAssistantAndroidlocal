package com.march7th.hkrpg.presentation.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home

import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.march7th.hkrpg.R
import com.march7th.hkrpg.constant.Routes
import com.march7th.hkrpg.presentation.components.M7aWindowInsets
import com.march7th.hkrpg.theme.AppDesignTokens

sealed class BottomNavTab(
    val route: String, @param:StringRes val labelRes: Int, val icon: ImageVector
) {
    // 首页与后台任务页已合并为一页（预检条 + 控制台），故不再有独立的 BACKGROUND 项
    data object HOME : BottomNavTab(
        route = Routes.HOME, labelRes = R.string.bottom_nav_home, icon = Icons.Default.Home
    )

    data object SCHEDULE : BottomNavTab(
        route = Routes.SCHEDULE,
        labelRes = R.string.bottom_nav_schedule,
        icon = Icons.Default.DateRange
    )

    data object SETTINGS : BottomNavTab(
        route = Routes.SETTINGS,
        labelRes = R.string.bottom_nav_settings,
        icon = Icons.Default.Settings
    )

    companion object {
        val all = listOf(HOME, SCHEDULE, SETTINGS)
    }
}

@Composable
fun AppBottomNavigation(
    currentRoute: String,
    onTabSelected: (BottomNavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier, color = MaterialTheme.colorScheme.surface, shadowElevation = 0.dp
    ) {
        Column {
            HorizontalDivider(
                thickness = AppDesignTokens.Separator.thickness,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(M7aWindowInsets.bottomBar)
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomNavTab.all.forEach { tab ->
                    val label = stringResource(tab.labelRes)
                    val selected = currentRoute == tab.route
                    val contentColor = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    Column(
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onTabSelected(tab) }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 20.dp, vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = label,
                            modifier = Modifier.size(20.dp),
                            tint = contentColor
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor
                        )
                    }
                }
            }
        }
    }
}
