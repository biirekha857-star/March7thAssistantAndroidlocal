package com.march7th.hkrpg.presentation.view.panel.fight

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.march7th.hkrpg.presentation.components.SelectableChipGroup

/**
 * 关卡选择按钮组
 */
@Composable
fun StageButtonGroup(
    modifier: Modifier = Modifier,
    label: String,
    selectedValue: String,
    items: List<String>,
    onItemSelected: (String) -> Unit,
    displayMapper: (String) -> String = { it },
    isOpenCheck: (String) -> Boolean = { true },
) {
    SelectableChipGroup(
        label = label,
        selectedValue = selectedValue,
        options = items.map { it to displayMapper(it) },
        onSelected = onItemSelected,
        modifier = modifier,
        isItemEnabled = isOpenCheck
    )
}
