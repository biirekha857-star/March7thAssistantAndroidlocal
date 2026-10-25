package com.march7th.hkrpg.data.model.update

import androidx.annotation.StringRes
import com.march7th.hkrpg.R

enum class UpdateChannel(
    val value: String,
    @param:StringRes val resId: Int
) {
    STABLE("stable", R.string.update_channel_stable),
    BETA("beta", R.string.update_channel_beta)
}
