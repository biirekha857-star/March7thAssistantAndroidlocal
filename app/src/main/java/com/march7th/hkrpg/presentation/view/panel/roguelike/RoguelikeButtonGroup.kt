package com.march7th.hkrpg.presentation.view.panel.roguelike

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.march7th.hkrpg.presentation.components.SelectableChipGroup

/**
 * 通用按钮组组件
 */
@Composable
fun RoguelikeButtonGroup(
    label: String,
    selectedValue: String,
    options: List<Pair<String, String>>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    SelectableChipGroup(
        label = label,
        selectedValue = selectedValue,
        options = options,
        onSelected = onValueChange,
        modifier = modifier,
        labelFontWeight = FontWeight.Medium
    )
}
