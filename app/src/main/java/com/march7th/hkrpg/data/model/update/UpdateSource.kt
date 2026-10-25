package com.march7th.hkrpg.data.model.update

import androidx.annotation.StringRes
import com.march7th.hkrpg.R

/**
 * 更新源类型
 */
enum class UpdateSource(
    @param:StringRes val resId: Int,
    val type: Int
) {
    GITHUB(R.string.update_source_github, 1),
    MIRROR_CHYAN(R.string.update_source_mirror_chyan, 2)
}
