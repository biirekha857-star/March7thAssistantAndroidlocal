package com.march7th.hkrpg.domain.service

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.model.LogLevel
import com.march7th.hkrpg.manager.RemoteServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

interface GameFpsReader {
    /** null = 远端未监控或无法读取 */
    suspend fun readGameFps(): Float?
}

class RemoteGameFpsReader : GameFpsReader {
    override suspend fun readGameFps(): Float? = withContext(Dispatchers.IO) {
        runCatching { RemoteServiceManager.getInstanceOrNull()?.gameFps }.getOrNull()
            ?.takeIf { it >= 0f }
    }
}

/** 后台模式下轮询游戏帧率：供预览显示，并在持续低帧率时写会话日志 */
class GameFpsWatcher(
    private val reader: GameFpsReader,
    private val sessionLogger: CoreSessionLogger,
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    companion object {
        private const val POLL_INTERVAL_MS = 1_000L
    }

    private val advisor = GameFpsAdvisor()

    /** null = 未监控 */
    private val _fps = MutableStateFlow<Float?>(null)
    val fps: StateFlow<Float?> = _fps.asStateFlow()

    private var job: Job? = null

    fun start() {
        stop()
        advisor.reset()
        job = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                pollOnce()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _fps.value = null
    }

    @VisibleForTesting
    internal suspend fun pollOnce() {
        val value = reader.readGameFps()
        _fps.value = value

        // ## 低帧率警告已关闭
        //
        // 原来这里会在游戏帧率低于 50 / 30 时往会话日志写一条 WARNING / ERROR。
        // 实测问题：
        //
        // - 崩铁**默认就是 30 FPS**（省电模式的默认档位），几乎每次任务都会触发；
        // - 用户看到的是"任务出错"级别的红字，误以为程序有问题；
        // - 而且这条建议（"开启高帧率模式"）对**后台自动刷本毫无意义** ——
        //   战斗节奏由游戏自己控制，30 FPS 完全能正常打。
        //
        // 所以不再写日志，`fps` 状态流保留（首页预览仍能显示实时帧率）。
    }
}
