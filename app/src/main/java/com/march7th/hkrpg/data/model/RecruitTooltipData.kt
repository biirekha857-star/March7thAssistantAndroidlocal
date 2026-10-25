package com.march7th.hkrpg.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class RecruitCombination(
    val starLevel: Int,
    val tags: List<String>,
    /** 已按星级倒序 */
    val opers: List<RecruitOper>
)

@Immutable
data class RecruitOper(
    val starLevel: Int,
    val name: String
)
