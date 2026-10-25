package com.march7th.hkrpg.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.presentation.state.UiEffect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 应用级事件 → UI 提示。
 *
 * ## 原本只做一件事：弹「成就解锁」提示
 *
 * MAA-Meow 自带一套"元成就"系统（帕拉斯勋章等），解锁时通过这里
 * 发 Toast。那套系统和崩铁游戏内成就无关，已整体删除，
 * 所以本 ViewModel 现在**不再产生任何事件**。
 *
 * 保留空壳是为了不动 `AppNavigation` 里的接线；等哪天有真正的
 * 应用级事件（比如"资源加载完成"）再往 [effects] 里塞。
 */
class AppEventsViewModel(
    @Suppress("unused") private val appSettings: AppSettingsManager,
) : ViewModel() {

    /** 无事件。 */
    val effects: Flow<UiEffect> = emptyFlow()
}
